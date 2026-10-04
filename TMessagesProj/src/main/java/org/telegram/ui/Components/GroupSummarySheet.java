/*
 * Telegram for Android.
 * Licensed under the GNU General Public License, version 2 or later.
 * See LICENSE for details.
 */

package org.telegram.ui.Components;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
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
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.AiSummaryClient;
import org.telegram.messenger.ai.AiSummarySettings;
import org.telegram.messenger.ai.AiSummaryPrompt;
import org.telegram.messenger.ai.PromptOptions;
import org.telegram.messenger.ai.PromptPreferences;
import org.telegram.messenger.ai.SummaryHistoryLoader;
import org.telegram.messenger.ai.SummaryHistoryStore;
import org.telegram.messenger.ai.SummaryMessage;
import org.telegram.messenger.ai.SummarySourceReference;
import org.telegram.messenger.ai.SummaryStateStore;
import org.telegram.messenger.ai.SummarySourceVerifier;
import org.telegram.messenger.ai.SummaryResultCache;
import org.telegram.messenger.ai.SummaryFilter;
import org.telegram.messenger.ai.SummaryChatExport;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.SummaryHistoryActivity;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Date;
import java.util.HashMap;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/** An account-scoped, explicit user action. Source snapshots stay in this dialog. */
public final class GroupSummarySheet {

    public interface SourceNavigator {
        void open(long dialogId, int messageId);
    }

    private enum RangeMode { RECENT, TODAY, SINCE, UNREAD, REPLAY }

    private static final class RangeRequest {
        final RangeMode mode;
        final int count;
        final int expectedCursor;
        final int lower;
        final int upper;
        final boolean initialize;
        final SummaryHistoryLoader.Result replayHistory;
        final ArrayList<SummaryMessage> replayMessages;
        final SummaryFilter.Options filters;

        RangeRequest(RangeMode mode, int count, int expectedCursor, int lower, int upper,
                     boolean initialize, SummaryHistoryLoader.Result replayHistory,
                     ArrayList<SummaryMessage> replayMessages) {
            this(mode, count, expectedCursor, lower, upper, initialize, replayHistory, replayMessages, null);
        }

        RangeRequest(RangeMode mode, int count, int expectedCursor, int lower, int upper,
                     boolean initialize, SummaryHistoryLoader.Result replayHistory,
                     ArrayList<SummaryMessage> replayMessages, SummaryFilter.Options filters) {
            this.mode = mode;
            this.count = count;
            this.expectedCursor = expectedCursor;
            this.lower = lower;
            this.upper = upper;
            this.initialize = initialize;
            this.replayHistory = replayHistory;
            this.replayMessages = replayMessages == null ? null : new ArrayList<>(replayMessages);
            this.filters = filters;
        }
    }

    private static final class SourceUpdate {
        final String text;
        final int editDate;
        final boolean usable;

        SourceUpdate(String text, int editDate, boolean usable) {
            this.text = text;
            this.editDate = editDate;
            this.usable = usable;
        }

        boolean changed(SummaryMessage source) {
            return !usable || source.editDate != editDate || !source.text.equals(text);
        }
    }

    private static final class CachedResult {
        final SummaryResultCache.Key key;
        final ArrayList<SummaryMessage> sources;
        final SummaryHistoryLoader.Result history;
        final RangeRequest range;
        final AiSummarySettings.Config config;
        final PromptOptions prompt;
        final String coverage;
        final long generatedAt;
        final long elapsedMs;

        CachedResult(SummaryResultCache.Key key, ArrayList<SummaryMessage> sources,
                     SummaryHistoryLoader.Result history, RangeRequest range, AiSummarySettings.Config config,
                     PromptOptions prompt, String coverage, long generatedAt, long elapsedMs) {
            this.key = key;
            this.sources = new ArrayList<>(sources);
            this.history = history;
            this.range = range;
            this.config = config;
            this.prompt = prompt;
            this.coverage = coverage;
            this.generatedAt = generatedAt;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class RequestInputEntry {
        final String stage;
        final AiSummaryClient.RequestInput input;

        RequestInputEntry(String stage, AiSummaryClient.RequestInput input) {
            this.stage = stage;
            this.input = input;
        }
    }

    private static final class ExportTask {
        final RangeRequest range;
        final SummaryChatExport.Format format;
        final PromptOptions prompt;
        final String timeZoneId;
        volatile boolean cancelled;
        SummaryHistoryLoader.Result history;
        ArrayList<SummaryMessage> rawSources;
        ArrayList<SummaryMessage> sources;
        String coverage;
        int missingReplies;
        SummaryExportFileHelper.PreparedFile file;

        ExportTask(RangeRequest range, SummaryChatExport.Format format, PromptOptions prompt) {
            this.range = range;
            this.format = format;
            this.prompt = prompt;
            timeZoneId = TimeZone.getDefault().getID();
        }
    }

    // Activity results are dispatched to the top fragment in each pane. Never reuse a code
    // within this process, so a late result cannot attach to another panel or export task.
    private static final AtomicInteger NEXT_EXPORT_DOCUMENT_REQUEST = new AtomicInteger(0x5000);

    private static final Pattern REFERENCE = Pattern.compile("\\[m([0-9]+)\\]");
    private static final Pattern HEADING = Pattern.compile("(?m)^(?:#{1,6}\\s*)?(?:\\*\\*)?【?(话题|结论|待办)】?(?:\\*\\*)?[：:]?\\s*$");

    private final BaseFragment fragment;
    private final int account;
    private final long ownerId;
    private final long dialogId;
    private final long topicId;
    private final int unreadLower;
    private final int unreadUpper;
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
    private TextView requestStatus;
    private AiSummaryClient.RequestStatus latestRequestStatus;
    private AiSummaryClient.Progress latestSummaryProgress;
    private String streamingRequestStage;
    private int streamingRequestGeneration;
    private final ArrayList<RequestInputEntry> requestInputs = new ArrayList<>();
    private TextView requestInputsButton;
    private AlertDialog requestInputsDialog;
    private LinearLayout requestInputsContent;
    private int requestInputsViewerGeneration;
    private boolean requestInputsListVisible;
    private long modelRequestStartedAt;
    private TextView partialAnswer;
    private TextView partialLabel;
    private String streamingDraft;
    private Runnable progressTicker;
    private Runnable partialUpdate;
    private long lastPartialUpdateAt;
    private boolean promptLoaded;
    private String recentCountText = "100";
    private int recentCount = 100;
    private RangeMode rangeMode = RangeMode.RECENT;
    private SummaryStateStore.State summaryState;
    private SummaryStateStore.CompletionToken completionToken;
    private RangeRequest summaryRange;
    private SummaryHistoryLoader.Result summaryHistory;
    private SummaryHistoryLoader.Result lastSuccessfulHistory;
    private ArrayList<SummaryMessage> lastSuccessfulSources;
    private boolean resultCommitted;
    private String completionNotice;
    private final HashMap<Integer, SourceUpdate> pendingSourceUpdates = new HashMap<>();
    private boolean sourceSnapshotInvalid;
    private SummarySourceVerifier sourceVerifier;
    private AlertDialog sourceDialog;
    private LinearLayout sourceContent;
    private int sourceOperation;
    private CachedResult cachedResult;
    private boolean viewingCachedResult;
    private SummaryHistoryStore.Record historyRecord;
    private String historySaveNotice;
    private boolean historySavePending;
    private boolean historySaveFailed;
    private TextView historySaveStatus;
    private TextView historySaveRetry;
    private SummaryFilter.Options filterOptions = SummaryFilter.Options.DEFAULT;
    private boolean summaryFiltered;
    private SummaryQuestionSheet questionSheet;
    private ExportTask exportTask;
    private boolean exportDocumentPending;
    private int exportDocumentRequest = -1;
    private ExportTask exportDocumentTask;
    private int exportDocumentGeneration;
    private boolean closed;
    private int operation;

    /** The caller should also dismiss the returned handle when its fragment is destroyed. */
    public static GroupSummarySheet show(BaseFragment fragment, int account, long dialogId,
                                         long topicId, SourceNavigator navigator) {
        return show(fragment, account, dialogId, topicId, -1, -1, navigator);
    }

    public static GroupSummarySheet show(BaseFragment fragment, int account, long dialogId,
                                         long topicId, int unreadLower, int unreadUpper, SourceNavigator navigator) {
        if (fragment == null || fragment.isFinished || fragment.getParentActivity() == null) {
            return null;
        }
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || UserConfig.getInstance(account).getClientUserId() == 0) {
            return null;
        }
        GroupSummarySheet sheet = new GroupSummarySheet(fragment, account, dialogId, topicId,
                unreadLower, unreadUpper, navigator);
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
                              int unreadLower, int unreadUpper,
                              SourceNavigator navigator) {
        this.fragment = fragment;
        this.account = account;
        ownerId = UserConfig.getInstance(account).getClientUserId();
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.unreadLower = unreadLower;
        this.unreadUpper = unreadUpper;
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
        lastSuccessfulSources = null;
        lastSuccessfulHistory = null;
        summaryHistory = null;
        cachedResult = null;
        historyRecord = null;
        historySaveStatus = null;
        historySaveRetry = null;
        content.removeAllViews();
    }

    private void cancelWork() {
        cancelWork(false);
    }

    private void cancelWork(boolean keepRequestInputs) {
        operation++;
        closeRequestInputs();
        requestInputsButton = null;
        if (!keepRequestInputs) requestInputs.clear();
        exportDocumentPending = false;
        exportDocumentTask = null;
        if (exportTask != null) {
            exportTask.cancelled = true;
            if (exportTask.file != null) exportTask.file.release();
            exportTask = null;
        }
        closeQuestions();
        closeSourcePreview();
        pendingSourceUpdates.clear();
        stopProgressUpdates();
        streamingDraft = null;
        summaryInferenceStarted = false;
        if (completionToken != null) {
            completionToken.cancel();
            completionToken = null;
            // A commit may have won before its UI callback. Always reload authoritative state
            // when leaving a task so the next incremental batch cannot use a stale cursor.
            summaryState = null;
        }
        if (historyLoader != null) {
            historyLoader.cancel();
            historyLoader = null;
        }
        if (client != null) {
            client.cancel();
            client = null;
        }
    }

