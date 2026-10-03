/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui.Components;

import android.content.Context;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ai.AiSummaryClient;
import org.telegram.messenger.ai.AiSummarySettings;
import org.telegram.messenger.ai.PromptOptions;
import org.telegram.messenger.ai.SummaryMessage;
import org.telegram.messenger.ai.SummaryQuestionPrompt;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A short-lived conversation grounded in one completed summary's immutable original messages. */
public final class SummaryQuestionSheet {
    public interface SourcePreview {
        /** The parent must verify the current server message before showing or navigating to it. */
        void preview(SummaryMessage source, int reference);
    }

    private static final Pattern REFERENCE = Pattern.compile("\\[m([0-9]+)\\]");
    private static final long PARTIAL_INTERVAL_MS = 150;

    private final BaseFragment fragment;
    private final int account;
    private final long ownerId;
    private final AiSummarySettings.Config config;
    private final PromptOptions options;
    private final SourcePreview sourcePreview;
    private final Context context;
    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout content;
    private final AlertDialog dialog;
    private final ArrayList<SummaryQuestionPrompt.Turn> history = new ArrayList<>();
    private List<SummaryMessage> sources;
    private AiSummaryClient client;
    private EditTextBoldCursor questionInput;
    private TextView progressView;
    private TextView partialView;
    private Runnable lifecycleTick;
    private Runnable partialUpdate;
    private int generation;
    private boolean closed;
    private boolean running;
    private String draft = "";
    private String requestQuestion;
    private String streamingDraft;
    private String lastError;
    private String progress = "正在准备本次原文";
    private long startedAt;
    private long lastPartialAt;

