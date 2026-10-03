/*
 * Telegram for Android.
 * Licensed under the GNU General Public License, version 2 or later.
 * See LICENSE for details.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.AiSummaryClient;
import org.telegram.messenger.ai.AiSummarySettings;
import org.telegram.messenger.ai.SummaryHistoryLoader;
import org.telegram.messenger.ai.SummaryMessage;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An account-scoped, explicit user action. Summaries are kept only in this dialog. */
public final class GroupSummarySheet {

    public interface SourceNavigator {
        void open(long dialogId, int messageId);
    }

    private static final Pattern REFERENCE = Pattern.compile("\\[m([0-9]+)\\]");
    private static final Pattern HEADING = Pattern.compile("(?m)^(?:#{1,6}\\s*)?(?:\\*\\*)?【?(话题|结论|待办)】?(?:\\*\\*)?[：:]?\\s*$");

    private final BaseFragment fragment;
    private final int account;
    private final long ownerId;
    private final long dialogId;
    private final long topicId;
    private final SourceNavigator navigator;
    private final Context context;
    private final Theme.ResourcesProvider resourcesProvider;
    private final LinearLayout content;
    private final AlertDialog dialog;

    private SummaryHistoryLoader historyLoader;
    private AiSummaryClient client;
    private ArrayList<SummaryMessage> sourceMessages;
    private String coverageNote;
    private String settingsNotice;
    private String recentCountText = "100";
    private int recentCount = 100;
    private boolean today;
    private boolean closed;
    private int operation;