    private void closeQuestions() {
        SummaryQuestionSheet previous = questionSheet;
        questionSheet = null;
        if (previous != null) previous.dismiss();
    }

    private void closeRequestInputs() {
        AlertDialog previous = requestInputsDialog;
        requestInputsDialog = null;
        requestInputsListVisible = false;
        if (requestInputsContent != null) requestInputsContent.removeAllViews();
        requestInputsContent = null;
        if (previous != null) previous.dismiss();
    }

    private void recordRequestInput(AiSummaryClient.RequestInput input) {
        if (input == null) return;
        RequestInputEntry entry = new RequestInputEntry(streamingRequestStage == null
                ? currentRequestStage() : streamingRequestStage, input);
        requestInputs.add(entry);
        updateRequestInputsButton();
        if (requestInputsDialog != null && requestInputsListVisible && requestInputsViewerActive()) {
            addRequestInputRow(requestInputs.size() - 1);
        }
    }

    private void addRequestInputsAction() {
        requestInputsButton = addAction("", this::showRequestInputs);
        updateRequestInputsButton();
    }

    private void updateRequestInputsButton() {
        if (requestInputsButton == null) return;
        requestInputsButton.setText("查看模型输入（" + requestInputs.size() + " 次请求）");
        requestInputsButton.setEnabled(!requestInputs.isEmpty());
        requestInputsButton.setAlpha(requestInputs.isEmpty() ? 0.5f : 1f);
    }

    private boolean requestInputsViewerActive() {
        return requestInputsDialog != null && requestInputsDialog.isShowing()
                && active(requestInputsViewerGeneration);
    }

    private void showRequestInputs() {
        if (!active(operation) || requestInputs.isEmpty()) return;
        if (isSummaryAccessRevoked()) { onAccessRevoked(); return; }
        closeRequestInputs();
        requestInputsViewerGeneration = operation;
        requestInputsContent = new LinearLayout(context);
        requestInputsContent.setOrientation(LinearLayout.VERTICAL);
        requestInputsContent.setPadding(dp(24), 0, dp(24), dp(8));
        AlertDialog viewer = new AlertDialog.Builder(context, resourcesProvider)
                .setTitle("查看请求输入")
                .setView(requestInputsContent)
                .setNegativeButton("关闭", (ignored, which) -> closeRequestInputs())
                .create();
        requestInputsDialog = viewer;
        viewer.setOnDismissListener(ignored -> {
            if (requestInputsDialog == viewer) closeRequestInputs();
        });
        renderRequestInputList();
        try {
            // Keep the active summary dialog and network request alive underneath this viewer.
            viewer.show();
        } catch (RuntimeException unavailableWindow) {
            closeRequestInputs();
        }
    }

    private void renderRequestInputList() {
        if (requestInputsContent == null) return;
        requestInputsListVisible = true;
        requestInputsContent.removeAllViews();
        addText(requestInputsContent, "仅保留本次任务的请求输入。重新总结、重试或关闭面板后清除，不写入总结历史。", false);
        addText(requestInputsContent, "以下是请求 messages 中的 system 和 user 原文；字符数不是 token 数，输出预留不属于输入文本。", false);
        for (int i = 0; i < requestInputs.size(); i++) addRequestInputRow(i);
        scrollRequestInputsToTop();
    }

    private void addRequestInputRow(int index) {
        RequestInputEntry entry = requestInputs.get(index);
        addAction(requestInputsContent, "第 " + (index + 1) + " 次 · " + entry.stage + " · "
                + entry.input.inputCharacters + " 字符", () -> showRequestInput(index));
    }

    private void showRequestInput(int index) {
        if (!requestInputsViewerActive() || index < 0 || index >= requestInputs.size()) return;
        RequestInputEntry entry = requestInputs.get(index);
        requestInputsListVisible = false;
        requestInputsContent.removeAllViews();
        addAction(requestInputsContent, "返回请求列表", () -> {
            if (requestInputsViewerActive()) renderRequestInputList();
        });
        addText(requestInputsContent, "第 " + (index + 1) + " 次请求 · " + entry.stage, true);
        addText(requestInputsContent, "请求原文完整显示，长按可选择复制。", false);
        addText(requestInputsContent, "system · 系统规则（" + entry.input.systemText.length() + " 字符）", true);
        addRequestInputText(entry.input.systemText);
        addText(requestInputsContent, "user · 方向及消息／合并内容（" + entry.input.userText.length() + " 字符）", true);
        addRequestInputText(entry.input.userText);
        addAction(requestInputsContent, "返回请求列表", () -> {
            if (requestInputsViewerActive()) renderRequestInputList();
        });
        scrollRequestInputsToTop();
    }

    private void scrollRequestInputsToTop() {
        final LinearLayout body = requestInputsContent;
        if (body == null) return;
        body.post(() -> {
            if (requestInputsContent != body || !requestInputsViewerActive()) return;
            ViewParent parent = body.getParent();
            while (parent instanceof View) {
                if (parent instanceof ScrollView) {
                    ((ScrollView) parent).scrollTo(0, 0);
                    break;
                }
                parent = parent.getParent();
            }
        });
    }

    private void addRequestInputText(String text) {
        TextView view = addText(requestInputsContent, text, false);
        view.setTextColor(color(Theme.key_dialogTextBlack));
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTextIsSelectable(true);
        view.setLinksClickable(false);
        view.setSaveEnabled(false);
    }

    private boolean isSummaryAccessRevoked() {
        return dialogId >= 0 || ChatObject.isKickedFromChat(
                MessagesController.getInstance(account).getChat(-dialogId));
    }

    private void showQuestions() {
        if (!active(operation) || sourceSnapshotInvalid || summaryHistory == null
                || !summaryHistory.complete || sourceMessages == null || sourceMessages.isEmpty()
                || summaryPrompt == null || summaryConfig == null) return;
        if (isSummaryAccessRevoked()) {
            onAccessRevoked();
            return;
        }
        if (questionSheet != null && questionSheet.isShowing()) return;
        closeSourcePreview();
        final int generation = operation;
        questionSheet = SummaryQuestionSheet.show(fragment, account, ownerId, summaryConfig,
                new ArrayList<>(sourceMessages), summaryPrompt, (source, reference) -> {
                    if (active(generation) && !sourceSnapshotInvalid) showSourcePreview(source, reference);
                });
    }

    public void onSourceDeleted(int messageId) {
        if (closed) return;
        if (!checkAccountOwner()) return;
        SourceUpdate update = new SourceUpdate(null, 0, false);
        if (exportTask != null && sourceChanged(exportTask.rawSources, messageId, update)) {
            invalidateExportSources("导出范围内的原消息已删除，请重新读取后导出。");
            return;
        }
        invalidateCachedSource(messageId, update);
        if (historyLoader != null) pendingSourceUpdates.put(messageId, update);
        if (sourceChanged(sourceMessages, messageId, update)
                || sourceChanged(lastSuccessfulSources, messageId, update)
                || historySourceChanged(summaryHistory, messageId, update)
                || historySourceChanged(lastSuccessfulHistory, messageId, update)
                || summaryRange != null && sourceChanged(summaryRange.replayMessages, messageId, update)) {
            invalidateSources("原消息已删除，当前摘要及原文快照已过期。请重新读取消息后总结。", false);
        }
    }

    public void onSourceUpdated(int messageId, String text, int editDate, boolean usable) {
        if (closed) return;
        if (!checkAccountOwner()) return;
        SourceUpdate update = new SourceUpdate(text, editDate, usable);
        if (exportTask != null && sourceChanged(exportTask.rawSources, messageId, update)) {
            invalidateExportSources("导出范围内的原消息已修改或不可用，请重新读取后导出。");
            return;
        }
        invalidateCachedSource(messageId, update);
        if (historyLoader != null) pendingSourceUpdates.put(messageId, update);
        if (sourceChanged(sourceMessages, messageId, update)
                || sourceChanged(lastSuccessfulSources, messageId, update)
                || historySourceChanged(summaryHistory, messageId, update)
                || historySourceChanged(lastSuccessfulHistory, messageId, update)
                || summaryRange != null && sourceChanged(summaryRange.replayMessages, messageId, update)) {
            invalidateSources("原消息已修改或不再可用，当前摘要及原文快照已过期。请重新读取消息后总结。", false);
        }
    }

    public void onAccessRevoked() {
        if (!closed) invalidateSources("当前聊天已不可访问，已清除摘要和原文快照。", true);
    }

    private boolean sourceChanged(ArrayList<SummaryMessage> sources, int messageId, SourceUpdate update) {
        if (sources == null) return false;
        for (SummaryMessage source : sources) {
            if (source.dialogId == dialogId && source.id == messageId && update.changed(source)) return true;
        }
        return false;
    }

    private boolean historySourceChanged(SummaryHistoryLoader.Result history, int messageId, SourceUpdate update) {
        return history != null && sourceChanged(history.messages, messageId, update);
    }

    private void invalidateSources(String reason, boolean accessRevoked) {
        if (closed || !checkAccountOwner()) return;
        cancelWork();
        clearCachedResult();
        sourceSnapshotInvalid = true;
        sourceMessages = null;
        summaryHistory = null;
        summaryRange = null;
        lastSuccessfulSources = null;
        lastSuccessfulHistory = null;
        summaryPrompt = null;
        summaryConfig = null;
        coverageNote = null;
        clearContent();
        addText(accessRevoked ? "当前聊天内容不可用于总结" : "摘要已过期", true);
        addText(reason, false);
        if (!accessRevoked) addAction("重新读取并选择范围", this::showSelection);
    }

    private void invalidateCachedSource(int messageId, SourceUpdate update) {
        if (cachedResult != null && (sourceChanged(cachedResult.sources, messageId, update)
                || historySourceChanged(cachedResult.history, messageId, update))) clearCachedResult();
    }

    private void clearCachedResult() {
        if (cachedResult != null) SummaryResultCache.getInstance().invalidate(cachedResult.key);
        cachedResult = null;
        viewingCachedResult = false;
    }

