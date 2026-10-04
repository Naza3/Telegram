/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui.Components;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.SummaryForegroundService;
import org.telegram.messenger.ai.AiSummaryClient;
import org.telegram.messenger.ai.AiSummaryPrompt;
import org.telegram.messenger.ai.AiSummarySettings;
import org.telegram.messenger.ai.PromptOptions;
import org.telegram.messenger.ai.SummaryFilter;
import org.telegram.messenger.ai.SummaryHistoryLoader;
import org.telegram.messenger.ai.SummaryHistoryStore;
import org.telegram.messenger.ai.SummaryMessage;
import org.telegram.messenger.ai.SummaryPublishStore;
import org.telegram.messenger.ai.SummarySourceReference;
import org.telegram.messenger.ai.SummaryStateStore;
import org.telegram.messenger.ai.SummaryTaskCheckpoint;
import org.telegram.tgnet.TLRPC;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

/** UI-thread task owner. No Activity, Fragment, Context or View is retained here. */
public final class SummaryTaskController implements NotificationCenter.NotificationCenterDelegate {
    private static final SummaryTaskController[] INSTANCES = new SummaryTaskController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long RETAIN_FINISHED_MS = 30 * 60 * 1000L;
    private static final int MAX_RETAINED_INPUT_CHARACTERS = 128 * 64000;
    public enum State { LOADING, GENERATING, SAVING, SUCCESS, ERROR, CANCELLED, INVALIDATED }
    public interface Listener { void onSummaryTaskChanged(Session task); }

    public static final class Input {
        public final String stage;
        public final AiSummaryClient.RequestInput input;
        Input(String stage, AiSummaryClient.RequestInput input) { this.stage = stage; this.input = input; }
    }

    public static final class Session {
        public final String taskId = UUID.randomUUID().toString();
        public final int account;
        public final long ownerId, dialogId, topicId;
        public final long startedAtMillis = System.currentTimeMillis();
        public final long startedAtElapsed = SystemClock.elapsedRealtime();
        GroupSummarySheet.RangeRequest range;
        public final AiSummarySettings.Config config;
        public final PromptOptions prompt;
        public State state = State.LOADING;
        public String stage = "正在读取文字消息…";
        public String requestStage = "当前模型请求";
        public String draft, summary, error, coverage, completionNotice, historyNotice;
        public int scanned, textCount, requestNumber;
        public long requestStartedAt;
        public long finishedAtElapsed;
        public long firstVisibleBodyElapsedMs = -1;
        public boolean filtered, committed, historySaved, inferenceStarted;
        public SummaryHistoryLoader.Result history;
        public ArrayList<SummaryMessage> sources;
        public SummaryHistoryStore.Record record;
        public SummaryStateStore.State savedState;
        public AiSummaryClient.Progress progress;
        public AiSummaryClient.RequestStatus requestStatus;
        public final ArrayList<Input> inputs = new ArrayList<>();
        /** Terminal metrics for actual model requests; usage is reported by the service, never estimated. */
        public final ArrayList<AiSummaryClient.RequestMetrics> requestMetrics = new ArrayList<>();
        private final HashMap<Integer, SourceChange> pendingChanges = new HashMap<>();
        private final SummaryStateStore.CompletionToken completion = new SummaryStateStore.CompletionToken();
        private int inputCharacters;
        private boolean historyLoaded;

        Session(int account, long ownerId, long dialogId, long topicId, GroupSummarySheet.RangeRequest range,
                AiSummarySettings.Config config, PromptOptions prompt) {
            this.account = account; this.ownerId = ownerId; this.dialogId = dialogId; this.topicId = topicId;
            this.range = range; this.config = config; this.prompt = prompt;
        }
        public boolean running() { return state == State.LOADING || state == State.GENERATING || state == State.SAVING; }
        public String title() {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            String title = chatTitle();
            TLRPC.TL_forumTopic topic = chat == null || topicId == 0 ? null
                    : MessagesController.getInstance(account).getTopicsController().findTopic(chat.id, topicId);
            return title + (topicId == 0 ? "" : " · " + (topic == null ? "话题 " + topicId : topic.title));
        }
        public String chatTitle() {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            return chat == null || chat.title == null ? "来源聊天" : chat.title;
        }
    }

