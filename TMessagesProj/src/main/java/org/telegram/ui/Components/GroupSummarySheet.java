/*
 * Telegram for Android.
 * Licensed under the GNU General Public License, version 2 or later.
 * See LICENSE for details.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.CheckBox;
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
import org.telegram.messenger.ai.PromptOptions;
import org.telegram.messenger.ai.PromptPreferences;
import org.telegram.messenger.ai.SummaryHistoryLoader;
import org.telegram.messenger.ai.SummaryMessage;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Locale;
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
    private String promptNotice;
    private PromptOptions savedPrompt = PromptOptions.DEFAULT;
    private PromptPreferences.Scope savedPromptScope = PromptPreferences.Scope.BUILTIN;
    private PromptOptions sessionPrompt;
    private PromptOptions summaryPrompt;
    private AiSummarySettings.Config summaryConfig;
    private boolean summaryInferenceStarted;
    private long summaryStartedAt;
    private TextView progressStatus;
    private TextView progressElapsed;
    private TextView partialAnswer;
    private TextView partialLabel;
    private String streamingDraft;
    private Runnable progressTicker;
    private Runnable partialUpdate;
    private long lastPartialUpdateAt;
    private boolean promptLoaded;
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
        // The first page can be a preferences-loading view without an EditText. Later pages
        // still need the IME; AlertDialog otherwise derives this flag only when first shown.
        if (sheet.dialog.getWindow() != null) {
            sheet.dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            sheet.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
        sessionPrompt = null;
        summaryPrompt = null;
        summaryConfig = null;
        content.removeAllViews();
    }

    private void cancelWork() {
        operation++;
        stopProgressUpdates();
        streamingDraft = null;
        summaryInferenceStarted = false;
        if (historyLoader != null) {
            historyLoader.cancel();
            historyLoader = null;
        }
        if (client != null) {
            client.cancel();
            client = null;
        }
    }

    private void stopProgressUpdates() {
        if (progressTicker != null) {
            AndroidUtilities.cancelRunOnUIThread(progressTicker);
            progressTicker = null;
        }
        if (partialUpdate != null) {
            AndroidUtilities.cancelRunOnUIThread(partialUpdate);
            partialUpdate = null;
        }
        progressStatus = null;
        progressElapsed = null;
        partialAnswer = null;
        partialLabel = null;
    }

    private void startProgressTicker(int generation) {
        progressTicker = new Runnable() {
            @Override
            public void run() {
                if (!active(generation)) return;
                if (progressElapsed != null) {
                    progressElapsed.setText("已耗时 " + Math.max(0L,
                            (SystemClock.elapsedRealtime() - summaryStartedAt) / 1000) + " 秒");
                }
                AndroidUtilities.runOnUIThread(this, 1000);
            }
        };
        progressTicker.run();
    }

    private void updateSummaryProgress(AiSummaryClient.Progress progress) {
        if (progressStatus == null) return;
        switch (progress.stage) {
            case SOURCE:
                progressStatus.setText("正在总结分段：已完成 " + progress.completed + " / " + progress.total);
                break;
            case MERGE:
                progressStatus.setText("正在合并摘要（第 " + progress.mergeRound + " 轮）：已完成 "
                        + progress.completed + " / " + progress.total);
                break;
            case VALIDATING:
                progressStatus.setText("正在校验原文引用…");
                break;
        }
    }

    private void updatePartialAnswer(String text, int generation) {
        streamingDraft = text;
        if (partialUpdate != null) return;
        partialUpdate = () -> {
            partialUpdate = null;
            if (!active(generation) || partialAnswer == null) return;
            lastPartialUpdateAt = SystemClock.elapsedRealtime();
            partialLabel.setVisibility(View.VISIBLE);
            partialAnswer.setVisibility(View.VISIBLE);
            // A partial response has no spans, links or source navigation until final validation.
            partialAnswer.setText(streamingDraft);
        };
        AndroidUtilities.runOnUIThread(partialUpdate,
                Math.max(0L, 150 - (SystemClock.elapsedRealtime() - lastPartialUpdateAt)));
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
        if (!promptLoaded) {
            loadPromptPreferences();
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

        PromptOptions direction = effectivePrompt();
        addText("总结方向：" + PromptOptions.templateLabel(direction.templateId) + " · "
                + promptScopeLabel(sessionPrompt != null ? PromptPreferences.Scope.SESSION : savedPromptScope), true);
        if (!direction.customInstructions.isEmpty()) {
            addText("已设置补充要求（" + direction.customInstructions.codePointCount(0,
                    direction.customInstructions.length()) + " 字符）。", false);
        }
        if (promptNotice != null) {
            addText(promptNotice, false);
        }
        addAction("修改总结方向", () -> {
            recentCountText = count.getText().toString();
            showPromptEditor(effectivePrompt(), PromptPreferences.Scope.SESSION, null);
        });

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

    private PromptOptions effectivePrompt() {
        return sessionPrompt != null ? sessionPrompt : savedPrompt;
    }

    private String promptScopeLabel(PromptPreferences.Scope scope) {
        switch (scope) {
            case SESSION: return "本次面板";
            case CHAT: return topicId == 0 ? "本群偏好" : "当前话题偏好";
            case ACCOUNT: return "账号默认";
            default: return "内置默认";
        }
    }

    private void loadPromptPreferences() {
        cancelWork();
        final int generation = operation;
        clearContent();
        addText("正在读取总结方向…", true);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameAccountOwner()) {
                AndroidUtilities.runOnUIThread(this::dismiss);
                return;
            }
            try {
                PromptPreferences.Resolved resolved = PromptPreferences.load(account, ownerId, dialogId, topicId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (active(generation)) {
                        savedPrompt = resolved.options;
                        savedPromptScope = resolved.scope;
                        promptLoaded = true;
                        showSelection();
                    }
                });
            } catch (RuntimeException ignored) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    clearContent();
                    addText("无法读取已保存的总结方向，请重试。", true);
                    addAction("重试读取", this::loadPromptPreferences);
                    addAction("本次使用内置默认", () -> {
                        sessionPrompt = PromptOptions.DEFAULT;
                        promptLoaded = true;
                        showSelection();
                    });
                });
            }
        });
    }

    private void showPromptEditor(PromptOptions draft, PromptPreferences.Scope selectedScope, String error) {
        if (closed || !checkAccountOwner()) return;
        cancelWork();
        clearContent();
        addText("总结方向", true);
        addText("保持话题、结论、待办和原文引用。补充要求只改变关注点，不会排除所选范围中的消息。", false);
        RadioGroup templates = new RadioGroup(context);
        templates.setOrientation(RadioGroup.VERTICAL);
        int defaultTemplateId = 0;
        for (String template : new String[] {PromptOptions.GENERAL, PromptOptions.PROJECT,
                PromptOptions.DECISIONS, PromptOptions.TODOS}) {
            RadioButton option = radio(PromptOptions.templateLabel(template));
            option.setId(View.generateViewId());
            option.setTag(template);
            templates.addView(option, new RadioGroup.LayoutParams(-1, dp(44)));
            if (PromptOptions.GENERAL.equals(template)) defaultTemplateId = option.getId();
            if (draft.templateId.equals(template)) templates.check(option.getId());
        }
        content.addView(templates, LayoutHelper.createLinear(-1, -2, 0, 4, 0, 4));
        addText("补充要求（最多 " + PromptOptions.MAX_CUSTOM_CODE_POINTS + " 个 Unicode 字符）", true);
        EditTextBoldCursor custom = edit("例如：重点整理发布阻塞、已确认决定和仍有分歧的问题。",
                draft.customInstructions, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        custom.setSingleLine(false);
        custom.setMinLines(4);
        custom.setMaxLines(8);
        custom.setGravity(Gravity.TOP | Gravity.START);
        custom.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        TextView counter = addText("", false);
        Runnable updateCounter = () -> {
            String text = custom.getText().toString();
            int count = text.codePointCount(0, text.length());
            counter.setText(count + " / " + PromptOptions.MAX_CUSTOM_CODE_POINTS + " 字符");
            counter.setTextColor(color(count > PromptOptions.MAX_CUSTOM_CODE_POINTS
                    ? Theme.key_text_RedRegular : Theme.key_dialogTextGray));
        };
        custom.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateCounter.run(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        updateCounter.run();

        addText("应用范围", true);
        RadioGroup scopes = new RadioGroup(context);
        scopes.setOrientation(RadioGroup.VERTICAL);
        int sessionScopeId = 0;
        for (PromptPreferences.Scope scope : new PromptPreferences.Scope[] {PromptPreferences.Scope.SESSION,
                PromptPreferences.Scope.CHAT, PromptPreferences.Scope.ACCOUNT}) {
            String label = scope == PromptPreferences.Scope.SESSION ? "仅本次面板，不保存"
                    : scope == PromptPreferences.Scope.CHAT ? (topicId == 0 ? "保存为本群偏好" : "保存为当前话题偏好")
                    : "保存为当前账号默认";
            RadioButton option = radio(label);
            option.setId(View.generateViewId());
            option.setTag(scope);
            scopes.addView(option, new RadioGroup.LayoutParams(-1, dp(44)));
            if (scope == PromptPreferences.Scope.SESSION) sessionScopeId = option.getId();
            if (scope == selectedScope) scopes.check(option.getId());
        }
        content.addView(scopes, LayoutHelper.createLinear(-1, -2, 0, 4, 0, 4));
        addText("本次面板优先于群／话题偏好，群／话题偏好优先于账号默认。保存账号默认不会覆盖已保存的群偏好。", false);
        TextView validation = addText(error == null ? "" : error, false);
        validation.setTextColor(color(Theme.key_text_RedRegular));
        validation.setVisibility(error == null ? View.GONE : View.VISIBLE);
        addAction("应用总结方向", () -> {
            RadioButton template = templates.findViewById(templates.getCheckedRadioButtonId());
            RadioButton scope = scopes.findViewById(scopes.getCheckedRadioButtonId());
            try {
                PromptOptions options = new PromptOptions((String) template.getTag(), custom.getText().toString());
                savePromptOptions(options, (PromptPreferences.Scope) scope.getTag());
            } catch (IllegalArgumentException exception) {
                validation.setText(exception.getMessage());
                validation.setVisibility(View.VISIBLE);
                custom.requestFocus();
            }
        });
        final int resetTemplateId = defaultTemplateId;
        final int resetScopeId = sessionScopeId;
        addAction("恢复默认填写", () -> {
            templates.check(resetTemplateId);
            custom.setText("");
            scopes.check(resetScopeId);
            validation.setVisibility(View.GONE);
        });
        if (sessionPrompt != null) {
            addAction("清除本次覆盖", () -> {
                sessionPrompt = null;
                promptLoaded = false;
                promptNotice = "已取消本次覆盖，恢复使用已保存的偏好。";
                showSelection();
            });
        }
        if (savedPromptScope == PromptPreferences.Scope.CHAT) {
            addAction(topicId == 0 ? "清除本群偏好" : "清除当前话题偏好", () -> changePromptPreferences(
                    () -> PromptPreferences.clearChat(account, ownerId, dialogId, topicId),
                    "群／话题偏好已清除。", null,
                    () -> showPromptEditor(draft, selectedScope, "清除失败，请重试。")));
        }
        addAction("清除账号默认", () -> changePromptPreferences(
                () -> PromptPreferences.clearAccountDefault(account, ownerId), "账号默认已清除。", null,
                () -> showPromptEditor(draft, selectedScope, "清除失败，请重试。")));
        addAction("取消修改并返回", this::showSelection);
    }

    private void savePromptOptions(PromptOptions options, PromptPreferences.Scope scope) {
        if (closed || !checkAccountOwner()) return;
        if (scope == PromptPreferences.Scope.SESSION) {
            sessionPrompt = options;
            promptNotice = "总结方向仅用于本次面板，关闭后不保留。";
            showSelection();
            return;
        }
        changePromptPreferences(() -> PromptPreferences.save(account, ownerId, dialogId, topicId, scope, options),
                "总结方向已保存。", scope,
                () -> showPromptEditor(options, scope, "保存失败，请重试。"));
    }

    private void changePromptPreferences(Runnable change, String notice, PromptPreferences.Scope savingScope,
                                         Runnable onError) {
        if (closed || !checkAccountOwner()) return;
        cancelWork();
        final int generation = operation;
        clearContent();
        addText("正在更新总结方向…", true);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameAccountOwner()) {
                AndroidUtilities.runOnUIThread(this::dismiss);
                return;
            }
            try {
                change.run();
                PromptPreferences.Resolved resolved = PromptPreferences.load(account, ownerId, dialogId, topicId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    savedPrompt = resolved.options;
                    savedPromptScope = resolved.scope;
                    promptLoaded = true;
                    if (savingScope != null) sessionPrompt = null;
                    promptNotice = notice;
                    if (savingScope == PromptPreferences.Scope.ACCOUNT && resolved.scope == PromptPreferences.Scope.CHAT) {
                        promptNotice += " 本群／话题已有偏好，仍优先使用；清除该偏好后才使用账号默认。";
                    }
                    showSelection();
                });
            } catch (RuntimeException ignored) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (active(generation)) onError.run();
                });
            }
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
        CheckBox streaming = new CheckBox(context);
        streaming.setText("流式显示（需要服务支持）");
        streaming.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        streaming.setTextColor(color(Theme.key_dialogTextBlack));
        streaming.setChecked(config.stream);
        streaming.setMinHeight(dp(48));
        content.addView(streaming, LayoutHelper.createLinear(-1, -2, 0, 4, 0, 4));
        addText("流式模式逐步展示最终回答；连接测试仍使用普通请求，不代表已验证流式支持。", false);
        addText("API Key（按 MNN 服务设置填写）", true);
        EditTextBoldCursor key = edit("服务关闭鉴权时可留空", config.apiKey,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTransformationMethod(PasswordTransformationMethod.getInstance());
        key.setSaveEnabled(false);
        addText("API Key 在支持的设备上加密保存；加密保存不可用时仅本次运行有效。", false);

        TextView validation = addText("", false);
        validation.setTextColor(color(Theme.key_text_RedRegular));
        validation.setVisibility(View.GONE);
        addAction("测试连接", () -> {
            AiSummarySettings.Config snapshot = readSettingsInput(address, model, key, outputTokens, validation,
                    streaming.isChecked(), config.inputCharacterBudget);
            if (snapshot != null) {
                testConnection(snapshot);
            }
        });
        addAction("保存设置", () -> {
            AiSummarySettings.Config updated = readSettingsInput(address, model, key, outputTokens, validation,
                    streaming.isChecked(), config.inputCharacterBudget);
            if (updated != null) {
                saveSettings(updated);
            }
        });
        addAction("返回范围选择", this::showSelection);
    }

    private AiSummarySettings.Config readSettingsInput(EditTextBoldCursor address, EditTextBoldCursor model,
                                                       EditTextBoldCursor key, EditTextBoldCursor outputTokens,
                                                       TextView validation, boolean stream, int inputCharacterBudget) {
        validation.setVisibility(View.GONE);
        int tokens;
        try {
            tokens = Integer.parseInt(outputTokens.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            tokens = 0;
        }
        if (tokens < 64 || tokens > 8192) {
            outputTokens.setError("请输入 64–8192 之间的值");
            outputTokens.requestFocus();
            return null;
        }
        AiSummarySettings.Config config = new AiSummarySettings.Config(address.getText().toString().trim(),
                model.getText().toString().trim(), key.getText().toString().trim(), tokens, stream, inputCharacterBudget);
        String error = AiSummarySettings.validate(config);
        if (error != null) {
            validation.setText(error);
            validation.setVisibility(View.VISIBLE);
            return null;
        }
        return config;
    }

    private void testConnection(AiSummarySettings.Config config) {
        if (closed || !checkAccountOwner()) {
            return;
        }
        cancelWork();
        final int generation = operation;
        clearContent();
        addText("正在测试 MNN API 连接…", true);
        addText("使用当前填写的接口配置发送简短测试文本，不读取聊天消息，也不保存设置。", false);
        addText("请保持 Telegram 在前台，并确保 MNN Chat 已加载模型、开启 API 服务。", false);
        addAction("取消测试并返回设置", () -> returnToSettings(config));
        client = new AiSummaryClient();
        client.testConnection(config, new AiSummaryClient.DiagnosticCallback() {
            @Override
            public void onSuccess(AiSummaryClient.DiagnosticResult result) {
                if (active(generation)) {
                    client = null;
                    showConnectionResult(config, result, null);
                }
            }

            @Override
            public void onError(String error) {
                if (active(generation)) {
                    client = null;
                    showConnectionResult(config, null, error);
                }
            }
        });
    }

    private void showConnectionResult(AiSummarySettings.Config config,
                                      AiSummaryClient.DiagnosticResult result, String error) {
        clearContent();
        if (result != null) {
            addText("连接与文本生成测试通过", true);
            addText(String.format(Locale.US, "请求耗时：%.1f 秒", Math.max(0L, result.elapsedMs) / 1000.0), false);
            if (result.reportedModel == null || result.reportedModel.trim().isEmpty()) {
                addText("服务未返回模型标识，无法确认实际加载的模型。", false);
            } else {
                addText("服务返回的模型标识：" + result.reportedModel, false);
            }
        } else {
            addText("连接测试未通过", true);
            addText(error, false);
        }
        addText("测试不会保存设置；返回设置后可修改或保存当前配置。", false);
        addAction(result != null ? "再次测试" : "重试连接测试", () -> testConnection(config));
        addAction("返回设置", () -> returnToSettings(config));
    }

    private void returnToSettings(AiSummarySettings.Config config) {
        if (closed || !checkAccountOwner()) {
            return;
        }
        cancelWork();
        showSettings(config);
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
        final PromptOptions prompt = effectivePrompt();
        loadSettings(config -> startSummary(config, prompt));
    }

    private void startSummary(AiSummarySettings.Config config, PromptOptions prompt) {
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
        summaryPrompt = prompt;
        summaryConfig = config;
        summaryStartedAt = SystemClock.elapsedRealtime();
        lastPartialUpdateAt = 0;
        final int generation = operation;
        clearContent();
        progressStatus = addText("正在读取文字消息…", true);
        progressElapsed = addText("已耗时 0 秒", false);
        addText(today ? "读取今天 00:00 起的文字消息。" : "读取最近 " + recentCount + " 条文字消息。", false);
        addText("正在确定可读取范围，暂时没有完整消息总量。", false);
        addAction("取消并返回", this::showSelection);
        startProgressTicker(generation);

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
                progressStatus = addText("正在生成话题、结论与待办…", true);
                progressElapsed = addText("已耗时 " + Math.max(0L,
                        (SystemClock.elapsedRealtime() - summaryStartedAt) / 1000) + " 秒", false);
                addText("已读取 " + sourceMessages.size() + " 条文字消息。", false);
                addText(coverageNote, false);
                addText("手机上的本地模型可能需要一些时间。保持 MNN Chat API 服务运行。", false);
                addText("请保持 Telegram 在前台；关闭面板或离开页面会取消总结。", false);
                partialLabel = addText("生成中，尚未完成或校验", true);
                partialLabel.setVisibility(View.GONE);
                partialAnswer = addText("", false);
                partialAnswer.setTextColor(color(Theme.key_dialogTextBlack));
                partialAnswer.setLinksClickable(false);
                partialAnswer.setVisibility(View.GONE);
                addAction("取消并返回", GroupSummarySheet.this::showSelection);
                summaryInferenceStarted = true;
                client = new AiSummaryClient();
                client.summarize(config, sourceMessages, prompt, new AiSummaryClient.Callback() {
                    @Override
                    public void onProgress(AiSummaryClient.Progress progress) {
                        if (active(generation)) updateSummaryProgress(progress);
                    }

                    @Override
                    public void onPartial(String text) {
                        if (active(generation)) updatePartialAnswer(text, generation);
                    }

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
        final String unfinished = streamingDraft;
        final boolean allowPlainRetry = summaryInferenceStarted && summaryConfig != null && summaryConfig.stream;
        final AiSummarySettings.Config failedConfig = summaryConfig;
        final PromptOptions failedPrompt = summaryPrompt;
        cancelWork();
        clearContent();
        addText("暂时无法完成总结", true);
        addText(message, false);
        if (coverageNote != null) {
            addText(coverageNote, false);
        }
        if (unfinished != null && !unfinished.isEmpty()) {
            addText("以下内容未完成，尚未通过引用校验", true);
            TextView partial = addText(unfinished, false);
            partial.setLinksClickable(false);
        }
        addAction("重试", this::startSummary);
        if (allowPlainRetry) {
            addAction("改用普通模式重新运行", () -> startSummary(new AiSummarySettings.Config(
                    failedConfig.baseUrl, failedConfig.model, failedConfig.apiKey, failedConfig.maxOutputTokens,
                    false, failedConfig.inputCharacterBudget), failedPrompt));
            addText("仅本次改用普通模式，不修改已保存设置；重新运行会再次读取所选范围。", false);
        }
        addAction("MNN API 设置", this::showSettings);
        addAction("返回范围选择", this::showSelection);
    }

    private void showResult(String summary) {
        stopProgressUpdates();
        streamingDraft = null;
        summaryInferenceStarted = false;
        clearContent();
        addText("已完成 · 总耗时 " + Math.max(0L,
                (SystemClock.elapsedRealtime() - summaryStartedAt) / 1000) + " 秒", false);
        if (summaryPrompt != null) {
            addText("总结方向：" + PromptOptions.templateLabel(summaryPrompt.templateId), false);
        }
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