    private void cacheCompletedResult(String summary) {
        if (sourceSnapshotInvalid || summaryHistory == null || !summaryHistory.complete
                || sourceMessages == null || sourceMessages.isEmpty()
                || summaryRange == null || !resultCommitted && summaryRange.mode != RangeMode.REPLAY) return;
        try {
            SummaryResultCache.Key key = SummaryResultCache.key(account, ownerId, dialogId, topicId,
                    sourceMessages, summaryPrompt, summaryConfig, null);
            long generatedAt = System.currentTimeMillis();
            if (SummaryResultCache.getInstance().put(key, summary, generatedAt)) {
                cachedResult = new CachedResult(key, sourceMessages, summaryHistory, summaryRange,
                        summaryConfig, summaryPrompt, coverageNote, generatedAt,
                        Math.max(0L, SystemClock.elapsedRealtime() - summaryStartedAt));
            }
        } catch (RuntimeException ignored) {
            // Optional, bounded memory caching must not turn a completed task into a failure.
        }
    }

    private void viewExistingResult() {
        if (cachedResult == null || closed || !checkAccountOwner()) return;
        if (isSummaryAccessRevoked()) {
            onAccessRevoked();
            return;
        }
        CachedResult snapshot = cachedResult;
        SummaryResultCache.Entry entry = SummaryResultCache.getInstance().get(snapshot.key, true);
        if (entry == null) {
            cachedResult = null;
            settingsNotice = "已有结果已失效或已被清理，请重新生成。";
            showSelection();
            return;
        }
        cancelWork();
        sourceSnapshotInvalid = false;
        viewingCachedResult = true;
        sourceMessages = new ArrayList<>(snapshot.sources);
        summaryHistory = snapshot.history;
        summaryRange = snapshot.range;
        summaryFiltered = snapshot.range.filters != null && snapshot.range.filters.hasFilters();
        summaryConfig = snapshot.config;
        summaryPrompt = snapshot.prompt;
        coverageNote = snapshot.coverage;
        summaryStartedAt = SystemClock.elapsedRealtime() - snapshot.elapsedMs;
        resultCommitted = false;
        completionNotice = "已有结果，生成于 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(entry.generatedAtMillis)) + "。这是当时原文和配置生成的历史结果，不代表当前最新消息或模型。";
        showResult(entry.summary);
    }

    private void closeSourcePreview() {
        sourceOperation++;
        if (sourceVerifier != null) {
            sourceVerifier.cancel();
            sourceVerifier = null;
        }
        AlertDialog previous = sourceDialog;
        sourceDialog = null;
        sourceContent = null;
        if (previous != null) previous.dismiss();
    }

    private boolean sourcePreviewActive(int parentGeneration, int previewGeneration) {
        return !sourceSnapshotInvalid && sourceOperation == previewGeneration && sourceDialog != null
                && sourceDialog.isShowing() && active(parentGeneration);
    }

    private void showSourcePreview(SummaryMessage source, int reference) {
        if (!active(operation) || sourceSnapshotInvalid) return;
        closeSourcePreview();
        final int parentGeneration = operation;
        final int previewGeneration = sourceOperation;
        sourceContent = new LinearLayout(context);
        sourceContent.setOrientation(LinearLayout.VERTICAL);
        sourceContent.setPadding(dp(24), 0, dp(24), dp(8));
        final AlertDialog preview = new AlertDialog.Builder(context, resourcesProvider)
                .setTitle("原文引用 [m" + reference + "]")
                .setView(sourceContent)
                .setNegativeButton("关闭", (ignored, which) -> closeSourcePreview())
                .create();
        sourceDialog = preview;
        preview.setOnDismissListener(ignored -> {
            if (sourceDialog == preview) closeSourcePreview();
        });
        try {
            // BaseFragment.showDialog would dismiss the parent summary dialog.
            preview.show();
            verifySource(source, parentGeneration, previewGeneration, false);
        } catch (RuntimeException ignored) {
            closeSourcePreview();
        }
    }

    private void verifySource(SummaryMessage expected, int parentGeneration, int previewGeneration, boolean jump) {
        if (!sourcePreviewActive(parentGeneration, previewGeneration)) return;
        if (sourceVerifier != null) sourceVerifier.cancel();
        sourceContent.removeAllViews();
        addText(sourceContent, jump ? "正在重新核验原消息…" : "正在读取并核验原消息…", true);
        sourceVerifier = new SummarySourceVerifier(account, ownerId, dialogId, topicId);
        sourceVerifier.verify(expected, new SummarySourceVerifier.Callback() {
            @Override
            public void onVerified(SummaryMessage current) {
                if (!sourcePreviewActive(parentGeneration, previewGeneration)) return;
                sourceVerifier = null;
                if (jump) {
                    if (navigator != null) {
                        dismiss();
                        navigator.open(current.dialogId, current.id);
                    }
                    return;
                }
                sourceContent.removeAllViews();
                addText(sourceContent, current.sender, true);
                addText(sourceContent, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date(current.date * 1000L)), false);
                TextView original = addText(sourceContent, current.text, false);
                original.setTextColor(color(Theme.key_dialogTextBlack));
                original.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                original.setTextIsSelectable(true);
                addAction(sourceContent, "跳回群聊", () ->
                        verifySource(current, parentGeneration, previewGeneration, true));
            }

            @Override
            public void onInvalidated(String reason) {
                if (sourcePreviewActive(parentGeneration, previewGeneration)) invalidateSources(reason, false);
            }

            @Override
            public void onError(String message) {
                if (!sourcePreviewActive(parentGeneration, previewGeneration)) return;
                sourceVerifier = null;
                sourceContent.removeAllViews();
                addText(sourceContent, "暂时无法显示原文", true);
                addText(sourceContent, message, false);
                addAction(sourceContent, "重新核验", () ->
                        verifySource(expected, parentGeneration, previewGeneration, false));
            }
        });
    }