    private static final class SourceChange {
        final String text; final int editDate; final boolean usable;
        final SourceMetadata metadata;
        SourceChange(String text, int editDate, boolean usable) {
            this(text, editDate, usable, null);
        }
        SourceChange(String text, int editDate, boolean usable, SourceMetadata metadata) {
            this.text = text; this.editDate = editDate; this.usable = usable;
            this.metadata = metadata;
        }
        boolean changed(SummaryMessage source) {
            return !usable || source.editDate != editDate || !source.text.equals(text)
                    || metadata != null && metadata.changed(source);
        }
    }

    /** Copy metadata immediately: pending notifications must not retain mutable Telegram messages. */
    private static final class SourceMetadata {
        final long senderId, topicId, replyDialogId;
        final int date, replyId;
        final boolean outgoing;
        final String quote;
        SourceMetadata(int account, long dialogId, TLRPC.Message message, boolean forum) {
            senderId = org.telegram.messenger.DialogObject.getPeerDialogId(message.from_id);
            topicId = MessageObject.getTopicId(account, message, forum);
            date = message.date;
            outgoing = message.out;
            boolean ordinaryReply = message.reply_to != null && !message.reply_to.reply_to_scheduled
                    && !message.reply_to.reply_to_ephemeral;
            replyId = ordinaryReply && message.reply_to.reply_to_msg_id > 0 ? message.reply_to.reply_to_msg_id : 0;
            replyDialogId = replyId == 0 ? 0 : message.reply_to.reply_to_peer_id == null ? dialogId
                    : org.telegram.messenger.DialogObject.getPeerDialogId(message.reply_to.reply_to_peer_id);
            quote = ordinaryReply && (message.reply_to.flags & (1 << 6)) != 0 && message.reply_to.quote_text != null
                    ? message.reply_to.quote_text : "";
        }
        boolean changed(SummaryMessage source) {
            return source.senderId != senderId || source.topicId != topicId || source.date != date
                    || source.outgoing != outgoing || source.replyToId != replyId
                    || source.replyToDialogId != replyDialogId || !source.quoteText.equals(quote);
        }
    }

    public static SummaryTaskController get(int account) {
        if (account < 0 || account >= INSTANCES.length) throw new IllegalArgumentException("无效账号");
        if (INSTANCES[account] == null) INSTANCES[account] = new SummaryTaskController(account);
        SummaryTaskController value = INSTANCES[account];
        if (value.current != null && !value.sameOwner(value.current)) value.clear();
        return value;
    }

    public static void clearOwner(int account, long ownerId) {
        if (account < 0 || account >= INSTANCES.length) return;
        SummaryTaskController controller = INSTANCES[account];
        if (controller != null && controller.current != null && controller.current.ownerId == ownerId) {
            controller.clear();
            controller.listeners.clear();
        }
    }

    private final int account;
    private final ArrayList<WeakReference<Listener>> listeners = new ArrayList<>();
    private volatile Session current;
    private SummaryHistoryLoader loader;
    private AiSummaryClient client;
    private Runnable expiry;