    /**
     * The parent owns this handle and must dismiss or invalidate it when its range, prompt,
     * sources, account or visibility changes. The child does not replace the fragment's dialog.
     */
    public static SummaryQuestionSheet show(BaseFragment fragment, int account, long ownerId,
            AiSummarySettings.Config config, List<SummaryMessage> sources, PromptOptions options,
            SourcePreview sourcePreview) {
        if (fragment == null || fragment.isFinished || fragment.getParentActivity() == null
                || fragment.getParentActivity().isFinishing() || account < 0
                || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId == 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId
                || config == null || options == null || sources == null || sources.isEmpty()) {
            return null;
        }
        for (SummaryMessage source : sources) {
            if (source == null) return null;
        }
        SummaryQuestionSheet sheet = new SummaryQuestionSheet(fragment, account, ownerId,
                config, sources, options, sourcePreview);
        sheet.render();
        sheet.dialog.setOnDismissListener(ignored -> sheet.onDismissed());
        sheet.dialog.setCanceledOnTouchOutside(true);
        // BaseFragment.showDialog dismisses the parent summary and invalidates this snapshot.
        try {
            sheet.dialog.show();
        } catch (RuntimeException unavailableWindow) {
            // The activity can lose its window while a fragment transition is finishing.
            sheet.onDismissed();
            return null;
        }
        if (sheet.dialog.getWindow() != null) {
            sheet.dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            sheet.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        sheet.startLifecycleTick();
        return sheet;
    }

    private SummaryQuestionSheet(BaseFragment fragment, int account, long ownerId,
            AiSummarySettings.Config config, List<SummaryMessage> sources, PromptOptions options,
            SourcePreview sourcePreview) {
        this.fragment = fragment;
        this.account = account;
        this.ownerId = ownerId;
        this.config = config;
        this.options = options;
        this.sourcePreview = sourcePreview;
        this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        context = fragment.getParentActivity();
        resourcesProvider = fragment.getResourceProvider();
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        content.setPadding(dp(24), 0, dp(24), dp(8));
        // AlertDialog supplies a scroll container for its custom view.
        dialog = new AlertDialog.Builder(context, resourcesProvider)
                .setTitle("追问本次消息")
                .setView(content)
                .setNegativeButton("返回总结", (ignored, which) -> dismiss())
                .create();
    }

    public void dismiss() {
        onDismissed();
        dialog.dismiss();
    }

    /** Invalidated evidence cannot remain readable or continue a background inference. */
    public void invalidate(String reason) {
        dismiss();
    }

    public boolean isShowing() {
        return !closed && dialog.isShowing();
    }

    private boolean ownerActive() {
        return !closed && !fragment.isFinished && fragment.getParentActivity() != null
                && !fragment.getParentActivity().isFinishing()
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    private boolean active(int request) {
        if (!ownerActive()) {
            dismiss();
            return false;
        }
        return request == generation;
    }

    private void onDismissed() {
        if (closed) return;
        closed = true;
        cancelRequest();
        if (lifecycleTick != null) {
            AndroidUtilities.cancelRunOnUIThread(lifecycleTick);
            lifecycleTick = null;
        }
        AndroidUtilities.hideKeyboard(content);
        history.clear();
        sources = null;
        draft = requestQuestion = streamingDraft = lastError = null;
        questionInput = null;
        progressView = partialView = null;
        content.removeAllViews();
    }

    private void cancelRequest() {
        generation++;
        running = false;
        if (client != null) {
            client.cancel();
            client = null;
        }
        if (partialUpdate != null) {
            AndroidUtilities.cancelRunOnUIThread(partialUpdate);
            partialUpdate = null;
        }
    }

    private void startLifecycleTick() {
        lifecycleTick = new Runnable() {
            @Override
            public void run() {
                if (!ownerActive()) {
                    dismiss();
                    return;
                }
                updateProgress();
                AndroidUtilities.runOnUIThread(this, 1000);
            }
        };
        AndroidUtilities.runOnUIThread(lifecycleTick, 1000);
    }

    private void render() {
        if (!ownerActive()) {
            dismiss();
            return;
        }
        content.removeAllViews();
        questionInput = null;
        progressView = partialView = null;
        addText("仅使用本次总结的 " + sources.size() + " 条原文；最多追问 "
                + SummaryQuestionPrompt.MAX_TURNS + " 轮。消息范围和总结方向固定。", false);
        addText("已完成 " + history.size() + "/" + SummaryQuestionPrompt.MAX_TURNS
                + " 轮 · 关闭后清除本次问答", false);
        for (int i = 0; i < history.size(); i++) {
            SummaryQuestionPrompt.Turn turn = history.get(i);
            addText("问题 " + (i + 1) + "：" + turn.question, true);
            TextView answer = addText(linkSources(turn.answer), false);
            answer.setTextColor(color(Theme.key_dialogTextBlack));
            answer.setMovementMethod(LinkMovementMethod.getInstance());
            answer.setLinksClickable(true);
        }
        if (requestQuestion != null && (running || lastError != null)) {
            addText("本轮问题：" + requestQuestion, true);
        }
        if (running) {
            progressView = addText("", false);
            updateProgress();
            addText("回答生成中，以下内容尚未完成，引用暂不可点击。", false);
            partialView = addText(streamingDraft == null || streamingDraft.isEmpty()
                    ? "等待模型回复…" : streamingDraft, false);
            partialView.setLinksClickable(false);
            addAction("取消本轮", () -> {
                cancelRequest();
                lastError = "已取消。本轮未计入问答历史，可修改问题后重试。";
                draft = requestQuestion;
                render();
            });
            return;
        }
        if (lastError != null) {
            addText(lastError, false);
            if (streamingDraft != null && !streamingDraft.isEmpty()) {
                addText("未完成内容，尚未通过引用校验", true);
                TextView unfinished = addText(streamingDraft, false);
                unfinished.setLinksClickable(false);
            }
        }
        if (history.size() >= SummaryQuestionPrompt.MAX_TURNS) {
            addText("本次问答已完成 5 轮。可返回总结页重新开启问答。", true);
            return;
        }
        questionInput = new EditTextBoldCursor(context);
        questionInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        questionInput.setTextColor(color(Theme.key_dialogTextBlack));
        questionInput.setHintTextColor(color(Theme.key_dialogTextGray));
        questionInput.setCursorColor(color(Theme.key_dialogTextLink));
        questionInput.setCursorWidth(1.5f);
        questionInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        questionInput.setSingleLine(false);
        questionInput.setMinLines(2);
        questionInput.setMaxLines(5);
        questionInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        questionInput.setPadding(dp(2), dp(10), dp(2), dp(10));
        questionInput.setHint("例如：这个决定有哪些原文依据？");
        questionInput.setText(draft);
        content.addView(questionInput, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 4));
        TextView counter = addText("", false);
        updateCount(counter, draft);
        questionInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                draft = s.toString();
                updateCount(counter, draft);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        addAction("为什么这样决定", () -> fillQuestion("为什么这样决定？请指出决定和理由的原文依据。"));
        addAction("还有哪些分歧", () -> fillQuestion("本次消息中还有哪些分歧或尚未解决的问题？"));
        addAction("谁负责跟进", () -> fillQuestion("谁负责跟进，明确约定的待办和时间分别是什么？"));
        addAction(lastError == null ? "提问" : "重试本轮", () -> ask(config));
        if (lastError != null && config.stream) {
            addAction("使用普通模式重试", () -> ask(new AiSummarySettings.Config(config.baseUrl,
                    config.model, config.apiKey, config.maxOutputTokens, false, config.inputCharacterBudget)));
            addText("仅本轮使用普通模式，沿用相同原文和已完成的问答。", false);
        }
    }

    private void fillQuestion(String question) {
        if (!ownerActive() || running || questionInput == null) return;
        questionInput.setText(question);
        questionInput.setSelection(questionInput.length());
        questionInput.requestFocus();
    }

    private void ask(AiSummarySettings.Config requestConfig) {
        if (!ownerActive() || running) return;
        String question = questionInput == null ? draft : questionInput.getText().toString();
        try {
            SummaryQuestionPrompt.validate(question, history);
        } catch (IllegalArgumentException error) {
            draft = question;
            lastError = error.getMessage();
            render();
            return;
        }
        AndroidUtilities.hideKeyboard(questionInput);
        cancelRequest();
        final int request = generation;
        requestQuestion = question.trim();
        draft = requestQuestion;
        streamingDraft = null;
        lastError = null;
        progress = "正在准备本次原文";
        startedAt = SystemClock.elapsedRealtime();
        lastPartialAt = 0;
        running = true;
        render();
        client = new AiSummaryClient();
        client.ask(requestConfig, sources, options, requestQuestion, new ArrayList<>(history),
                new AiSummaryClient.Callback() {
            @Override public void onProgress(AiSummaryClient.Progress update) {
                if (!active(request)) return;
                switch (update.stage) {
                    case SOURCE:
                        progress = "正在核对原文 " + update.completed + "/" + update.total;
                        break;
                    case MERGE:
                        progress = "正在合并依据，第 " + update.mergeRound + " 轮 "
                                + update.completed + "/" + update.total;
                        break;
                    case VALIDATING:
                        progress = "正在校验回答和引用";
                        break;
                }
                updateProgress();
            }

            @Override public void onPartial(String text) {
                if (!active(request)) return;
                streamingDraft = text;
                if (partialUpdate != null) return;
                partialUpdate = () -> {
                    partialUpdate = null;
                    if (!active(request) || partialView == null) return;
                    partialView.setText(streamingDraft);
                    lastPartialAt = SystemClock.elapsedRealtime();
                };
                AndroidUtilities.runOnUIThread(partialUpdate, Math.max(0,
                        PARTIAL_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastPartialAt)));
            }

            @Override public void onSuccess(String answer) {
                if (!active(request)) return;
                try {
                    Set<Integer> allowed = new LinkedHashSet<>();
                    for (int i = 1; i <= sources.size(); i++) allowed.add(i);
                    SummaryQuestionPrompt.validateAnswer(answer, allowed);
                    SummaryQuestionPrompt.Turn completed = new SummaryQuestionPrompt.Turn(requestQuestion, answer);
                    cancelRequest();
                    history.add(completed);
                    draft = "";
                    requestQuestion = streamingDraft = lastError = null;
                    render();
                } catch (IllegalArgumentException error) {
                    onError(error.getMessage());
                }
            }

            @Override public void onError(String error) {
                if (!active(request)) return;
                cancelRequest();
                lastError = error == null ? "问答未完成，请重试。" : error;
                draft = requestQuestion;
                render();
            }
        });
    }