    private void stopProgressUpdates() {
        streamingRequestGeneration++;
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
        requestStatus = null;
        latestRequestStatus = null;
        latestSummaryProgress = null;
        streamingRequestStage = null;
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
                updateRequestStatusText();
                AndroidUtilities.runOnUIThread(this, 1000);
            }
        };
        progressTicker.run();
    }

    private void updateSummaryProgress(AiSummaryClient.Progress progress) {
        latestSummaryProgress = progress;
        if (progressStatus == null) return;
        switch (progress.stage) {
            case SOURCE:
                progressStatus.setText(progress.total == 1
                        ? progress.completed == 0 ? "正在生成摘要（1 次模型请求）" : "摘要生成完成，正在校验"
                        : "总结分段：已完成 " + progress.completed + " / " + progress.total + "；全部完成后还需合并");
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

    private String currentRequestStage() {
        AiSummaryClient.Progress progress = latestSummaryProgress;
        if (progress == null) return "当前模型请求";
        int total = Math.max(1, progress.total);
        int current = Math.max(1, Math.min(total, progress.completed + 1));
        if (progress.stage == AiSummaryClient.Stage.SOURCE) {
            return "总结第 " + current + " / " + total + " 段";
        }
        if (progress.stage == AiSummaryClient.Stage.MERGE) {
            return "第 " + progress.mergeRound + " 轮合并 · 第 " + current + " / " + total + " 段";
        }
        return "最终引用校验阶段";
    }

    private void beginStreamingRequest() {
        streamingRequestGeneration++;
        streamingRequestStage = currentRequestStage();
        streamingDraft = null;
        if (partialUpdate != null) {
            AndroidUtilities.cancelRunOnUIThread(partialUpdate);
            partialUpdate = null;
        }
        lastPartialUpdateAt = 0;
        if (partialAnswer != null) {
            partialAnswer.setText("");
            partialAnswer.setVisibility(View.GONE);
        }
        if (partialLabel != null) {
            partialLabel.setText(streamingRequestStage + " · 生成中，尚未完成或校验");
            partialLabel.setVisibility(View.GONE);
        }
    }

    private void updateRequestStatusText() {
        if (requestStatus == null || latestRequestStatus == null) return;
        AiSummaryClient.RequestStatus status = latestRequestStatus;
        String stage = status.phase == AiSummaryClient.RequestPhase.SENDING
                ? status.streaming ? "正在请求模型接口" : "正在请求模型接口，普通模式需等待完整回复"
                : status.phase == AiSummaryClient.RequestPhase.RESPONSE ? "模型接口已响应，等待生成内容"
                : "已收到 " + status.receivedCharacters + " 字符（不是 token 数）"
                    + (status.reasoningObserved ? "；检测到思考内容，已隐藏" : "");
        requestStatus.setText(stage + "。\n当前请求：输入约 " + status.inputCharacters
                + " 字符，输出上限 " + status.maxOutputTokens + " tokens；已等待 "
                + Math.max(0L, (SystemClock.elapsedRealtime() - modelRequestStartedAt) / 1000L) + " 秒。");
    }

    private void updatePartialAnswer(String text, int generation) {
        streamingDraft = text;
        if (partialUpdate != null) return;
        final int requestGeneration = streamingRequestGeneration;
        partialUpdate = new Runnable() {
            @Override public void run() {
                // A cancelled update must neither render nor detach a newer request's update.
                if (partialUpdate != this) return;
                partialUpdate = null;
                if (requestGeneration != streamingRequestGeneration || !active(generation)
                        || partialAnswer == null || partialLabel == null) return;
                lastPartialUpdateAt = SystemClock.elapsedRealtime();
                boolean hasText = streamingDraft != null && !streamingDraft.isEmpty();
                partialLabel.setVisibility(hasText ? View.VISIBLE : View.GONE);
                partialAnswer.setVisibility(hasText ? View.VISIBLE : View.GONE);
                // Every request replaces its own accumulated plain text; intermediate references
                // remain non-interactive until the complete summary passes final validation.
                partialAnswer.setText(streamingDraft == null ? "" : streamingDraft);
            }
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
        requestInputsButton = null;
        historySaveStatus = null;
        historySaveRetry = null;
        requestStatus = null;
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
        addAction(topicId == 0 ? "本聊天的总结历史" : "本话题的总结历史", this::openHistory);
        if (isSummaryAccessRevoked()) {
            addText("当前聊天已不可访问，请返回聊天确认读取权限后重试。", false);
            return;
        }
        if (!promptLoaded) {
            loadPromptPreferences();
            return;
        }
        if (summaryState == null) {
            loadSummaryState(false);
            return;
        }
        addText(topicId == 0 ? "选择当前聊天的文字消息范围" : "仅总结当前话题的文字消息", true);
        if (settingsNotice != null) {
            addText(settingsNotice, false);
        }
        if (cachedResult != null) {
            addAction("查看已有结果（" + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(cachedResult.generatedAt)) + "）", this::viewExistingResult);
        }

        RadioGroup choices = new RadioGroup(context);
        choices.setOrientation(RadioGroup.VERTICAL);
        for (RangeMode mode : new RangeMode[] {RangeMode.RECENT, RangeMode.TODAY, RangeMode.SINCE, RangeMode.UNREAD}) {
            RadioButton option = radio(mode == RangeMode.RECENT ? "最近 N 条文字消息"
                    : mode == RangeMode.TODAY ? "当日文字消息"
                    : mode == RangeMode.SINCE ? "上次总结之后" : "进入聊天时的未读消息");
            option.setId(View.generateViewId());
            option.setTag(mode);
            if (mode == RangeMode.UNREAD && !hasUnreadSnapshot()) {
                option.setEnabled(false);
                option.setAlpha(0.5f);
            }
            choices.addView(option, new RadioGroup.LayoutParams(-1, dp(46)));
            if (mode == rangeMode) choices.check(option.getId());
        }
        content.addView(choices, LayoutHelper.createLinear(-1, -2, 0, 4, 0, 4));

        EditTextBoldCursor count = edit("条数（1–" + SummaryHistoryLoader.MAX_RECENT_COUNT + "）", recentCountText,
                InputType.TYPE_CLASS_NUMBER);
        TextView rangeNote = addText("", false);
        Runnable updateRange = () -> {
            count.setEnabled(rangeMode != RangeMode.TODAY);
            count.setAlpha(rangeMode == RangeMode.TODAY ? 0.5f : 1f);
            count.setHint((rangeMode == RangeMode.UNREAD || rangeMode == RangeMode.SINCE && summaryState.cursor > 0
                    ? "每批历史条数" : "文字条数") + "（1–" + SummaryHistoryLoader.MAX_RECENT_COUNT + "）");
            rangeNote.setText(rangeMode == RangeMode.SINCE
                    ? summaryState.cursor == 0
                        ? "尚无增量起点。本次以最近 N 条文字初始化，之前的历史不包含；成功后从该位置继续补齐新消息。"
                        : "从已记录位置之后按时间先后分批补齐。每批历史条数包含媒体和服务消息，但只有文字会发送给模型。"
                    : rangeMode == RangeMode.UNREAD
                        ? "固定本次进入聊天时的未读边界；每批条数包含非文字消息。不改变 Telegram 已读状态，也不移动增量进度。"
                    : rangeMode == RangeMode.TODAY
                        ? "按手机时区从今天 00:00 起读取，当前时间由 Telegram 校准，范围截至开始时；不移动增量进度。"
                        : "从最新消息向前选取 N 条有效文字，再按时间先后总结；不移动增量进度。需要建立增量起点时请选择“上次总结之后”。");
        };
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton checked = choices.findViewById(checkedId);
            rangeMode = (RangeMode) checked.getTag();
            updateRange.run();
        });
        updateRange.run();
        if (!hasUnreadSnapshot()) {
            addText("本次未取得可靠的进入聊天前未读边界，因此未读范围不可用；可重新进入聊天后再试。", false);
        }
        addText("点击“开始总结”才会将文字发送到 MNN Chat API。也可独立导出待总结消息，不需要配置或启动模型。已完成的摘要自动加密保存在本机总结历史。", false);

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

        addText("消息选择：" + filterLabel(filterOptions), false);
        if (filterOptions.hasFilters()) {
            addText("已启用实际消息筛选：本次不会建立或推进通用增量起点。要补齐全部消息，请恢复全部文字后运行。", true);
        }
        addAction("与我相关与筛选", () -> {
            recentCountText = count.getText().toString();
            showFilterEditor();
        });

        addAction("MNN API 设置", () -> {
            recentCountText = count.getText().toString();
            showSettings();
        });
        addAction("开始总结", () -> {
            if (readSelectedCount(count)) startSummary();
        });
        addAction("导出待总结消息", () -> {
            if (readSelectedCount(count)) showExportFormats(selectedRangeRequest());
        });
        if (lastSuccessfulHistory != null && lastSuccessfulSources != null) {
            addAction("用当前方向重做上次成功范围", () -> {
                recentCountText = count.getText().toString();
                RangeRequest request = new RangeRequest(RangeMode.REPLAY, recentCount, summaryState.cursor,
                        lastSuccessfulHistory.lowerExclusiveId, lastSuccessfulHistory.coveredThroughId,
                        false, lastSuccessfulHistory, lastSuccessfulHistory.messages);
                startSummary(request);
            });
            addText("重做使用上次成功范围的原始文字快照，重新应用当前筛选与方向，不移动增量进度；关闭面板后该快照不保留。", false);
        }
        if (summaryState.completedAt > 0) {
            addAction("按已保存范围分批重读并重做", () -> {
                recentCountText = count.getText().toString();
                startSummary(new RangeRequest(RangeMode.REPLAY, SummaryHistoryLoader.MAX_RECENT_COUNT,
                        summaryState.cursor, summaryState.lastLowerExclusive, summaryState.lastUpperInclusive,
                        false, null, null));
            });
            addText("摘要正文不持久化。可按记录范围每批最多 500 条历史重新生成；重新读取的原文可能已编辑或删除，不移动增量进度。", false);
        }
    }

    private boolean hasUnreadSnapshot() {
        return unreadLower >= 0 && unreadUpper >= unreadLower;
    }

    private boolean readSelectedCount(EditTextBoldCursor count) {
        recentCountText = count.getText().toString().trim();
        if (rangeMode == RangeMode.TODAY) return true;
        try {
            recentCount = Integer.parseInt(recentCountText);
        } catch (NumberFormatException ignored) {
            recentCount = 0;
        }
        if (recentCount < 1 || recentCount > SummaryHistoryLoader.MAX_RECENT_COUNT) {
            count.setError("请输入 1–" + SummaryHistoryLoader.MAX_RECENT_COUNT + " 之间的条数");
            count.requestFocus();
            return false;
        }
        return true;
    }

    private RangeRequest selectedRangeRequest() {
        return new RangeRequest(rangeMode, recentCount, summaryState.cursor,
                rangeMode == RangeMode.UNREAD ? unreadLower : summaryState.cursor,
                rangeMode == RangeMode.UNREAD ? unreadUpper : -1,
                rangeMode == RangeMode.SINCE && summaryState.cursor == 0, null, null, filterOptions);
    }

    private String exportRangeLabel(RangeRequest request) {
        if (request.mode == RangeMode.TODAY) return "当日文字消息（手机时区，今天 00:00 起）";
        if (request.mode == RangeMode.RECENT) return "最近 " + request.count + " 条有效文字消息（不含其他类型）";
        if (request.mode == RangeMode.SINCE && request.initialize) {
            return "尚无总结起点：仅导出最近 " + request.count + " 条有效文字，不建立增量起点";
        }
        return (request.mode == RangeMode.UNREAD ? "进入聊天时的固定未读范围" : "上次总结之后")
                + "：本批最多 " + request.count + " 条历史消息，只导出其中匹配的文字";
    }

    private void showExportFormats(RangeRequest request) {
        if (closed || !checkAccountOwner()) return;
        cancelWork();
        final PromptOptions prompt = effectivePrompt().withFocusSelf(request.filters.mode == SummaryFilter.Mode.FOCUS_SELF);
        clearContent();
        addText("导出待总结消息", true);
        addText(exportRangeLabel(request), false);
        addText("消息选择：" + filterLabel(request.filters), false);
        addText("推荐群聊对话格式：按时间显示谁说了什么、谁回复了谁，可直接阅读或交给 AI。文件会附带当前总结方向。", false);
        addText("先读取并核对范围，再由你选择保存位置或分享应用；不会自动上传。", false);
        addAction("群聊对话（Markdown，推荐）", () -> startExport(request, SummaryChatExport.Format.MARKDOWN, prompt));
        addAction("JSON（完整数据）", () -> startExport(request, SummaryChatExport.Format.JSON, prompt));
        addAction("返回范围选择", this::showSelection);
    }

    private void startExport(RangeRequest request, SummaryChatExport.Format format, PromptOptions prompt) {
        if (closed || !checkAccountOwner()) return;
        if (isSummaryAccessRevoked()) {
            onAccessRevoked();
            return;
        }
        cancelWork();
        ExportTask task = new ExportTask(request, format, prompt);
        exportTask = task;
        final int generation = operation;
        clearContent();
        TextView status = addText("正在读取导出范围…", true);
        addText(exportRangeLabel(request), false);
        addText("不连接 MNN，不改变总结进度。", false);
        addAction("取消导出", this::showSelection);
        historyLoader = new SummaryHistoryLoader(account, dialogId, topicId);
        SummaryHistoryLoader.Callback callback = new SummaryHistoryLoader.Callback() {
            @Override public void onLoaded(SummaryHistoryLoader.Result result) {
                if (!exportActive(task, generation)) return;
                historyLoader = null;
                prepareExport(task, result, generation);
            }
            @Override public void onProgress(int scanned, int textCount) {
                if (exportActive(task, generation)) status.setText("正在读取：已扫描 " + scanned + " 条，收集 " + textCount + " 条文字");
            }
            @Override public void onError(String error) {
                if (exportActive(task, generation)) showExportError(task, error);
            }
        };
        loadRange(historyLoader, request, callback);
    }

    private boolean exportActive(ExportTask task, int generation) {
        return exportTask == task && !task.cancelled && active(generation);
    }

    private void prepareExport(ExportTask task, SummaryHistoryLoader.Result history, int generation) {
        task.history = history;
        task.rawSources = new ArrayList<>(history.messages);
        for (SummaryMessage source : task.rawSources) {
            SourceUpdate update = pendingSourceUpdates.get(source.id);
            if (update != null && update.changed(source)) {
                showExportError(task, "读取期间原消息已变化，请重新读取后导出。");
                return;
            }
        }
        pendingSourceUpdates.clear();
        if (isSummaryAccessRevoked()) {
            onAccessRevoked();
            return;
        }
        SummaryFilter.Result filtered = SummaryFilter.apply(task.rawSources, ownerId, task.range.filters);
        task.sources = new ArrayList<>(filtered.messages);
        task.coverage = history.coverageNote + "\n" + filtered.coverageNote.replace("实际发送", "实际导出");
        if (task.sources.isEmpty()) {
            showExportPreview(task, "当前范围没有匹配的文字消息，未生成文件。");
            return;
        }
        org.telegram.tgnet.TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        String title = chat == null || chat.title == null ? "群聊" : chat.title;
        org.telegram.tgnet.TLRPC.TL_forumTopic topic = topicId == 0 ? null
                : MessagesController.getInstance(account).getTopicsController().findTopic(-dialogId, topicId);
        SummaryChatExport.Metadata metadata = new SummaryChatExport.Metadata(dialogId, title, topicId,
                topic == null || topic.title == null ? "" : topic.title, System.currentTimeMillis(), task.timeZoneId,
                exportRangeLabel(task.range), filterLabel(task.range.filters), task.coverage,
                !history.complete || history.truncated, history.hasMore, history.scannedMessageCount);
        clearContent();
        addText("正在生成 " + task.format.name() + " 文件…", true);
        addText("本次匹配 " + task.sources.size() + " 条文字；仅写入应用私有临时目录。", false);
        addAction("取消导出", this::showSelection);
        SummaryExportFileHelper.post(() -> {
            SummaryExportFileHelper.PreparedFile prepared = null;
            try {
                if (task.cancelled) return;
                if (!sameAccountOwner()) {
                    AndroidUtilities.runOnUIThread(this::dismiss);
                    return;
                }
                int missing = SummaryChatExport.missingReplyTargetCount(task.sources);
                byte[] bytes = SummaryChatExport.render(task.format, metadata, task.sources, task.prompt);
                prepared = SummaryExportFileHelper.prepare(context.getApplicationContext(), bytes,
                        task.format.extension, task.format.mimeType, () -> task.cancelled || !sameAccountOwner());
                final SummaryExportFileHelper.PreparedFile result = prepared;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!exportActive(task, generation)) {
                        result.release();
                        return;
                    }
                    task.file = result;
                    task.missingReplies = missing;
                    showExportPreview(task, null);
                });
            } catch (Exception error) {
                if (prepared != null) prepared.release();
                final String reason = error instanceof IllegalArgumentException ? error.getMessage()
                        : "无法生成导出文件，请检查设备可用空间后重试。";
                AndroidUtilities.runOnUIThread(() -> {
                    if (exportActive(task, generation)) showExportError(task, reason);
                });
            }
        });
    }

    private void showExportPreview(ExportTask task, String notice) {
        if (exportTask != task || task.cancelled || closed || !checkAccountOwner()) return;
        clearContent();
        addText("核对导出范围", true);
        addText(exportRangeLabel(task.range), false);
        addText("本次实际导出 " + task.sources.size() + " 条文字消息。", true);
        addText(task.coverage, false);
        if (!task.history.complete || task.history.truncated) {
            addText("本次读取仅部分覆盖；文件只包含已经读取到的匹配文字。", true);
        }
        if (task.history.hasMore) addText("固定范围还有后续消息，本文件只包含本批，不能视为全部范围。", true);
        if (task.file != null) {
            if (task.missingReplies > 0) addText("有 " + task.missingReplies + " 条回复的原消息未包含在本次导出中。", false);
            addText(task.file.file.getName() + " · " + String.format(Locale.US, "%.1f KiB", task.file.bytes / 1024.0), false);
            addText("文件包含可识别发言者的原文。保存或分享由你选择；交给系统分享后临时文件保留约 24 小时，应用运行时清理，进程退出后会在后续导出时清理过期文件。", false);
        }
        if (notice != null) addText(notice, true);
        if (task.file != null) {
            addAction("保存到文件", () -> chooseExportDestination(task));
            addAction("分享导出文件", () -> shareExport(task));
        }
        if (task.history.complete && task.history.hasMore
                && (task.range.mode == RangeMode.SINCE || task.range.mode == RangeMode.UNREAD)) {
            addAction("读取并导出下一批", () -> startExport(new RangeRequest(task.range.mode, task.range.count,
                    task.range.expectedCursor, task.history.coveredThroughId, task.history.upperInclusiveId,
                    false, null, null, task.range.filters), task.format, task.prompt));
            addText("下一批仅在本次导出中继续，通用总结进度保持不变。", false);
        }
        addAction("重新读取此范围", () -> startExport(task.range, task.format, task.prompt));
        addAction("返回范围选择", this::showSelection);
    }

    private void shareExport(ExportTask task) {
        if (exportTask != task || task.cancelled || !checkAccountOwner()) return;
        if (isSummaryAccessRevoked()) { onAccessRevoked(); return; }
        try {
            task.file.share(context);
        } catch (Exception ignored) {
            if (!closed && exportTask == task) showExportError(task, "无法打开系统分享，请重新生成后重试或使用“保存到文件”。");
        }
    }

    private void chooseExportDestination(ExportTask task) {
        if (exportDocumentPending || exportTask != task || task.cancelled || !checkAccountOwner()) return;
        if (isSummaryAccessRevoked()) { onAccessRevoked(); return; }
        int requestCode = NEXT_EXPORT_DOCUMENT_REQUEST.getAndIncrement();
        if (requestCode > 0x7fff) {
            showExportPreview(task, "本次运行的文件选择次数已达上限，请使用分享或重启应用后保存。");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(task.file.mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, task.file.file.getName());
        exportDocumentPending = true;
        exportDocumentRequest = requestCode;
        exportDocumentTask = task;
        exportDocumentGeneration = operation;
        try {
            fragment.startActivityForResult(intent, requestCode);
        } catch (RuntimeException ignored) {
            exportDocumentPending = false;
            exportDocumentTask = null;
            showExportPreview(task, "无法打开系统文件选择器，可使用分享或稍后重试。");
        }
    }

    /** Parent fragments keep only this dialog alive while the system file picker is active. */
    public boolean keepExportDialogOnPause(Dialog candidate) {
        return candidate == dialog && exportDocumentPending && !closed && exportTask != null
                && exportDocumentTask == exportTask && exportDocumentGeneration == operation
                && !exportTask.cancelled && sameAccountOwner();
    }

    /** Consumes only our dedicated ACTION_CREATE_DOCUMENT response. */
    public boolean onExportActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != exportDocumentRequest || exportDocumentRequest < 0) return false;
        boolean expected = exportDocumentPending;
        exportDocumentPending = false;
        final ExportTask task = exportDocumentTask;
        final int generation = exportDocumentGeneration;
        exportDocumentTask = null;
        if (!expected || task == null || task != exportTask || generation != operation
                || task.cancelled || closed || !checkAccountOwner()) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            showExportPreview(task, "已取消保存，未写入外部文件。");
            return true;
        }
        if (isSummaryAccessRevoked()) { onAccessRevoked(); return true; }
        final Uri destination = data.getData();
        clearContent();
        addText("正在保存导出文件…", true);
        addText("保存到你选择的位置，不改变总结进度。", false);
        addAction("停止保存并返回", () -> {
            settingsNotice = "已停止保存；所选目标文件可能不完整，可重新导出保存。";
            showSelection();
        });
        boolean saving = SummaryExportFileHelper.postSave(() -> {
            String failure = null;
            try {
                SummaryExportFileHelper.save(context.getApplicationContext(), task.file, destination,
                        () -> task.cancelled || !sameAccountOwner());
            } catch (Exception ignored) {
                failure = "保存失败，目标文件可能不完整。请检查存储空间或选择其他位置后重试。";
            }
            final String message = failure == null ? "已保存到所选文件。" : failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (exportActive(task, generation)) showExportPreview(task, message);
            });
        });
        if (!saving) showExportPreview(task, "文件提供方仍有保存操作未返回，请稍后再试或使用分享；本次未开始写入所选文件。");
        return true;
    }

    private void showExportError(ExportTask task, String message) {
        cancelWork();
        clearContent();
        addText("暂时无法导出", true);
        addText(message, false);
        addText("导出没有调用模型，也没有更新总结进度或摘要历史。", false);
        addAction("重新读取并重试", () -> startExport(task.range, task.format, task.prompt));
        addAction("返回范围选择", this::showSelection);
    }

    private void invalidateExportSources(String message) {
        ExportTask task = exportTask;
        clearCachedResult();
        sourceSnapshotInvalid = true;
        sourceMessages = null;
        summaryHistory = null;
        summaryRange = null;
        lastSuccessfulSources = null;
        lastSuccessfulHistory = null;
        summaryConfig = null;
        summaryPrompt = null;
        if (task != null) showExportError(task, message);
    }

    private static String filterLabel(SummaryFilter.Options options) {
        String label = options.mode == SummaryFilter.Mode.FOCUS_SELF ? "全部文字，重点关注与我相关"
                : options.mode == SummaryFilter.Mode.FILTER_SELF ? "仅与我相关及必要上下文" : "全部文字";
        if (options.senderId != 0) label += " · 成员 ID " + options.senderId;
        if (!options.keyword.isEmpty()) label += " · 关键词：" + options.keyword;
        return label;
    }

    private void showFilterEditor() {
        cancelWork();
        clearContent();
        addText("与我相关与筛选", true);
        addText("仅作用于本面板，先读取选定范围，再筛选其中的文字。", false);
        RadioGroup modes = new RadioGroup(context);
        modes.setOrientation(RadioGroup.VERTICAL);
        for (SummaryFilter.Mode mode : SummaryFilter.Mode.values()) {
            RadioButton option = radio(mode == SummaryFilter.Mode.ALL ? "总结全部文字"
                    : mode == SummaryFilter.Mode.FOCUS_SELF ? "保留全部，重点关注与我相关" : "仅总结与我相关及必要上下文");
            option.setId(View.generateViewId());
            option.setTag(mode);
            modes.addView(option, new RadioGroup.LayoutParams(-1, -2));
            if (mode == filterOptions.mode) modes.check(option.getId());
        }
        content.addView(modes, LayoutHelper.createLinear(-1, -2, 0, 4, 0, 4));
        addText("与我相关仅按明确 @ 我、已确认回复我、本人身份可确认的发言匹配，不猜测匿名身份。仅相关模式还会带入范围内前后各一条文字及明确回复的原文，作为必要上下文。", false);
        addText("成员 ID（可留空）", true);
        EditTextBoldCursor sender = edit("留空为全部成员", filterOptions.senderId == 0 ? "" : Long.toString(filterOptions.senderId),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        addText("按 Telegram 真实发言者 ID 精确筛选；本人 ID：" + ownerId + "。不按显示名猜测成员。", false);
        addText("正文关键词（可留空，最多 128 个字符）", true);
        EditTextBoldCursor keyword = edit("不区分大小写的字面匹配", filterOptions.keyword, InputType.TYPE_CLASS_TEXT);
        addText("成员、关键词和仅相关条件同时满足才匹配；额外上下文可能不满足条件。任何实际筛选都不会推进通用“上次总结之后”进度，零匹配也不会退回全部消息。", false);
        TextView validation = addText("", false);
        validation.setTextColor(color(Theme.key_text_RedRegular));
        addAction("应用本次筛选", () -> {
            long senderId = 0;
            String senderText = sender.getText().toString().trim();
            if (!senderText.isEmpty()) {
                try {
                    senderId = Long.parseLong(senderText);
                } catch (NumberFormatException ignored) {
                    sender.setError("请输入有效的 Telegram 成员 ID");
                    return;
                }
                if (senderId == 0) {
                    sender.setError("ID 不能为 0；全部成员请留空");
                    return;
                }
            }
            RadioButton selected = modes.findViewById(modes.getCheckedRadioButtonId());
            try {
                filterOptions = new SummaryFilter.Options((SummaryFilter.Mode) selected.getTag(), senderId,
                        keyword.getText().toString());
                showSelection();
            } catch (IllegalArgumentException error) {
                validation.setText(error.getMessage());
            }
        });
        addAction("恢复全部文字", () -> {
            filterOptions = SummaryFilter.Options.DEFAULT;
            showSelection();
        });
        addAction("取消", this::showSelection);
    }

    private void loadSummaryState(boolean reset) {
        if (closed || !checkAccountOwner()) return;
        cancelWork();
        final int generation = operation;
        clearContent();
        addText(reset ? "正在清除本聊天的总结进度…" : "正在读取总结进度…", true);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameAccountOwner()) {
                AndroidUtilities.runOnUIThread(this::dismiss);
                return;
            }
            try {
                if (reset) SummaryStateStore.clear(account, ownerId, dialogId, topicId);
                SummaryStateStore.State state = SummaryStateStore.load(account, ownerId, dialogId, topicId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    summaryState = state;
                    showSelection();
                });
            } catch (RuntimeException ignored) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    clearContent();
                    addText("无法读取或更新本聊天的总结进度。", true);
                    addText("可以重试；如果本地记录已损坏，可清除后重新选择增量起点。这不会修改 Telegram 消息或已读状态。", false);
                    addAction("重试读取", () -> loadSummaryState(false));
                    addAction("清除本聊天进度并重新选择起点", () -> loadSummaryState(true));
                });
            }
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
        addText("MNN 本机 API 最高支持 2048，建议先用 512；其他服务按其限制填写。", false);
        addText("上下文字符预算（2048–32000）", true);
        EditTextBoldCursor contextBudget = edit("6000", Integer.toString(config.inputCharacterBudget),
                InputType.TYPE_CLASS_NUMBER);
        addText("这是保守的字符估计，不是模型 token 数。输出按每 token 预留 4 字符，此外还需容纳规则、补充要求和聊天内容。", false);
        addText("常用搭配（最大输出 / 字符预算）：512 / 6000、1024 / 12000、2048 / 16000。较大预算可能增加手机内存及耗时。", false);
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
            AiSummarySettings.Config snapshot = readSettingsInput(address, model, key, outputTokens, contextBudget,
                    validation, streaming.isChecked());
            if (snapshot != null) {
                testConnection(snapshot);
            }
        });
        addAction("保存设置", () -> {
            AiSummarySettings.Config updated = readSettingsInput(address, model, key, outputTokens, contextBudget,
                    validation, streaming.isChecked());
            if (updated != null) {
                saveSettings(updated);
            }
        });
        addAction("返回范围选择", this::showSelection);
    }

    private AiSummarySettings.Config readSettingsInput(EditTextBoldCursor address, EditTextBoldCursor model,
                                                       EditTextBoldCursor key, EditTextBoldCursor outputTokens,
                                                       EditTextBoldCursor contextBudget, TextView validation, boolean stream) {
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
        int inputCharacterBudget;
        try {
            inputCharacterBudget = Integer.parseInt(contextBudget.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            inputCharacterBudget = 0;
        }
        if (inputCharacterBudget < 2048 || inputCharacterBudget > 32000) {
            contextBudget.setError("请输入 2048–32000 之间的值");
            contextBudget.requestFocus();
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
        addText("本次最大输出：" + config.maxOutputTokens + " tokens。手机推理可能需要较长时间，可随时取消测试。", false);
        addText("请保持 Telegram 在前台，并确保 MNN Chat 已加载模型、开启 API 服务。", false);
        addAction("取消测试并返回设置", () -> returnToSettings(config));
        client = new AiSummaryClient();
        client.testConnection(config, new AiSummaryClient.DiagnosticCallback() {
            @Override
            public void onSuccess(AiSummaryClient.DiagnosticResult result) {
                if (active(generation)) {
                    client = null;
                    showConnectionResult(config, result, null, null);
                }
            }

            @Override
            public void onError(String error) {
                onError(error, null);
            }

            @Override
            public void onError(String error, AiSummaryClient.DiagnosticErrorInfo info) {
                if (active(generation)) {
                    client = null;
                    showConnectionResult(config, null, error, info);
                }
            }
        });
    }

    private void showConnectionResult(AiSummarySettings.Config config,
                                      AiSummaryClient.DiagnosticResult result, String error,
                                      AiSummaryClient.DiagnosticErrorInfo info) {
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
            if (info != null) {
                addText("连接测试诊断", true);
                String format;
                switch (info.responseFormat) {
                    case "json": format = "JSON"; break;
                    case "text": format = "文本"; break;
                    case "html": format = "HTML 页面"; break;
                    case "empty": format = "空响应"; break;
                    default: format = "未知"; break;
                }
                addText("HTTP " + info.status + " · 返回格式：" + format, false);
                if (!info.serverMessage.isEmpty()) {
                    addText("服务端错误说明", true);
                    // Plain selectable text: server content is never rendered as HTML or links.
                    addText(info.serverMessage, false).setTextIsSelectable(true);
                } else if ("html".equals(info.responseFormat)) {
                    addText("服务返回了 HTML 错误页。请核对地址和端口是否与 MNN Chat API 设置页一致。", false);
                } else if (info.status != 401 && info.status != 403
                        && !(info.status >= 300 && info.status < 400)) {
                    addText("服务未返回可展示的具体原因。排查时请同时提供 API 地址、模型名称和 MNN Chat 版本。", false);
                }
            }
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
        if (summaryState == null) {
            loadSummaryState(false);
            return;
        }
        RangeRequest request = new RangeRequest(rangeMode, recentCount, summaryState.cursor,
                rangeMode == RangeMode.UNREAD ? unreadLower : summaryState.cursor,
                rangeMode == RangeMode.UNREAD ? unreadUpper : -1,
                rangeMode == RangeMode.SINCE && summaryState.cursor == 0, null, null);
        startSummary(request);
    }

    private void startSummary(RangeRequest request) {
        final RangeRequest snapshot = request.filters == null
                ? new RangeRequest(request.mode, request.count, request.expectedCursor, request.lower, request.upper,
                    request.initialize, request.replayHistory, request.replayMessages, filterOptions) : request;
        final PromptOptions prompt = effectivePrompt().withFocusSelf(snapshot.filters.mode == SummaryFilter.Mode.FOCUS_SELF);
        summaryRange = snapshot;
        summaryHistory = null;
        sourceMessages = null;
        summaryConfig = null;
        summaryPrompt = prompt;
        loadSettings(config -> startSummary(config, prompt, snapshot));
    }

    private void startSummary(AiSummarySettings.Config config, PromptOptions prompt, RangeRequest request) {
        if (closed || fragment.isFinished || !checkAccountOwner()) return;
        if (isSummaryAccessRevoked()) {
            showSelection();
            return;
        }
        cancelWork();
        sourceMessages = null;
        coverageNote = null;
        summaryHistory = null;
        completionNotice = null;
        resultCommitted = false;
        viewingCachedResult = false;
        historyRecord = null;
        historySaveNotice = null;
        historySavePending = false;
        historySaveFailed = false;
        summaryFiltered = request.filters != null && request.filters.hasFilters();
        sourceSnapshotInvalid = false;
        summaryRange = request;
        summaryPrompt = prompt;
        summaryConfig = config;
        String settingsError = AiSummarySettings.validate(config);
        if (settingsError != null) {
            showError(settingsError);
            return;
        }
        try {
            AiSummaryPrompt.dataBudget(prompt, config.inputCharacterBudget, config.maxOutputTokens);
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            return;
        }
        completionToken = new SummaryStateStore.CompletionToken();
        summaryStartedAt = SystemClock.elapsedRealtime();
        lastPartialUpdateAt = 0;
        final int generation = operation;
        clearContent();
        progressStatus = addText("正在读取文字消息…", true);
        progressElapsed = addText("已耗时 0 秒", false);
        addText(request.mode == RangeMode.TODAY ? "读取今天 00:00 起的文字消息。"
                : request.mode == RangeMode.RECENT || request.initialize ? "读取最近 " + request.count + " 条文字消息。"
                : request.mode == RangeMode.REPLAY ? "重做已记录的范围，不改变增量进度。"
                : "从固定下界开始读取下一批，最多 " + request.count + " 条历史消息；只总结文字。", false);
        addAction("取消并返回", this::showSelection);
        startProgressTicker(generation);
        if (request.replayHistory != null) {
            onHistoryLoaded(request.replayHistory, request.replayMessages, generation);
            return;
        }
        historyLoader = new SummaryHistoryLoader(account, dialogId, topicId);
        SummaryHistoryLoader.Callback callback = new SummaryHistoryLoader.Callback() {
            @Override public void onLoaded(SummaryHistoryLoader.Result result) {
                onHistoryLoaded(result, null, generation);
            }
            @Override public void onProgress(int scanned, int textCount) {
                if (active(generation) && progressStatus != null) {
                    progressStatus.setText("正在读取：已扫描 " + scanned + " 条，收集 " + textCount + " 条文字（总量待定）");
                }
            }
            @Override public void onError(String error) {
                if (active(generation)) showError(error);
            }
        };
        loadRange(historyLoader, request, callback);
    }

    private void loadRange(SummaryHistoryLoader loader, RangeRequest request, SummaryHistoryLoader.Callback callback) {
        if (request.mode == RangeMode.TODAY) {
            loader.loadToday(callback);
        } else if (request.mode == RangeMode.SINCE && !request.initialize && request.upper < 0) {
            loader.loadSince(request.lower, request.count, callback);
        } else if (request.mode == RangeMode.UNREAD) {
            loader.loadUnread(request.lower, request.upper, request.count, callback);
        } else if (request.mode == RangeMode.REPLAY || request.mode == RangeMode.SINCE && !request.initialize) {
            loader.loadRange(request.lower, request.upper, request.count, callback);
        } else {
            loader.loadRecent(request.count, callback);
        }
    }

    private void onHistoryLoaded(SummaryHistoryLoader.Result result, ArrayList<SummaryMessage> replay, int generation) {
        if (!active(generation)) return;
        historyLoader = null;
        summaryHistory = result;
        ArrayList<SummaryMessage> rawSources = new ArrayList<>(replay == null ? result.messages : replay);
        for (SummaryMessage source : rawSources) {
            SourceUpdate update = pendingSourceUpdates.get(source.id);
            if (update != null && update.changed(source)) {
                invalidateSources("读取期间原消息已变化，本次原文快照已过期。请重新读取消息。", false);
                return;
            }
        }
        pendingSourceUpdates.clear();
        coverageNote = result.coverageNote;
        SummaryFilter.Result filtered = SummaryFilter.apply(rawSources, ownerId, summaryRange.filters);
        sourceMessages = filtered.messages;
        summaryFiltered = filtered.filtered;
        if (!filtered.coverageNote.isEmpty()) coverageNote += "\n" + filtered.coverageNote;
        if (summaryRange.replayHistory != null) {
            coverageNote += summaryRange.mode == RangeMode.REPLAY
                    ? "\n本次重做复用本面板已读取的原文快照，未重新读取消息，不改变已保存进度。"
                    : "\n本次重试使用相同的原文快照，未重新读取消息。";
        }
        if (isSummaryAccessRevoked()) {
            onAccessRevoked();
            return;
        }
        if (sourceMessages.isEmpty()) {
            completeSummary(summaryFiltered ? "本次范围内没有匹配当前条件的文字消息，未调用模型；通用增量进度保持不变。"
                    : "本批没有可总结的文字消息。已扫描 " + result.scannedMessageCount
                    + " 条历史消息，未调用模型。", generation);
            return;
        }
        clearContent();
        progressStatus = addText("正在生成话题、结论与待办…", true);
        progressElapsed = addText("已耗时 " + Math.max(0L,
                (SystemClock.elapsedRealtime() - summaryStartedAt) / 1000) + " 秒", false);
        addText("已读取 " + sourceMessages.size() + " 条文字消息。", false);
        addText(coverageNote, false);
        requestStatus = addText("等待模型接口响应…", false);
        addRequestInputsAction();
        addText("手机上的本地模型可能需要一些时间。保持 MNN Chat API 服务运行。", false);
        addText("请保持 Telegram 在前台；关闭面板或离开页面会取消总结。", false);
        partialLabel = addText("生成中，尚未完成或校验", true);
        partialLabel.setVisibility(View.GONE);
        partialAnswer = addText("", false);
        partialAnswer.setTextColor(color(Theme.key_dialogTextBlack));
        partialAnswer.setLinksClickable(false);
        partialAnswer.setVisibility(View.GONE);
        addAction("取消并返回", this::showSelection);
        summaryInferenceStarted = true;
        client = new AiSummaryClient();
        client.summarize(summaryConfig, sourceMessages, summaryPrompt, new AiSummaryClient.Callback() {
            @Override public void onProgress(AiSummaryClient.Progress progress) {
                if (active(generation)) updateSummaryProgress(progress);
            }
            @Override public void onPartial(String text) {
                if (active(generation)) updatePartialAnswer(text, generation);
            }
            @Override public void onRequestInput(AiSummaryClient.RequestInput input) {
                if (active(generation)) recordRequestInput(input);
            }
            @Override public void onRequestStatus(AiSummaryClient.RequestStatus status) {
                if (active(generation)) {
                    if (status.phase == AiSummaryClient.RequestPhase.SENDING) beginStreamingRequest();
                    latestRequestStatus = status;
                    modelRequestStartedAt = SystemClock.elapsedRealtime() - status.elapsedMs;
                    updateRequestStatusText();
                }
            }
            @Override public void onSuccess(String summary) {
                if (active(generation)) {
                    client = null;
                    completeSummary(summary, generation);
                }
            }
            @Override public void onError(String error) {
                if (active(generation)) showError(error);
            }
        });
    }

    private void completeSummary(String summary, int generation) {
        if (!active(generation)) return;
        stopProgressUpdates();
        streamingDraft = null;
        summaryInferenceStarted = false;
        // Archive the validated model result independently of the incremental cursor commit.
        // Once accepted, closing this sheet must not cancel the queued history write.
        beginHistorySave(summary);
        if (!summaryHistory.complete) {
            completionNotice = "摘要已完成，但只覆盖部分范围；未更新增量进度。";
            showResult(summary);
            return;
        }
        if (summaryRange.mode == RangeMode.REPLAY) {
            completionNotice = "重做完成；已保存的增量进度和原始恢复范围保持不变。";
            lastSuccessfulHistory = summaryHistory;
            lastSuccessfulSources = new ArrayList<>(sourceMessages);
            cacheCompletedResult(summary);
            showResult(summary);
            return;
        }
        final SummaryHistoryLoader.Result history = summaryHistory;
        final RangeRequest range = summaryRange;
        final PromptOptions prompt = summaryPrompt;
        final ArrayList<SummaryMessage> sources = new ArrayList<>(sourceMessages);
        final SummaryStateStore.CompletionToken token = completionToken;
        final boolean advance = range.mode == RangeMode.SINCE && !summaryFiltered && history.coveredThroughId > 0;
        clearContent();
        addText("正在保存本批完成记录…", true);
        addText(advance ? "完整结果和覆盖记录确认后才更新增量进度。摘要正文及原文不会写入该进度记录。"
                : "只保存本批完成范围，通用增量进度保持不变。摘要正文及原文不会写入该进度记录。", false);
        addText("返回时会重新读取进度；如果记录已经提交，返回不会撤销该进度。", false);
        if (!requestInputs.isEmpty()) addRequestInputsAction();
        addAction("返回范围选择", this::showSelection);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameAccountOwner()) {
                AndroidUtilities.runOnUIThread(this::dismiss);
                return;
            }
            try {
                boolean committed = SummaryStateStore.recordSuccess(account, ownerId, dialogId, topicId,
                        range.expectedCursor, history.lowerExclusiveId, history.coveredThroughId,
                        history.complete, advance, sources.size(), summary, promptRevision(prompt), token);
                if (!committed) return;
                SummaryStateStore.State state = SummaryStateStore.load(account, ownerId, dialogId, topicId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    summaryState = state;
                    resultCommitted = true;
                    lastSuccessfulHistory = history;
                    lastSuccessfulSources = sources;
                    completionNotice = advance ? "本批完整完成，增量进度已保存。"
                            : "本批完成记录已保存，增量进度保持不变。";
                    cacheCompletedResult(summary);
                    showResult(summary);
                });
            } catch (RuntimeException ignored) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    summaryState = null;
                    completionNotice = "摘要已生成，但无法确认完成记录已保存。返回范围选择后重新读取进度，不能把本批视为已补齐。";
                    showResult(summary);
                });
            }
        });
    }

    private static String promptRevision(PromptOptions prompt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((prompt.templateId + "\n"
                    + prompt.templateVersion + "\n" + prompt.builtinRulesVersion + "\n"
                    + prompt.customInstructions + "\nfocusSelf=" + prompt.focusSelf).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format(Locale.US, "%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void openHistory() {
        if (!checkAccountOwner() || fragment.isFinished || fragment.getParentActivity() == null) return;
        SummaryHistoryActivity history = new SummaryHistoryActivity(account, dialogId, topicId == 0 ? -1 : topicId);
        dismiss();
        fragment.presentFragment(history);
    }

    private void beginHistorySave(String summary) {
        if (sourceMessages == null || sourceMessages.isEmpty() || summaryHistory == null) return;
        try {
            ArrayList<SummarySourceReference> references = new ArrayList<>();
            for (SummaryMessage source : sourceMessages) references.add(SummarySourceReference.from(source));
            org.telegram.tgnet.TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            String title = chat == null || chat.title == null ? "聊天 " + (-dialogId) : chat.title;
            RangeMode mode = summaryRange == null ? RangeMode.RECENT : summaryRange.mode;
            String range = mode == RangeMode.TODAY ? "当日文字消息"
                    : mode == RangeMode.SINCE ? "上次总结之后"
                    : mode == RangeMode.UNREAD ? "进入聊天时的未读消息"
                    : mode == RangeMode.REPLAY ? "重做已记录范围" : "最近 N 条文字消息";
            historyRecord = new SummaryHistoryStore.Record(UUID.randomUUID().toString(), dialogId, topicId,
                    System.currentTimeMillis(), title, range, coverageNote == null ? "" : coverageNote,
                    summaryPrompt == null ? "通用总结" : PromptOptions.templateLabel(summaryPrompt.templateId),
                    summaryPrompt == null ? "" : summaryPrompt.customInstructions,
                    summaryConfig == null ? "" : summaryConfig.model, summary, !summaryHistory.complete, references);
            persistHistory(historyRecord);
        } catch (RuntimeException error) {
            historySaveNotice = historyFailureNotice(error);
            historySaveFailed = true;
            historySavePending = false;
        }
    }

    private void persistHistory(SummaryHistoryStore.Record record) {
        if (record == null || historySavePending || !sameAccountOwner()) return;
        historySavePending = true;
        historySaveFailed = false;
        historySaveNotice = "正在加密保存总结历史…";
        updateHistorySaveViews();
        Utilities.globalQueue.postRunnable(() -> {
            String failure = null;
            try {
                SummaryHistoryStore.save(account, ownerId, record);
            } catch (RuntimeException error) {
                failure = historyFailureNotice(error);
            }
            final String errorNotice = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (closed || !sameAccountOwner() || historyRecord != record) return;
                historySavePending = false;
                historySaveFailed = errorNotice != null;
                historySaveNotice = errorNotice == null ? "已保存到本机总结历史，关闭窗口后仍可查看。" : errorNotice;
                updateHistorySaveViews();
            });
        });
    }

    private void updateHistorySaveViews() {
        if (historySaveStatus != null) historySaveStatus.setText(historySaveNotice);
        if (historySaveRetry != null) historySaveRetry.setVisibility(historySaveFailed ? View.VISIBLE : View.GONE);
    }

    private static String historyFailureNotice(RuntimeException error) {
        String detail = error.getMessage();
        return "摘要未保存到历史，当前窗口仍可查看结果。"
                + (detail == null || detail.isEmpty() ? "请重试保存。" : detail);
    }

    private void continueBatch() {
        if (summaryHistory == null || !summaryHistory.complete || !summaryHistory.hasMore) return;
        if (summaryRange.mode == RangeMode.SINCE && (summaryState == null
                || summaryState.cursor != summaryHistory.coveredThroughId)) {
            summaryState = null;
            showSelection();
            return;
        }
        int upper = summaryRange.mode == RangeMode.REPLAY ? summaryRange.upper : summaryHistory.upperInclusiveId;
        RangeRequest next = new RangeRequest(summaryRange.mode, summaryRange.count,
                summaryState == null ? summaryRange.expectedCursor : summaryState.cursor,
                summaryHistory.coveredThroughId, upper, false, null, null, summaryRange.filters);
        startSummary(next);
    }

    private void showError(String message) {
        final String unfinished = streamingDraft;
        final String unfinishedStage = streamingRequestStage;
        final boolean allowPlainRetry = summaryInferenceStarted && summaryConfig != null && summaryConfig.stream;
        final AiSummarySettings.Config failedConfig = summaryConfig;
        final PromptOptions failedPrompt = summaryPrompt;
        final RangeRequest originalRange = summaryRange;
        final RangeRequest failedRange = originalRange != null && summaryHistory != null && sourceMessages != null
                ? new RangeRequest(originalRange.mode, originalRange.count, originalRange.expectedCursor,
                    originalRange.lower, originalRange.upper, originalRange.initialize, summaryHistory,
                    originalRange.replayMessages == null ? summaryHistory.messages : originalRange.replayMessages,
                    originalRange.filters)
                : originalRange;
        cancelWork(true);
        clearContent();
        addText("暂时无法完成总结", true);
        addText(message, false);
        if (coverageNote != null) {
            addText(coverageNote, false);
        }
        if (unfinished != null && !unfinished.isEmpty()) {
            addText((unfinishedStage == null ? "当前模型请求" : unfinishedStage)
                    + " · 以下内容未完成，尚未通过最终引用校验", true);
            TextView partial = addText(unfinished, false);
            partial.setLinksClickable(false);
        }
        if (!requestInputs.isEmpty()) addRequestInputsAction();
        addAction("重试", () -> {
            if (failedRange == null) startSummary();
            else startSummary(failedRange);
        });
        if (allowPlainRetry) {
            addAction("改用普通模式重新运行", () -> startSummary(new AiSummarySettings.Config(
                    failedConfig.baseUrl, failedConfig.model, failedConfig.apiKey, failedConfig.maxOutputTokens,
                    false, failedConfig.inputCharacterBudget), failedPrompt, failedRange));
            addText("仅本次改用普通模式，不修改已保存设置；已成功读取的原文快照保持不变。", false);
        }
        addAction("MNN API 设置", this::showSettings);
        addAction("返回范围选择", this::showSelection);
    }

    private void showResult(String summary) {
        stopProgressUpdates();
        streamingDraft = null;
        summaryInferenceStarted = false;
        clearContent();
        addText((viewingCachedResult ? "历史结果" : summaryHistory != null && !summaryHistory.complete ? "部分覆盖" : "已生成") + " · 生成耗时 " + Math.max(0L,
                (SystemClock.elapsedRealtime() - summaryStartedAt) / 1000) + " 秒", false);
        if (completionNotice != null) addText(completionNotice, true);
        if (!requestInputs.isEmpty()) addRequestInputsAction();
        if (!viewingCachedResult && historySaveNotice != null) {
            historySaveStatus = addText(historySaveNotice, false);
            historySaveRetry = addAction("重试保存到总结历史", () -> {
                if (historyRecord == null) beginHistorySave(summary);
                else persistHistory(historyRecord);
                updateHistorySaveViews();
            });
            updateHistorySaveViews();
        }
        addAction(topicId == 0 ? "查看本聊天的总结历史" : "查看本话题的总结历史", this::openHistory);
        if (summaryPrompt != null) {
            addText("总结方向：" + PromptOptions.templateLabel(summaryPrompt.templateId), false);
            if (!summaryPrompt.customInstructions.isEmpty()) {
                addText("生成时补充要求：" + summaryPrompt.customInstructions, false);
            }
        }
        if (summaryRange != null && summaryRange.filters != null) {
            addText("生成时消息选择：" + filterLabel(summaryRange.filters), false);
        }
        if (summaryFiltered) addText("本次使用了实际消息筛选，通用增量进度未推进。", true);
        if (viewingCachedResult && summaryConfig != null) {
            addText("生成时请求模型：" + (summaryConfig.model.isEmpty() ? "服务当前模型（未指定名称）" : summaryConfig.model)
                    + "；上下文预算 " + summaryConfig.inputCharacterBudget + " 字符；输出上限 "
                    + summaryConfig.maxOutputTokens + " token。请求名称不代表已核实的实际模型版本。", false);
        }
        addText(coverageNote, false);
        addText("点击 [m数字] 先核验并预览原文，再选择跳回群聊；请结合原文核对模型生成的结论。", false);
        TextView result = addText(linkSources(summary), false);
        result.setTextColor(color(Theme.key_dialogTextBlack));
        result.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        result.setMovementMethod(LinkMovementMethod.getInstance());
        result.setLinksClickable(true);
        if (summaryHistory != null && summaryHistory.complete && sourceMessages != null && !sourceMessages.isEmpty()
                && (resultCommitted || viewingCachedResult || summaryRange.mode == RangeMode.REPLAY)) {
            addAction("追问本次消息", this::showQuestions);
            addText("追问仅依据这次实际发送文字在生成时的原文快照，最多 5 轮；范围外消息及未观察到的后续修改不会自动同步。点击引用仍会重新核验原消息。", false);
        }
        if (!viewingCachedResult && summaryHistory != null && summaryHistory.complete && summaryHistory.hasMore
                && !(summaryRange.mode == RangeMode.SINCE && summaryFiltered)
                && (summaryRange.mode == RangeMode.SINCE || summaryRange.mode == RangeMode.UNREAD
                    ? resultCommitted : summaryRange.mode == RangeMode.REPLAY
                        && summaryRange.upper > summaryHistory.coveredThroughId)) {
            addAction("继续下一批", this::continueBatch);
        }
        if (viewingCachedResult && summaryHistory != null && sourceMessages != null) {
            final RangeRequest regenerate = new RangeRequest(RangeMode.REPLAY, recentCount,
                    summaryRange.expectedCursor, summaryHistory.lowerExclusiveId, summaryHistory.coveredThroughId,
                    false, summaryHistory, summaryHistory.messages);
            addAction("按当前设置和方向重新生成", () -> startSummary(regenerate));
            addText("重新调用模型，使用生成时范围的原始文字快照，并应用当前筛选；若需最新消息，请重新选择范围。", false);
        }
        addAction("重新选择范围", this::showSelection);
    }

    private CharSequence linkSources(String summary) {
        final int resultOperation = operation;
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
            final int reference = index + 1;
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    if (!active(resultOperation) || sourceSnapshotInvalid) {
                        return;
                    }
                    showSourcePreview(source, reference);
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
        return addText(content, text, title);
    }

    private TextView addText(LinearLayout parent, CharSequence text, boolean title) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, title ? 16 : 14);
        view.setTextColor(color(title ? Theme.key_dialogTextBlack : Theme.key_dialogTextGray));
        view.setLineSpacing(dp(3), 1f);
        if (title) {
            view.setTypeface(AndroidUtilities.bold());
        }
        parent.addView(view, LayoutHelper.createLinear(-1, -2, 0, title ? 12 : 6, 0, 6));
        return view;
    }

    private TextView addAction(String label, Runnable action) {
        return addAction(content, label, action);
    }

    private TextView addAction(LinearLayout parent, String label, Runnable action) {
        final int actionOperation = operation;
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
            if (!closed && actionOperation == operation) {
                action.run();
            }
        });
        parent.addView(button, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
        return button;
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }
}