    private SummaryTaskController(int account) {
        this.account = account;
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.messagesDeleted);
        center.addObserver(this, NotificationCenter.replaceMessagesObjects);
        center.addObserver(this, NotificationCenter.chatInfoDidLoad);
        center.addObserver(this, NotificationCenter.updateInterfaces);
        center.addObserver(this, NotificationCenter.appDidLogout);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
    }

    public Session current() {
        if (current != null && !sameOwner(current)) clear();
        return current;
    }

    public void addListener(Listener listener) {
        removeListener(listener);
        listeners.add(new WeakReference<>(listener));
    }

    public void removeListener(Listener listener) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            Listener found = listeners.get(i).get();
            if (found == null || found == listener) listeners.remove(i);
        }
    }

    boolean start(long dialogId, long topicId, GroupSummarySheet.RangeRequest range,
            AiSummarySettings.Config config, PromptOptions prompt) {
        if (current != null && current.running()) return false;
        clear();
        long owner = UserConfig.getInstance(account).getClientUserId();
        if (owner <= 0 || UserConfig.selectedAccount != account) return false;
        Session task = current = new Session(account, owner, dialogId, topicId, range, config, prompt);
        String error = AiSummarySettings.validate(config);
        if (error == null) {
            try { AiSummaryPrompt.dataBudget(prompt, config.inputCharacterBudget, config.maxOutputTokens); }
            catch (IllegalArgumentException failure) { error = failure.getMessage(); }
        }
        if (error != null) { fail(task, error); return true; }
        if (range.mode == GroupSummarySheet.RangeMode.SELECTED && (range.replayHistory == null
                || !range.replayHistory.matchesSelectedScope(account, owner, dialogId, topicId))) {
            fail(task, "选中的消息快照已失效，请返回聊天重新选择。");
            return true;
        }
        if (!canRead(task)) { invalidate(task, "来源聊天已不可访问，请重新选择。", true); return true; }
        if (!SummaryForegroundService.start(account, owner, task.taskId,
                () -> { if (current == task) cancel(); }, message -> fail(task, message))) {
            fail(task, "无法启动后台总结服务，请保持应用在前台并重试。");
            return true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (sameOwner(task)) SummaryTaskCheckpoint.begin(account, task.ownerId, task.taskId,
                        task.dialogId, task.topicId, task.startedAtMillis);
            } catch (RuntimeException ignored) { /* A checkpoint failure must not break inference. */ }
        });
        publish();
        if (!active(task)) return true;
        if (range.replayHistory != null) {
            loaded(task, range.replayHistory,
                    range.mode == GroupSummarySheet.RangeMode.SELECTED ? null : range.replayMessages);
            return true;
        }
        loader = new SummaryHistoryLoader(account, dialogId, topicId);
        SummaryHistoryLoader.Callback callback = new SummaryHistoryLoader.Callback() {
            @Override public void onLoaded(SummaryHistoryLoader.Result result) { loaded(task, result, null); }
            @Override public void onError(String message) { if (task.state == State.LOADING && !task.historyLoaded) fail(task, message); }
            @Override public void onProgress(int scanned, int count) {
                if (!active(task) || task.state != State.LOADING || task.historyLoaded) return;
                task.scanned = scanned; task.textCount = count;
                task.stage = "正在读取：已扫描 " + scanned + " 条，收集 " + count + " 条文字";
                publish();
            }
        };
        switch (range.mode) {
            case TODAY: loader.loadToday(callback); break;
            case UNREAD: loader.loadUnread(range.lower, range.upper, range.count, callback); break;
            case REPLAY: loader.loadRange(range.lower, range.upper, range.count, callback); break;
            case SINCE:
                if (range.initialize) loader.loadRecent(range.count, callback);
                else if (range.upper < 0) loader.loadSince(range.lower, range.count, callback);
                else loader.loadRange(range.lower, range.upper, range.count, callback);
                break;
            default: loader.loadRecent(range.count, callback);
        }
        return true;
    }

    private void loaded(Session task, SummaryHistoryLoader.Result history, ArrayList<SummaryMessage> replay) {
        if (!active(task) || task.state != State.LOADING || task.historyLoaded) return;
        task.historyLoaded = true;
        loader = null;
        task.history = history;
        ArrayList<SummaryMessage> raw = new ArrayList<>(replay == null ? history.messages : replay);
        for (SummaryMessage source : raw) {
            SourceChange changed = task.pendingChanges.get(source.id);
            if (changed != null && changed.changed(source)) {
                invalidate(task, "读取期间来源消息已变化，请重新读取。", false); return;
            }
        }
        task.pendingChanges.clear();
        if (task.range.includePublished) {
            filterLoaded(task, history, raw, replay != null, 0);
            return;
        }
        task.stage = "正在核对已发布总结…";
        publish();
        if (!active(task)) return;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (current != task || task.completion.isCancelled() || !sameOwner(task)) return;
                SummaryFilter.ExclusionResult exclusion = SummaryFilter.excludePublished(raw,
                        SummaryPublishStore.confirmedMessages(account, task.ownerId, task.dialogId));
                AndroidUtilities.runOnUIThread(() -> {
                    if (active(task)) filterLoaded(task, history, new ArrayList<>(exclusion.messages),
                            replay != null, exclusion.excludedCount);
                });
            } catch (RuntimeException ignored) {
                AndroidUtilities.runOnUIThread(() -> fail(task, "无法核对已发布总结，请重试或明确选择包含本人已发布的 AI 总结。"));
            }
        });
    }

    private void filterLoaded(Session task, SummaryHistoryLoader.Result history,
            ArrayList<SummaryMessage> raw, boolean replay, int excludedCount) {
        if (!active(task)) return;
        SummaryFilter.Result filtered = SummaryFilter.apply(raw, task.ownerId, task.range.filters);
        task.sources = filtered.messages;
        task.filtered = filtered.filtered || excludedCount > 0;
        task.coverage = history.coverageNote + (filtered.coverageNote.isEmpty() ? "" : "\n" + filtered.coverageNote);
        if (!task.range.includePublished) task.coverage += "\n原范围文字 " + history.messages.size()
                + " 条；排除已确认发布的本人 AI 总结 " + excludedCount + " 条；筛选后实际输入 " + task.sources.size() + " 条。";
        if (task.range.mode == GroupSummarySheet.RangeMode.SELECTED) {
            task.coverage += "\n仅总结手动选中的消息，不补抓历史、不改变增量进度；原文仅在本机任务内存中保留。";
        } else if (replay) task.coverage += "\n使用上次读取的原文快照；原文仅在本机任务内存中保留。";
        if (!canRead(task)) { invalidate(task, "来源聊天已不可访问。", true); return; }
        if (task.sources.isEmpty()) {
            complete(task, task.filtered ? "本次范围没有匹配的文字，未调用模型；通用增量进度保持不变。"
                    : "本批没有可总结的文字。已扫描 " + history.scannedMessageCount + " 条历史消息，未调用模型。");
            return;
        }
        task.state = State.GENERATING;
        task.inferenceStarted = true;
        task.stage = "正在按核心要求生成总结…";
        publish();
        if (!active(task)) return;
        client = new AiSummaryClient();
        client.summarize(task.config, task.sources, task.prompt, new AiSummaryClient.Callback() {
            @Override public void onProgress(AiSummaryClient.Progress value) {
                if (!active(task) || task.state != State.GENERATING) return;
                task.progress = value;
                task.stage = value.stage == AiSummaryClient.Stage.SOURCE
                        ? "来源分段：已完成 " + value.completed + " / " + value.total
                        : value.stage == AiSummaryClient.Stage.MERGE
                            ? "第 " + value.mergeRound + " 轮合并：已完成 " + value.completed + " / " + value.total
                            : "正在校验总结结果…";
                publish();
            }
            @Override public void onRequestStatus(AiSummaryClient.RequestStatus value) {
                if (!active(task) || task.state != State.GENERATING) return;
                if (value.phase == AiSummaryClient.RequestPhase.SENDING) {
                    task.requestNumber++;
                    task.draft = null;
                    task.requestStage = requestStage(task.progress);
                }
                task.requestStatus = value;
                task.requestStartedAt = SystemClock.elapsedRealtime() - value.elapsedMs;
                publish();
            }
            @Override public void onRequestInput(AiSummaryClient.RequestInput value) {
                if (!active(task) || task.state != State.GENERATING) return;
                if (task.inputs.size() < 128 && value.inputCharacters >= 0
                        && value.inputCharacters <= MAX_RETAINED_INPUT_CHARACTERS - task.inputCharacters) {
                    task.inputs.add(new Input(task.requestStage, value));
                    task.inputCharacters += value.inputCharacters;
                }
                publish();
            }
            @Override public void onRequestMetrics(AiSummaryClient.RequestMetrics value) {
                if (!active(task) || task.state != State.GENERATING || value == null
                        || value.requestIndex <= 0 || value.requestIndex > 128
                        || task.requestMetrics.size() >= 128) return;
                for (AiSummaryClient.RequestMetrics previous : task.requestMetrics) {
                    if (previous.requestIndex == value.requestIndex) return;
                }
                task.requestMetrics.add(value);
                publish();
            }
            @Override public void onPartial(String value) {
                if (!active(task) || task.state != State.GENERATING) return;
                task.draft = value;
                if (task.firstVisibleBodyElapsedMs < 0 && value != null && !value.isEmpty()) {
                    task.firstVisibleBodyElapsedMs = SystemClock.elapsedRealtime() - task.startedAtElapsed;
                }
                publish();
            }
            @Override public void onSuccess(String value) { if (task.state == State.GENERATING) complete(task, value); }
            @Override public void onError(String message) { if (task.state == State.GENERATING) fail(task, message); }
        });
    }

    private static String requestStage(AiSummaryClient.Progress progress) {
        if (progress == null) return "当前模型请求";
        int count = Math.max(1, Math.min(progress.total, progress.completed + 1));
        return progress.stage == AiSummaryClient.Stage.MERGE
                ? "第 " + progress.mergeRound + " 轮合并 · 第 " + count + " / " + progress.total + " 段"
                : "来源分段 · 第 " + count + " / " + Math.max(1, progress.total) + " 段";
    }

    private void complete(Session task, String summary) {
        if (!active(task) || task.state == State.SAVING) return;
        client = null;
        task.summary = summary;
        if (task.firstVisibleBodyElapsedMs < 0 && task.inferenceStarted) {
            task.firstVisibleBodyElapsedMs = SystemClock.elapsedRealtime() - task.startedAtElapsed;
        }
        task.draft = null;
        task.state = State.SAVING;
        task.stage = "正在保存总结…";
        if (task.sources != null && !task.sources.isEmpty()) {
            ArrayList<SummarySourceReference> sources = new ArrayList<>();
            for (SummaryMessage source : task.sources) sources.add(SummarySourceReference.from(source));
            task.record = new SummaryHistoryStore.Record(UUID.randomUUID().toString(), task.dialogId, task.topicId,
                    System.currentTimeMillis(), task.chatTitle(), rangeLabel(task.range.mode), task.coverage, "",
                    task.prompt.customInstructions, task.config.model, summary, !task.history.complete, sources,
                    task.prompt.builtinRulesVersion < 4);
        }
        publish();
        if (!active(task)) return;
        final SummaryHistoryLoader.Result history = task.history;
        final GroupSummarySheet.RangeRequest range = task.range;
        final int sourceCount = task.sources.size();
        final SummaryHistoryStore.Record record = task.record;
        Utilities.globalQueue.postRunnable(() -> {
            if (current != task || task.completion.isCancelled()) return;
            if (!sameOwner(task)) {
                AndroidUtilities.runOnUIThread(() -> { if (current == task) clear(); }); return;
            }
            boolean saved = false, committed = false;
            String historyNotice = null;
            if (record != null) {
                try { SummaryHistoryStore.save(account, task.ownerId, record); saved = true; }
                catch (RuntimeException ignored) { historyNotice = "摘要未保存到本机历史，可重试保存。"; }
            }
            SummaryStateStore.State state = null;
            String completionNotice;
            if (record != null && !saved) completionNotice = "摘要已生成但历史未保存，本批增量进度未推进。重试保存仅补存历史。";
            else if (!history.complete) completionNotice = "仅覆盖部分范围，增量进度未推进。";
            else if (range.mode == GroupSummarySheet.RangeMode.SELECTED) completionNotice = "选中消息总结完成，增量进度保持不变。";
            else if (range.mode == GroupSummarySheet.RangeMode.REPLAY) completionNotice = "重做完成，增量进度保持不变。";
            else {
                boolean advance = range.mode == GroupSummarySheet.RangeMode.SINCE && !task.filtered
                        && history.coveredThroughId > 0;
                try {
                    committed = SummaryStateStore.recordSuccess(account, task.ownerId, task.dialogId, task.topicId,
                            range.expectedCursor, history.lowerExclusiveId, history.coveredThroughId,
                            true, advance, sourceCount, summary, promptRevision(task.prompt), task.completion);
                    state = SummaryStateStore.load(account, task.ownerId, task.dialogId, task.topicId);
                    completionNotice = committed ? advance ? "本批完成，增量进度已保存。" : "本批完成，增量进度保持不变。"
                            : "本批完成记录未提交，返回后重新读取进度。";
                } catch (RuntimeException ignored) {
                    completionNotice = "摘要已生成，但本批完成记录保存失败；增量进度未确认。";
                }
            }
            final boolean savedResult = saved, committedResult = committed;
            final String notice = completionNotice, archiveNotice = historyNotice;
            final SummaryStateStore.State resultState = state;
            AndroidUtilities.runOnUIThread(() -> {
                if (!active(task)) return;
                task.historySaved = savedResult;
                task.historyNotice = archiveNotice == null && savedResult ? "已保存到本机总结历史。" : archiveNotice;
                task.committed = committedResult;
                task.completionNotice = notice;
                task.savedState = resultState;
                task.state = State.SUCCESS;
                task.stage = "已完成";
                terminal(task);
            });
        });
    }

    public void retryHistorySave() {
        Session task = current;
        if (task == null || task.record == null || task.state != State.SUCCESS || !sameOwner(task)) return;
        task.historyNotice = "正在重试保存历史…";
        publish();
        if (!isCurrent(task) || task.state != State.SUCCESS) return;
        final SummaryHistoryStore.Record record = task.record;
        Utilities.globalQueue.postRunnable(() -> {
            if (current != task || !sameOwner(task) || task.state != State.SUCCESS) return;
            boolean saved;
            try { SummaryHistoryStore.save(account, task.ownerId, record); saved = true; }
            catch (RuntimeException ignored) { saved = false; }
            final boolean result = saved;
            AndroidUtilities.runOnUIThread(() -> {
                if (!isCurrent(task)) return;
                task.historySaved = result;
                task.historyNotice = result ? "已保存到本机总结历史。" : "保存失败，请重试。";
                publish();
            });
        });
    }

    public void cancel() {
        Session task = current;
        if (task == null || !task.running()) return;
        stop(); task.completion.cancel();
        task.state = State.CANCELLED;
        task.stage = "已停止";
        task.draft = null;
        task.inputs.clear();
        task.sources = null;
        task.history = null;
        stripReplay(task);
        terminal(task);
    }

    public void clear() {
        Session previous = current;
        current = null;
        stop();
        if (expiry != null) { AndroidUtilities.cancelRunOnUIThread(expiry); expiry = null; }
        if (previous != null) {
            previous.completion.cancel();
            previous.inputs.clear(); previous.pendingChanges.clear();
            previous.requestMetrics.clear();
            previous.sources = null; previous.history = null; previous.draft = null;
            stripReplay(previous);
            finishCheckpoint(previous);
        }
        publish();
    }

    private void stop() {
        if (loader != null) { loader.cancel(); loader = null; }
        if (client != null) { client.cancel(); client = null; }
    }

    private void fail(Session task, String error) {
        if (!active(task)) return;
        stop(); task.completion.cancel();
        task.state = State.ERROR; task.stage = "未完成"; task.error = error;
        terminal(task);
    }

    private void invalidate(Session task, String reason, boolean revoked) {
        if (!isCurrent(task)) return;
        stop(); task.completion.cancel();
        task.state = State.INVALIDATED; task.stage = revoked ? "来源不可访问" : "来源已变化";
        task.error = reason; task.summary = null; task.draft = null;
        task.inputs.clear(); task.pendingChanges.clear(); task.sources = null; task.history = null;
        stripReplay(task);
        terminal(task);
    }

    private void terminal(Session task) {
        if (task.finishedAtElapsed == 0) task.finishedAtElapsed = SystemClock.elapsedRealtime();
        finishCheckpoint(task);
        publish();
        if (expiry != null) AndroidUtilities.cancelRunOnUIThread(expiry);
        expiry = () -> {
            if (current == task && !task.running()) clear();
        };
        AndroidUtilities.runOnUIThread(expiry, RETAIN_FINISHED_MS);
    }

    private void finishCheckpoint(Session task) {
        SummaryForegroundService.stop(account, task.ownerId, task.taskId);
        Utilities.globalQueue.postRunnable(() -> {
            try { SummaryTaskCheckpoint.finish(account, task.ownerId, task.taskId); }
            catch (RuntimeException ignored) { /* Logout or unavailable preferences: no auto-retry. */ }
        });
    }

    private boolean active(Session task) {
        return isCurrent(task) && task.running();
    }
    private boolean isCurrent(Session task) {
        if (current != task) return false;
        if (!sameOwner(task)) { clear(); return false; }
        return task.state != State.CANCELLED && task.state != State.INVALIDATED;
    }
    private boolean sameOwner(Session task) {
        return task.ownerId > 0 && UserConfig.getInstance(account).getClientUserId() == task.ownerId;
    }
    private boolean canRead(Session task) {
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-task.dialogId);
        return chat != null && !ChatObject.isKickedFromChat(chat) && chat.migrated_to == null;
    }

    private void publish() {
        Session task = current;
        if (task != null && task.running()) {
            SummaryForegroundService.update(account, task.ownerId, task.taskId, task.stage,
                    task.progress == null ? 0 : task.progress.completed,
                    task.progress == null ? 0 : task.progress.total);
        }
        ArrayList<Listener> alive = new ArrayList<>();
        for (int i = listeners.size() - 1; i >= 0; i--) {
            Listener value = listeners.get(i).get();
            if (value == null) listeners.remove(i); else alive.add(value);
        }
        for (Listener listener : alive) listener.onSummaryTaskChanged(current);
    }

    @Override public void didReceivedNotification(int id, int changedAccount, Object... args) {
        Session task = current;
        if (task == null) return;
        if (id == NotificationCenter.activeAccountChanged && UserConfig.selectedAccount != account
                || id == NotificationCenter.appDidLogout && changedAccount == account || !sameOwner(task)) { clear(); return; }
        if (changedAccount != account) return;
        if (id == NotificationCenter.chatInfoDidLoad || id == NotificationCenter.updateInterfaces) {
            if (!canRead(task)) invalidate(task, "来源权限或群资料已变化，请重新选择。", true);
        } else if (id == NotificationCenter.messagesDeleted && args.length >= 3 && !((Boolean) args[2])) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-task.dialogId);
            if (chat == null || (Long) args[1] != (ChatObject.isChannel(chat) ? chat.id : 0)) return;
            for (int messageId : (ArrayList<Integer>) args[0]) sourceChanged(task, messageId, new SourceChange("", 0, false));
        } else if (id == NotificationCenter.replaceMessagesObjects && args.length >= 2 && (Long) args[0] == task.dialogId) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-task.dialogId);
            for (MessageObject message : (ArrayList<MessageObject>) args[1]) {
                if (message == null || message.messageOwner == null || message.getDialogId() != task.dialogId) continue;
                TLRPC.Message value = message.messageOwner;
                sourceChanged(task, value.id, new SourceChange(value.message, value.edit_date,
                        SummaryHistoryLoader.isUsableText(value, org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime()),
                        new SourceMetadata(account, task.dialogId, value, chat != null && chat.forum)));
            }
        }
    }

    private void sourceChanged(Session task, int id, SourceChange change) {
        if (!isCurrent(task)) return;
        if (task.state == State.LOADING) {
            if (task.pendingChanges.size() >= 10000) { invalidate(task, "来源变化过多，请重新读取。", false); return; }
            task.pendingChanges.put(id, change);
        }
        if (task.history != null) for (SummaryMessage source : task.history.messages) {
            if (source.id == id && change.changed(source)) {
                invalidate(task, "原消息已编辑、删除或不可用，请重新读取后总结。", false); return;
            }
        }
    }

    private static void stripReplay(Session task) {
        GroupSummarySheet.RangeRequest previous = task.range;
        task.range = new GroupSummarySheet.RangeRequest(previous.mode, previous.count, previous.expectedCursor,
                previous.lower, previous.upper, previous.initialize, null, null, previous.filters, previous.includePublished);
    }

    private static String rangeLabel(GroupSummarySheet.RangeMode mode) {
        switch (mode) {
            case TODAY: return "当日文字消息";
            case SINCE: return "上次总结之后";
            case UNREAD: return "固定未读范围";
            case REPLAY: return "重做已记录范围";
            case SELECTED: return "手动选中的文字消息";
            default: return "最近 N 条文字消息";
        }
    }

    private static String promptRevision(PromptOptions prompt) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((prompt.templateId + "\n" + prompt.templateVersion
                    + "\n" + prompt.builtinRulesVersion + "\n" + prompt.customInstructions + "\nfocusSelf=" + prompt.focusSelf)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : bytes) value.append(String.format(Locale.US, "%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