    private void updateProgress() {
        if (running && progressView != null) {
            progressView.setText(progress + " · 已用 "
                    + Math.max(0L, (SystemClock.elapsedRealtime() - startedAt) / 1000) + " 秒");
        }
    }

    private void updateCount(TextView view, String value) {
        int count = value == null ? 0 : value.codePointCount(0, value.length());
        view.setText(count + "/" + SummaryQuestionPrompt.MAX_QUESTION_CODE_POINTS
                + (count > SummaryQuestionPrompt.MAX_QUESTION_CODE_POINTS ? " 字符，需缩短后提问" : " 字符"));
    }

    private CharSequence linkSources(String answer) {
        SpannableStringBuilder linked = new SpannableStringBuilder(answer);
        Matcher references = REFERENCE.matcher(answer);
        while (references.find()) {
            final int reference;
            try {
                reference = Integer.parseInt(references.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (sources == null || reference < 1 || reference > sources.size()) continue;
            SummaryMessage source = sources.get(reference - 1);
            linked.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    if (!ownerActive() || sourcePreview == null) return;
                    sourcePreview.preview(source, reference);
                }
                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(color(Theme.key_dialogTextLink));
                    paint.setUnderlineText(true);
                }
            }, references.start(), references.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return linked;
    }

    private TextView addText(CharSequence text, boolean title) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, title ? 16 : 14);
        view.setTextColor(color(title ? Theme.key_dialogTextBlack : Theme.key_dialogTextGray));
        view.setLineSpacing(dp(3), 1f);
        if (title) view.setTypeface(AndroidUtilities.bold());
        content.addView(view, LayoutHelper.createLinear(-1, -2, 0, title ? 12 : 6, 0, 6));
        return view;
    }

    private void addAction(String label, Runnable action) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(color(Theme.key_dialogTextLink));
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setPadding(dp(8), dp(10), dp(8), dp(10));
        view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(6),
                color(Theme.key_dialogBackgroundGray), color(Theme.key_listSelector)));
        view.setFocusable(true);
        view.setOnClickListener(ignored -> {
            if (ownerActive()) action.run();
            else dismiss();
        });
        content.addView(view, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }
}