    /** The caller should also dismiss the returned handle when its fragment is destroyed. */
    public static GroupSummarySheet show(BaseFragment fragment, int account, long dialogId,
                                         long topicId, SourceNavigator navigator) {
        if (fragment == null || fragment.isFinished || fragment.getParentActivity() == null) {
            return null;
        }
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || UserConfig.getInstance(account).getClientUserId() == 0) {
            return null;
        }
        GroupSummarySheet sheet = new GroupSummarySheet(fragment, account, dialogId, topicId, navigator);
        sheet.showSelection();
        if (sheet.closed) {
            return null;
        }
        if (fragment.showDialog(sheet.dialog, false, ignored -> sheet.onDismissed()) == null) {
            sheet.onDismissed();
            return null;
        }
        return sheet;
    }

    private GroupSummarySheet(BaseFragment fragment, int account, long dialogId, long topicId,
                              SourceNavigator navigator) {
        this.fragment = fragment;
        this.account = account;
        ownerId = UserConfig.getInstance(account).getClientUserId();
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.navigator = navigator;
        context = fragment.getParentActivity();
        resourcesProvider = fragment.getResourceProvider();
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        content.setPadding(dp(24), 0, dp(24), dp(8));
        // Telegram's AlertDialog already puts its custom view inside a scroll container.
        dialog = new AlertDialog.Builder(context, resourcesProvider)
                .setTitle(topicId == 0 ? "AI 群聊总结" : "AI 话题总结")
                .setView(content)
                .setNegativeButton("关闭", (ignored, which) -> dismiss())
                .create();
    }

    public void dismiss() {
        onDismissed();
        dialog.dismiss();
    }

    private void onDismissed() {
        if (closed) {
            return;
        }
        closed = true;
        AndroidUtilities.hideKeyboard(content);
        cancelWork();
        sourceMessages = null;
        coverageNote = null;
        content.removeAllViews();
    }

    private void cancelWork() {
        operation++;
        if (historyLoader != null) {
            historyLoader.cancel();
            historyLoader = null;
        }
        if (client != null) {
            client.cancel();
            client = null;
        }
    }

    private boolean active(int expectedOperation) {
        if (closed || expectedOperation != operation) {
            return false;
        }
        if (!sameAccountOwner() || fragment.isFinished || fragment.getParentActivity() == null || !dialog.isShowing()) {
            dismiss();
            return false;
        }
        return true;
    }

    private boolean sameAccountOwner() {
        return ownerId != 0 && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    private boolean checkAccountOwner() {
        if (!sameAccountOwner()) {
            dismiss();
            return false;
        }
        return true;
    }

    private void clearContent() {
        AndroidUtilities.hideKeyboard(content);
        content.removeAllViews();
        content.requestFocus();
        content.post(() -> {
            ViewParent parent = content.getParent();
            while (parent instanceof View) {
                if (parent instanceof ScrollView) {
                    ((ScrollView) parent).scrollTo(0, 0);
                    break;
                }
                parent = parent.getParent();
            }
        });
    }

    private void showSelection() {
        if (closed || !checkAccountOwner()) {
            return;
        }
        cancelWork();
        clearContent();
        if (MessagesController.getInstance(account).isPeerNoForwards(dialogId)) {
            addText("此聊天已限制内容保存，无法使用 AI 总结。", false);
            return;
        }
        addText(topicId == 0 ? "选择本群的文字消息范围" : "仅总结当前话题的文字消息", true);
        if (settingsNotice != null) {
            addText(settingsNotice, false);
        }

        RadioGroup choices = new RadioGroup(context);
        choices.setOrientation(RadioGroup.VERTICAL);
        RadioButton recent = radio("最近 N 条文字消息");
        recent.setId(View.generateViewId());
        RadioButton day = radio("当日文字消息");
        day.setId(View.generateViewId());
        choices.addView(recent, new RadioGroup.LayoutParams(-1, dp(46)));
        choices.addView(day, new RadioGroup.LayoutParams(-1, dp(46)));
        choices.check(today ? day.getId() : recent.getId());
        content.addView(choices, LayoutHelper.createLinear(-1, -2, 0, 4, 0, 4));

        EditTextBoldCursor count = edit("条数（1–" + SummaryHistoryLoader.MAX_RECENT_COUNT + "）", recentCountText,
                InputType.TYPE_CLASS_NUMBER);
        count.setEnabled(!today);
        count.setAlpha(today ? 0.5f : 1f);
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            today = checkedId == day.getId();
            count.setEnabled(!today);
            count.setAlpha(today ? 0.5f : 1f);
        });
        addText("当日按手机时区从 00:00 起计算；读取范围截至开始总结时。只处理文字，不读取图片、语音或文件内容。", false);
        addText("选中的文字将发送到你设置的 MNN Chat API 服务。结果仅在这里显示，点击引用可返回原消息。", false);

        addAction("MNN API 设置", () -> {
            recentCountText = count.getText().toString();
            showSettings();
        });
        addAction("开始总结", () -> {
            recentCountText = count.getText().toString().trim();
            if (!today) {
                try {
                    recentCount = Integer.parseInt(recentCountText);
                } catch (NumberFormatException ignored) {
                    recentCount = 0;
                }
                if (recentCount < 1 || recentCount > SummaryHistoryLoader.MAX_RECENT_COUNT) {
                    count.setError("请输入 1–" + SummaryHistoryLoader.MAX_RECENT_COUNT + " 之间的条数");
                    count.requestFocus();
                    return;
                }
            }
            startSummary();
        });
    }

    private void showSettings() {
        loadSettings(this::showSettings);
    }

    private interface ConfigCallback {
        void onLoaded(AiSummarySettings.Config config);
    }

    private void loadSettings(ConfigCallback callback) {
        if (closed || !checkAccountOwner()) {
            return;
        }
        cancelWork();
        final int generation = operation;
        clearContent();
        addText("正在读取 MNN API 设置…", true);
        addAction("返回范围选择", this::showSelection);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameAccountOwner()) {
                AndroidUtilities.runOnUIThread(this::dismiss);
                return;
            }
            try {
                AiSummarySettings.Config config = AiSummarySettings.load(account, ownerId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (active(generation)) {
                        callback.onLoaded(config);
                    }
                });
            } catch (RuntimeException ignored) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (active(generation)) {
                        showError("无法读取 MNN API 设置，请重新打开面板后重试。");
                    }
                });
            }
        });
    }

    private void showSettings(AiSummarySettings.Config config) {
        clearContent();
        addText("MNN Chat API", true);
        addText("先在 MNN Chat 加载模型并开启 API 服务，再复制服务地址与 API Key。同一手机默认地址为 http://127.0.0.1:8080/v1。", false);

        addText("服务地址", true);
        EditTextBoldCursor address = edit("http://127.0.0.1:8080/v1", config.baseUrl,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addText("模型名称（可留空）", true);
        EditTextBoldCursor model = edit("留空使用 MNN 当前已加载的模型", config.model,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addText("填写模型名不会切换 MNN 中已加载的模型。", false);
        addText("最大输出 token（64–8192）", true);
        EditTextBoldCursor outputTokens = edit("512", Integer.toString(config.maxOutputTokens),
                InputType.TYPE_CLASS_NUMBER);
        addText("服务端支持时生效；较大的值会增加手机推理耗时。", false);
        addText("API Key（按 MNN 服务设置填写）", true);
        EditTextBoldCursor key = edit("服务关闭鉴权时可留空", config.apiKey,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTransformationMethod(PasswordTransformationMethod.getInstance());
        key.setSaveEnabled(false);
        addText("API Key 在支持的设备上加密保存；加密保存不可用时仅本次运行有效。", false);

        TextView validation = addText("", false);
        validation.setTextColor(color(Theme.key_text_RedRegular));
        validation.setVisibility(View.GONE);
        addAction("保存设置", () -> {
            int tokens;
            try {
                tokens = Integer.parseInt(outputTokens.getText().toString().trim());
            } catch (NumberFormatException ignored) {
                tokens = 0;
            }
            if (tokens < 64 || tokens > 8192) {
                outputTokens.setError("请输入 64–8192 之间的值");
                outputTokens.requestFocus();
                return;
            }
            AiSummarySettings.Config updated = new AiSummarySettings.Config(
                    address.getText().toString().trim(),
                    model.getText().toString().trim(), key.getText().toString().trim(), tokens);
            String error = AiSummarySettings.validate(updated);
            if (error != null) {
                validation.setText(error);
                validation.setVisibility(View.VISIBLE);
                return;
            }
            saveSettings(updated);
        });
        addAction("返回范围选择", this::showSelection);
    }

    private void saveSettings(AiSummarySettings.Config config) {
        if (closed || !checkAccountOwner()) {
            return;
        }
        cancelWork();
        final int generation = operation;
        clearContent();
        addText("正在保存 MNN API 设置…", true);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameAccountOwner()) {
                AndroidUtilities.runOnUIThread(this::dismiss);
                return;
            }
            try {
                boolean persistent = AiSummarySettings.save(account, ownerId, config);
                AndroidUtilities.runOnUIThread(() -> {
                    if (active(generation)) {
                        settingsNotice = persistent || config.apiKey.isEmpty() ? "设置已保存。"
                                : "地址、模型与输出长度已保存。此设备暂时无法加密保存 API Key，重启应用后需重新填写密钥。";
                        showSelection();
                    }
                });
            } catch (RuntimeException ignored) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (active(generation)) {
                        showSettings(config);
                        addText("无法保存设置，请重试。", false);
                    }
                });
            }
        });
    }

    private void startSummary() {
        loadSettings(this::startSummary);
    }

    private void startSummary(AiSummarySettings.Config config) {
        if (closed || fragment.isFinished || !checkAccountOwner()) {
            return;
        }
        if (MessagesController.getInstance(account).isPeerNoForwards(dialogId)) {
            showSelection();
            return;
        }
        String settingsError = AiSummarySettings.validate(config);
        if (settingsError != null) {
            showError(settingsError);
            return;
        }
        cancelWork();
        sourceMessages = null;
        coverageNote = null;
        final int generation = operation;
        clearContent();
        addText("正在读取文字消息…", true);
        addText(today ? "读取今天 00:00 起的文字消息。" : "读取最近 " + recentCount + " 条文字消息。", false);
        addAction("取消并返回", this::showSelection);

        historyLoader = new SummaryHistoryLoader(account, dialogId, topicId);
        SummaryHistoryLoader.Callback callback = new SummaryHistoryLoader.Callback() {
            @Override
            public void onLoaded(SummaryHistoryLoader.Result result) {
                if (!active(generation)) {
                    return;
                }
                historyLoader = null;
                sourceMessages = new ArrayList<>(result.messages);
                coverageNote = result.coverageNote;
                if (sourceMessages.isEmpty()) {
                    showError("所选范围内没有可总结的文字消息。");
                    return;
                }
                if (MessagesController.getInstance(account).isPeerNoForwards(dialogId)) {
                    showError("此群已启用内容保护，无法用于 AI 总结。");
                    return;
                }
                clearContent();
                addText("正在生成话题、结论与待办…", true);
                addText(coverageNote, false);
                addText("手机上的本地模型可能需要一些时间。保持 MNN Chat API 服务运行。", false);
                addText("请保持 Telegram 在前台；关闭面板或离开页面会取消总结。", false);
                addAction("取消并返回", GroupSummarySheet.this::showSelection);
                client = new AiSummaryClient();
                client.summarize(config, sourceMessages, new AiSummaryClient.Callback() {
                    @Override
                    public void onSuccess(String summary) {
                        if (active(generation)) {
                            client = null;
                            showResult(summary);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (active(generation)) {
                            showError(error);
                        }
                    }
                });
            }

            @Override
            public void onError(String error) {
                if (active(generation)) {
                    showError(error);
                }
            }
        };
        if (today) {
            historyLoader.loadToday(callback);
        } else {
            historyLoader.loadRecent(recentCount, callback);
        }
    }

    private void showError(String message) {
        cancelWork();
        clearContent();
        addText("暂时无法完成总结", true);
        addText(message, false);
        if (coverageNote != null) {
            addText(coverageNote, false);
        }
        addAction("重试", this::startSummary);
        addAction("MNN API 设置", this::showSettings);
        addAction("返回范围选择", this::showSelection);
    }

    private void showResult(String summary) {
        clearContent();
        addText(coverageNote, false);
        addText("点击 [m数字] 查看原消息；请结合原文核对模型生成的结论。", false);
        TextView result = addText(linkSources(summary), false);
        result.setTextColor(color(Theme.key_dialogTextBlack));
        result.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        result.setMovementMethod(LinkMovementMethod.getInstance());
        result.setLinksClickable(true);
        addAction("重新选择范围", this::showSelection);
    }

    private CharSequence linkSources(String summary) {
        SpannableStringBuilder text = new SpannableStringBuilder(summary);
        Matcher headings = HEADING.matcher(summary);
        while (headings.find()) {
            text.setSpan(new StyleSpan(Typeface.BOLD), headings.start(), headings.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher references = REFERENCE.matcher(summary);
        while (references.find()) {
            int index;
            try {
                index = Integer.parseInt(references.group(1)) - 1;
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (sourceMessages == null || index < 0 || index >= sourceMessages.size()) {
                continue;
            }
            SummaryMessage source = sourceMessages.get(index);
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    if (!active(operation) || navigator == null) {
                        return;
                    }
                    dismiss();
                    navigator.open(source.dialogId, source.id);
                }

                @Override
                public void updateDrawState(TextPaint paint) {
                    paint.setColor(color(Theme.key_dialogTextLink));
                    paint.setUnderlineText(true);
                }
            }, references.start(), references.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private RadioButton radio(String label) {
        RadioButton button = new RadioButton(context);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTextColor(color(Theme.key_dialogTextBlack));
        return button;
    }

    private EditTextBoldCursor edit(String hint, String value, int inputType) {
        EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setTextColor(color(Theme.key_dialogTextBlack));
        edit.setHintTextColor(color(Theme.key_dialogTextGray));
        edit.setCursorColor(color(Theme.key_dialogTextLink));
        edit.setCursorWidth(1.5f);
        edit.setInputType(inputType);
        edit.setSingleLine(true);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        edit.setPadding(dp(2), dp(10), dp(2), dp(10));
        edit.setHint(hint);
        edit.setText(value);
        content.addView(edit, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 8));
        return edit;
    }

    private TextView addText(CharSequence text, boolean title) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, title ? 16 : 14);
        view.setTextColor(color(title ? Theme.key_dialogTextBlack : Theme.key_dialogTextGray));
        view.setLineSpacing(dp(3), 1f);
        if (title) {
            view.setTypeface(AndroidUtilities.bold());
        }
        content.addView(view, LayoutHelper.createLinear(-1, -2, 0, title ? 12 : 6, 0, 6));
        return view;
    }

    private void addAction(String label, Runnable action) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(color(Theme.key_dialogTextLink));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), dp(10), dp(8), dp(10));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(6),
                color(Theme.key_dialogBackgroundGray), color(Theme.key_listSelector)));
        button.setFocusable(true);
        button.setOnClickListener(view -> {
            if (!closed) {
                action.run();
            }
        });
        content.addView(button, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }
}
