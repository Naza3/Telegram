/*
 * This file is part of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * See LICENSE for the full license text.
 */

package org.telegram.messenger.ai;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads a bounded, read-only snapshot directly from Telegram history. This deliberately does not
 * use the chat's message list or processLoadedMessages: neither opening an unread chat nor another
 * account's visible messages may change the summary's scope or read state.
 *
 * Public methods may be called from any thread; callbacks and all Run state are on the UI thread.
 * Starting another load or cancelling suppresses all callbacks from the previous load.
 */
public final class SummaryHistoryLoader {
    public static final int MAX_RECENT_COUNT = 500;
    public static final int MAX_TODAY_MESSAGES = 2000;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_SCANNED_MESSAGES = 10000;
    private static final long PAGE_TIMEOUT_MS = 45000;

    public interface Callback {
        void onLoaded(Result result);
        void onError(String message);
        default void onProgress(int scanned, int textCount) { }
    }

    public static final class Result {
        public final ArrayList<SummaryMessage> messages;
        public final boolean truncated;
        public final String coverageNote;
        public final int lowerExclusiveId;
        public final int upperInclusiveId;
        /** Safe batch endpoint. Advance a saved cursor only after a successful result commit. */
        public final int coveredThroughId;
        /** The chosen batch is complete, even if there is a later batch below the fixed upper ID. */
        public final boolean complete;
        public final boolean hasMore;
        public final boolean partial;
        public final int scannedMessageCount;

        private Result(ArrayList<SummaryMessage> messages, boolean truncated, String coverageNote,
                int lowerExclusiveId, int upperInclusiveId, int coveredThroughId,
                boolean hasMore, int scannedMessageCount) {
            this.messages = new ArrayList<>(messages);
            this.truncated = truncated;
            this.coverageNote = coverageNote;
            this.lowerExclusiveId = lowerExclusiveId;
            this.upperInclusiveId = upperInclusiveId;
            this.coveredThroughId = coveredThroughId;
            this.complete = !truncated;
            this.hasMore = hasMore;
            this.partial = truncated;
            this.scannedMessageCount = scannedMessageCount;
        }
    }

    private final int account;
    private final long ownerUserId;
    private final long dialogId;
    private final long topicId;
    private final AtomicInteger generation = new AtomicInteger();
    private Run active;

    public SummaryHistoryLoader(int account, long dialogId, long topicId) {
        this.account = account;
        this.ownerUserId = account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                ? UserConfig.getInstance(account).getClientUserId() : 0;
        this.dialogId = dialogId;
        this.topicId = topicId;
    }

    public void loadRecent(int count, Callback callback) {
        begin(false, count, callback);
    }

    public void loadToday(Callback callback) {
        begin(true, MAX_TODAY_MESSAGES, callback);
    }

    /** Read the earliest complete batch AFTER the saved cursor, never the newest N messages. */
    public void loadSince(int lowerExclusiveId, int count, Callback callback) {
        begin(false, count, lowerExclusiveId, -1, true, callback);
    }

    /** Boundaries must be captured by ChatActivity before Telegram changes the unread state. */
    public void loadUnread(int lowerExclusiveId, int upperInclusiveId, Callback callback) {
        loadRange(lowerExclusiveId, upperInclusiveId, MAX_RECENT_COUNT, callback);
    }

    public void loadUnread(int lowerExclusiveId, int upperInclusiveId, int count, Callback callback) {
        loadRange(lowerExclusiveId, upperInclusiveId, count, callback);
    }

    /** Count is the maximum number of raw history messages, including filtered service/media. */
    public void loadRange(int lowerExclusiveId, int upperInclusiveId, int count, Callback callback) {
        // An unknown unread upper bound must not silently become a fresh "latest" snapshot.
        begin(false, count, lowerExclusiveId, upperInclusiveId < 0 ? -2 : upperInclusiveId,
                true, callback);
    }

    public void cancel() {
        final int cancelledGeneration = generation.incrementAndGet();
        AndroidUtilities.runOnUIThread(() -> {
            if (generation.get() == cancelledGeneration) {
                cancelActive();
            }
        });
    }

    private void begin(boolean today, int count, Callback callback) {
        begin(today, count, 0, 0, false, callback);
    }

    private void begin(boolean today, int count, int lowerExclusiveId, int upperInclusiveId,
            boolean forward, Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback is required");
        }
        final int runGeneration = generation.incrementAndGet();
        // Capture time at the user's action, before queueing any UI or network work. Calendar.add
        // rather than 24 hours also covers 23/25-hour local days at daylight-saving transitions.
        final long startedAt = System.currentTimeMillis();
        final TimeZone timeZone = TimeZone.getDefault();
        AndroidUtilities.runOnUIThread(() -> {
            if (generation.get() != runGeneration) {
                return;
            }
            cancelActive();
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
                callback.onError("Telegram 账号无效，请重新打开当前群聊。");
                return;
            }
            final long clientUserId = UserConfig.getInstance(account).getClientUserId();
            if (clientUserId == 0) {
                callback.onError("当前 Telegram 账号已退出，请登录后重试。");
                return;
            }
            if (clientUserId != ownerUserId) {
                callback.onError("当前 Telegram 账号已变化，请重新打开群聊后重试。");
                return;
            }
            if (!DialogObject.isChatDialog(dialogId)) {
                callback.onError("目前仅支持普通群和超级群的文字消息总结。");
                return;
            }
            if (!today && (count < 1 || count > MAX_RECENT_COUNT)) {
                callback.onError("最近消息数量须为 1–" + MAX_RECENT_COUNT + "。");
                return;
            }
            if (forward && (lowerExclusiveId < 0 || upperInclusiveId < -1
                    || (upperInclusiveId >= 0 && upperInclusiveId < lowerExclusiveId))) {
                callback.onError("消息范围边界无效，请重新打开群聊并重新选择范围。");
                return;
            }
            MessagesController controller = MessagesController.getInstance(account);
            TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat == null) {
                callback.onError("群信息尚未加载，请稍后重试。");
                return;
            }
            if (chat.noforwards) {
                callback.onError("此群启用了内容保护，无法用于 AI 总结。");
                return;
            }
            if (chat.monoforum || (ChatObject.isChannel(chat) && !chat.megagroup)) {
                callback.onError("目前仅支持普通群和超级群，暂不支持频道或频道私信。");
                return;
            }
            if (chat.migrated_to != null) {
                callback.onError("此群已升级为超级群，请在新群中发起总结。");
                return;
            }
            if (topicId < 0 || topicId > Integer.MAX_VALUE || (topicId != 0 && !chat.forum)) {
                callback.onError("当前话题范围无效，请重新打开群话题后重试。");
                return;
            }
            Run run = new Run(account, clientUserId, runGeneration, today, count, callback,
                    startedAt, timeZone, controller, chat, lowerExclusiveId, upperInclusiveId,
                    forward);
            active = run;
            if (forward && (upperInclusiveId == lowerExclusiveId
                    || lowerExclusiveId == Integer.MAX_VALUE)) {
                run.upperInclusiveId = lowerExclusiveId;
                finishForward(run, false, "固定范围内没有新的历史消息。", lowerExclusiveId);
            } else {
                requestPage(run);
            }
        });
    }

    private void cancelActive() {
        if (active != null) {
            if (active.timeout != null) {
                AndroidUtilities.cancelRunOnUIThread(active.timeout);
            }
            active.connections.cancelRequestsForGuid(active.guid);
            active = null;
        }
    }

    private boolean isActive(Run run) {
        return active == run && generation.get() == run.generation;
    }

    private void requestPage(Run run) {
        if (!isActive(run) || !checkAccount(run)) {
            return;
        }
        TLRPC.InputPeer peer = run.controller.getInputPeer(dialogId);
        final boolean forwardPage = run.forward && !run.snapshotPending;
        final int limit = run.snapshotPending ? 1
                : forwardPage ? Math.min(PAGE_SIZE, run.target - run.scanned) : PAGE_SIZE;
        final int offsetId = forwardPage ? run.forwardCursor + 1 : run.offsetId;
        final int offsetDate = forwardPage ? 0
                : run.offsetId == 0 ? run.upperDateExclusive : 0;
        // Telegram's negative add_offset window starts immediately AFTER offset_id - 1.
        // min_id/max_id intentionally stay zero for forward windows: bounds are checked locally,
        // so server-side filtering cannot change which contiguous page follows the old cursor.
        final int addOffset = forwardPage ? -limit : 0;
        final int maxId = forwardPage ? 0 : run.maxIdExclusive;
        final TLObject request;
        if (topicId != 0) {
            TLRPC.TL_messages_getReplies replies = new TLRPC.TL_messages_getReplies();
            replies.peer = peer;
            replies.msg_id = (int) topicId;
            replies.offset_id = offsetId;
            replies.offset_date = offsetDate;
            replies.add_offset = addOffset;
            replies.max_id = maxId;
            replies.limit = limit;
            request = replies;
        } else {
            TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
            history.peer = peer;
            history.offset_id = offsetId;
            history.offset_date = offsetDate;
            history.add_offset = addOffset;
            history.max_id = maxId;
            history.limit = limit;
            request = history;
        }
        final int pageNumber = ++run.pages;
        run.timeout = () -> {
            if (isActive(run) && run.pages == pageNumber) {
                fail(run, "读取群历史超时，未生成总结。请检查 Telegram 连接后重试。");
            }
        };
        AndroidUtilities.runOnUIThread(run.timeout, PAGE_TIMEOUT_MS);
        int requestId = run.connections.sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isActive(run) || run.pages != pageNumber) {
                        return;
                    }
                    AndroidUtilities.cancelRunOnUIThread(run.timeout);
                    run.timeout = null;
                    if (error != null) {
                        // Never silently turn a failed later page into a smaller, successful range.
                        fail(run, "读取群历史失败，未生成总结（Telegram："
                                + (error.text == null ? Integer.toString(error.code) : error.text)
                                + "）。请稍后重试。");
                    } else if (!(response instanceof TLRPC.messages_Messages)
                            || response instanceof TLRPC.TL_messages_messagesNotModified) {
                        fail(run, "Telegram 返回了无法确认范围的历史结果，未生成总结。请重试。");
                    } else {
                        consumePage(run, (TLRPC.messages_Messages) response);
                    }
                }), ConnectionsManager.RequestFlagFailOnServerErrors);
        run.connections.bindRequestToGuid(requestId, run.guid);
    }

    private void consumePage(Run run, TLRPC.messages_Messages response) {
        if (!checkAccount(run) || !checkCurrentChat(run)) {
            return;
        }
        run.controller.putUsers(response.users, false);
        run.controller.putChats(response.chats, false);
        if (!checkCurrentChat(run)) {
            return;
        }
        if (run.forward) {
            if (run.snapshotPending) {
                consumeSnapshot(run, response);
            } else {
                consumeForwardPage(run, response);
            }
            return;
        }
        if (response.messages.isEmpty()) {
            finishAtEnd(run);
            return;
        }

        // Do not rely on the order or page length of replies: the server can overlap pages and
        // return short pages. Only an empty page, the date boundary, or a reached target ends a scan.
        ArrayList<TLRPC.Message> page = new ArrayList<>(response.messages);
        Collections.sort(page, (left, right) -> Integer.compare(right.id, left.id));
        int nextOffset = run.offsetId == 0 ? Integer.MAX_VALUE : run.offsetId;
        boolean progressed = false;
        boolean reachedDayStart = false;
        boolean onlyTopicAnchor = topicId != 0;
        for (TLRPC.Message message : page) {
            if (message.peer_id != null
                    && DialogObject.getPeerDialogId(message.peer_id) != dialogId) {
                fail(run, "Telegram 历史结果包含其他聊天，无法确认范围，未生成总结。");
                return;
            }
            if (message.id <= 0) {
                onlyTopicAnchor = false;
                continue;
            }
            // getReplies may include the topic's creation service message (the original group
            // creation for General). It is an anchor, not a history cursor: using its old ID could
            // skip pages of actual replies.
            if (topicId != 0 && message.id == topicId
                    && (message instanceof TLRPC.TL_messageService || message.action != null)) {
                continue;
            }
            onlyTopicAnchor = false;
            if (run.offsetId != 0 && message.id >= run.offsetId) {
                continue;
            }
            if (message.id < nextOffset) {
                nextOffset = message.id;
                progressed = true;
            }
            if (!run.seen.add(message.id)) {
                continue;
            }
            run.scanned++;
            if (message.date >= run.upperDateExclusive) {
                continue;
            }
            if (run.maxIdExclusive == 0 && message.date > 0) {
                run.maxIdExclusive = message.id == Integer.MAX_VALUE ? 0 : message.id + 1;
            }
            // Validate topic scope before using a text message's date as the day boundary.
            if (topicId != 0 && isUsableText(message)
                    && MessageObject.getTopicId(account, message, true) != topicId) {
                fail(run, "Telegram 历史结果包含其他 Topic，无法确认范围，未生成总结。");
                return;
            }
            // Empty/deleted placeholders have no reliable date; they still advance the ID cursor.
            if (message.date > 0 && run.today && message.date < run.lowerDateInclusive) {
                reachedDayStart = true;
                continue;
            }
            rememberPosition(run, message);
            if (!isUsableText(message)) {
                run.skipped++;
                continue;
            }
            run.messages.add(toSummaryMessage(run, message));
            if (run.messages.size() == run.target) {
                if (run.today) {
                    finish(run, true, "当日文字达到 " + MAX_TODAY_MESSAGES
                            + " 条上限，仅总结较新的部分；未确认更早的当日消息。");
                } else {
                    finish(run, false, "已找到最近 " + run.target + " 条可用纯文字。");
                }
                return;
            }
        }
        if (reachedDayStart) {
            finish(run, false, "已读至本次固定的当日零点边界。");
        } else if (onlyTopicAnchor) {
            // A replies page containing only its creation anchor has no remaining replies.
            finishAtEnd(run);
        } else if (!progressed) {
            // A nonempty page must advance its cursor. Do not call it a complete result when it
            // only repeats old messages or unexpectedly contains another peer/topic.
            finish(run, true, "服务器分页游标未前进，已停止；只总结已读取的部分消息。");
        } else if (run.scanned >= MAX_SCANNED_MESSAGES
                || run.pages >= MAX_SCANNED_MESSAGES / PAGE_SIZE) {
            finish(run, true, "已达到 " + MAX_SCANNED_MESSAGES
                    + " 条或 " + (MAX_SCANNED_MESSAGES / PAGE_SIZE)
                    + " 页历史扫描上限；只总结已读取的部分消息。");
        } else {
            run.offsetId = nextOffset;
            reportProgress(run);
            requestPage(run);
        }
    }

    public static boolean isUsableText(TLRPC.Message message) {
        return message != null && !(message instanceof TLRPC.TL_messageService)
                && !(message instanceof TLRPC.TL_messageEmpty)
                && message.action == null
                && message.id > 0 && message.date > 0 && message.send_state == 0
                && message.peer_id != null
                && !message.noforwards && message.ttl_period == 0 && message.ttl == 0
                && message.destroyTime == 0 && message.destroyTimeMillis == 0
                && message.expire_date == 0
                && message.ephemeralAnchorMsgId == 0 && message.ephemeralReceiverBotId == 0
                && message.quick_reply_shortcut_id == 0
                && (message.media == null || message.media.ttl_seconds == 0)
                // Link-preview messages still have ordinary user-authored text. Only send that
                // text, never the webpage object's title, description or remotely fetched body.
                && (message.media == null || message.media instanceof TLRPC.TL_messageMediaEmpty
                    || message.media instanceof TLRPC.TL_messageMediaWebPage)
                && message.message != null && !message.message.trim().isEmpty();
    }

    private void consumeSnapshot(Run run, TLRPC.messages_Messages response) {
        int newest = run.lowerExclusiveId;
        for (TLRPC.Message message : response.messages) {
            if (!checkPageScope(run, message)) {
                return;
            }
            if (message.id <= 0 || message.date >= run.upperDateExclusive) {
                fail(run, "无法确认消息快照的上界，未读取增量范围，请重试。");
                return;
            }
            newest = Math.max(newest, message.id);
        }
        run.upperInclusiveId = newest;
        run.snapshotPending = false;
        if (newest == run.lowerExclusiveId) {
            finishForward(run, false, "固定快照内没有新的历史消息。", newest);
        } else {
            requestPage(run);
        }
    }

    private void consumeForwardPage(Run run, TLRPC.messages_Messages response) {
        if (response.messages.isEmpty()) {
            // Empty forward windows also cover deleted/nonexistent positions before the fixed
            // upper ID. Their absence is established by the server, never by integer-ID gaps.
            finishForward(run, false, "已检查至固定上界，没有更多可见历史消息。",
                    run.upperInclusiveId);
            return;
        }
        ArrayList<TLRPC.Message> page = new ArrayList<>(response.messages);
        Collections.sort(page, (left, right) -> Integer.compare(left.id, right.id));
        final int oldCursor = run.forwardCursor;
        for (TLRPC.Message message : page) {
            if (!checkPageScope(run, message)) {
                return;
            }
            if (message.id <= oldCursor || message.id <= 0 || !run.seen.add(message.id)) {
                continue;
            }
            if (message.id > run.upperInclusiveId) {
                finishForward(run, false, "已读至固定上界；之后到达的消息留待下次。",
                        run.upperInclusiveId);
                return;
            }
            run.forwardCursor = message.id;
            run.scanned++;
            rememberPosition(run, message);
            if (isUsableText(message)) {
                run.messages.add(toSummaryMessage(run, message));
            } else {
                run.skipped++;
            }
            if (run.forwardCursor == run.upperInclusiveId) {
                finishForward(run, false, "已完整读取本次固定范围。", run.forwardCursor);
                return;
            }
            if (run.scanned == run.target) {
                finishForward(run, false, "本批已完整读取 " + run.scanned
                        + " 条历史消息；固定上界内仍有积压，可继续下一批。", run.forwardCursor);
                return;
            }
        }
        if (run.forwardCursor <= oldCursor) {
            finishForward(run, true, "服务器向新分页未前进，无法确认连续覆盖；请重试。",
                    run.lowerExclusiveId);
        } else if (run.pages >= MAX_SCANNED_MESSAGES / PAGE_SIZE) {
            finishForward(run, true, "已达到历史分页扫描上限，未能完整确认这一批；请重试。",
                    run.lowerExclusiveId);
        } else {
            reportProgress(run);
            requestPage(run);
        }
    }

    private boolean checkPageScope(Run run, TLRPC.Message message) {
        if (message.peer_id != null
                && DialogObject.getPeerDialogId(message.peer_id) != dialogId) {
            fail(run, "Telegram 历史结果包含其他聊天，无法确认范围，未生成总结。");
            return false;
        }
        if (message instanceof TLRPC.TL_messageEmpty || message.id <= 0) {
            return true;
        }
        if (message.peer_id == null) {
            fail(run, "Telegram 历史消息缺少聊天标识，无法确认范围，请重试。");
            return false;
        }
        if (topicId != 0 && !(message.id == topicId
                && (message instanceof TLRPC.TL_messageService || message.action != null))
                && MessageObject.getTopicId(account, message, true) != topicId) {
            fail(run, "Telegram 历史结果包含其他 Topic，无法确认范围，未生成总结。");
            return false;
        }
        return true;
    }

    private void rememberPosition(Run run, TLRPC.Message message) {
        run.highestCoveredId = Math.max(run.highestCoveredId, message.id);
        run.lowestCoveredId = Math.min(run.lowestCoveredId, message.id);
        long senderId = DialogObject.getPeerDialogId(message.from_id);
        if (senderId != 0) {
            run.senders.put(message.id, senderId);
        }
    }

    private SummaryMessage toSummaryMessage(Run run, TLRPC.Message message) {
        int replyToId = 0;
        long replyToDialogId = 0;
        if (message.reply_to != null && !message.reply_to.reply_to_scheduled
                && !message.reply_to.reply_to_ephemeral && message.reply_to.reply_to_msg_id > 0) {
            replyToId = message.reply_to.reply_to_msg_id;
            replyToDialogId = message.reply_to.reply_to_peer_id == null ? dialogId
                    : DialogObject.getPeerDialogId(message.reply_to.reply_to_peer_id);
        }
        boolean mentionedSelf = false;
        for (TLRPC.MessageEntity entity : message.entities) {
            if (entity instanceof TLRPC.TL_messageEntityMentionName
                    && ((TLRPC.TL_messageEntityMentionName) entity).user_id == run.clientUserId) {
                mentionedSelf = true;
            } else if (entity instanceof TLRPC.TL_messageEntityMention && message.mentioned
                    && entity.offset >= 0 && entity.length > 1
                    && entity.offset <= message.message.length() - entity.length) {
                String username = message.message.substring(entity.offset, entity.offset + entity.length);
                if (username.startsWith("@")
                        && run.selfUsernames.contains(username.substring(1).toLowerCase(Locale.US))) {
                    mentionedSelf = true;
                }
            }
        }
        return new SummaryMessage(dialogId, message.id, message.date, senderName(run, message),
                message.message, DialogObject.getPeerDialogId(message.from_id), replyToId,
                replyToDialogId, mentionedSelf, message.out, message.edit_date, false, false);
    }

    private void resolveReplySenders(Run run) {
        for (int i = 0; i < run.messages.size(); i++) {
            SummaryMessage message = run.messages.get(i);
            if (message.replyToId <= 0 || message.replyToDialogId != dialogId) {
                continue;
            }
            Long senderId = run.senders.get(message.replyToId);
            if (senderId != null) {
                run.messages.set(i, new SummaryMessage(message.dialogId, message.id, message.date,
                        message.sender, message.text, message.senderId, message.replyToId,
                        message.replyToDialogId, message.mentionedSelf, message.outgoing,
                        message.editDate, true, senderId == run.clientUserId));
            }
        }
    }

    private void reportProgress(Run run) {
        if (isActive(run)) {
            run.callback.onProgress(run.scanned, run.messages.size());
        }
    }

    private void finishForward(Run run, boolean partial, String reason, int coveredThroughId) {
        run.coveredThroughId = coveredThroughId;
        finish(run, partial, reason);
    }

    private String senderName(Run run, TLRPC.Message message) {
        long senderId = DialogObject.getPeerDialogId(message.from_id);
        if (senderId > 0) {
            TLRPC.User user = run.controller.getUser(senderId);
            if (user != null) {
                // UserObject.getUserName can fall back to a contact's phone number. A summary
                // only needs the display name, so never add that unrelated account metadata.
                String name = ((user.first_name == null ? "" : user.first_name) + " "
                        + (user.last_name == null ? "" : user.last_name)).trim();
                if (!name.isEmpty()) {
                    return name;
                }
                if (user.username != null && !user.username.isEmpty()) {
                    return "@" + user.username;
                }
            }
            return "用户 " + senderId;
        }
        if (senderId < 0) {
            TLRPC.Chat chat = run.controller.getChat(-senderId);
            if (chat != null && chat.title != null) {
                return chat.title;
            }
        }
        if (message.post_author != null && !message.post_author.trim().isEmpty()) {
            return message.post_author;
        }
        return "匿名发送者";
    }

    private void finishAtEnd(Run run) {
        TLRPC.ChatFull full = run.controller.getChatFull(-dialogId);
        if (topicId == 0 && full != null && full.migrated_from_chat_id != 0) {
            finish(run, true, "已读至当前群可见历史起点；此群有迁移前历史，本版未合并旧群消息。");
        } else if (topicId == 0 && run.channel && full == null) {
            finish(run, true, "已读至当前群可见历史起点；尚无完整群信息，无法确认是否存在迁移前历史。");
        } else {
            finish(run, false, "已读至当前群/话题在本账号下的可见历史起点。");
        }
    }

    private void finish(Run run, boolean truncated, String reason) {
        if (!isActive(run) || !checkAccount(run) || !checkCurrentChat(run)) {
            return;
        }
        Collections.sort(run.messages, (left, right) -> {
            if (run.forward) {
                return Integer.compare(left.id, right.id);
            }
            int byDate = Integer.compare(left.date, right.date);
            return byDate != 0 ? byDate : Integer.compare(left.id, right.id);
        });
        resolveReplySenders(run);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(run.timeZone);
        String scope = topicId != 0 ? "当前 Topic #" + topicId
                : run.forum ? "当前群的全部 Topic" : "当前群";
        StringBuilder note = new StringBuilder(scope).append("；手机时区 ")
                .append(run.timeZone.getID()).append(run.forward ? "，读取发起于 " : "，固定截止 ")
                .append(format.format(new Date(run.startedAt))).append("。\n");
        if (run.forward) {
            note.append("固定消息范围：#").append(run.lowerExclusiveId).append(" 之后至 #")
                    .append(run.upperInclusiveId).append("；每批最多 ").append(run.target)
                    .append(" 条历史消息，仅总结文字。 ");
        } else if (run.today) {
            note.append("当日范围从 ")
                    .append(format.format(new Date(run.lowerDateInclusive * 1000L)))
                    .append(" 至上述截止时刻。 ");
        } else {
            note.append("目标：最近 ").append(run.target).append(" 条可用纯文字。 ");
        }
        note.append("实际纳入 ").append(run.messages.size()).append(" 条");
        if (!run.messages.isEmpty()) {
            note.append("（")
                    .append(format.format(new Date(run.messages.get(0).date * 1000L)))
                    .append(" — ")
                    .append(format.format(new Date(run.messages.get(run.messages.size() - 1).date * 1000L)))
                    .append("）");
        }
        note.append("，扫描 ").append(run.scanned).append(" 条，跳过 ")
                .append(run.skipped).append(" 条不适用消息。\n")
                .append(truncated ? "部分结果：" : "").append(reason)
                .append(" 仅含当前账号可见的当前群历史；带链接预览的消息只读取原始文字，")
                .append("不读取网页正文；不含媒体及其说明、服务消息、受保护或限时内容、")
                .append("广告、未发送消息和迁移前旧群消息。");
        int lowerId = run.forward ? run.lowerExclusiveId
                : run.lowestCoveredId == Integer.MAX_VALUE ? 0 : Math.max(0, run.lowestCoveredId - 1);
        int upperId = run.forward ? run.upperInclusiveId : run.highestCoveredId;
        int coveredId = run.forward ? run.coveredThroughId : truncated ? 0 : upperId;
        boolean hasMore = run.forward && coveredId < upperId;
        if (run.forward && !truncated) {
            note.append(" 本批连续覆盖至 #").append(coveredId).append("；")
                    .append(hasMore ? "仍有后续批次。" : "已到达固定上界。");
        }
        Result result = new Result(run.messages, truncated, note.toString(), lowerId, upperId,
                coveredId, hasMore, run.scanned);
        reportProgress(run);
        if (!isActive(run)) {
            return;
        }
        cancelActive();
        run.callback.onLoaded(result);
    }

    private void fail(Run run, String message) {
        if (!isActive(run)) {
            return;
        }
        cancelActive();
        run.callback.onError(message);
    }

    private boolean checkAccount(Run run) {
        if (UserConfig.getInstance(account).getClientUserId() != run.clientUserId) {
            fail(run, "当前 Telegram 账号已变化，已停止读取消息，请重新打开群聊后重试。");
            return false;
        }
        return true;
    }

    private boolean checkCurrentChat(Run run) {
        TLRPC.Chat currentChat = run.controller.getChat(-dialogId);
        if (currentChat == null || currentChat.noforwards || currentChat.migrated_to != null) {
            fail(run, "群信息已变化或启用了内容保护，已停止读取消息。");
            return false;
        }
        return true;
    }

    private static final class Run {
        final int generation;
        final long clientUserId;
        final int guid = ConnectionsManager.generateClassGuid();
        final boolean today;
        final int target;
        final Callback callback;
        final long startedAt;
        final TimeZone timeZone;
        final int lowerDateInclusive;
        final int upperDateExclusive;
        final MessagesController controller;
        final ConnectionsManager connections;
        final boolean forum;
        final boolean channel;
        final boolean forward;
        final int lowerExclusiveId;
        final ArrayList<SummaryMessage> messages = new ArrayList<>();
        final HashSet<Integer> seen = new HashSet<>();
        final HashMap<Integer, Long> senders = new HashMap<>();
        final HashSet<String> selfUsernames = new HashSet<>();
        int upperInclusiveId;
        int coveredThroughId;
        int forwardCursor;
        boolean snapshotPending;
        int highestCoveredId;
        int lowestCoveredId = Integer.MAX_VALUE;
        int offsetId;
        int maxIdExclusive;
        int scanned;
        int skipped;
        int pages;
        Runnable timeout;

        Run(int account, long clientUserId, int generation, boolean today, int target,
                Callback callback, long startedAt, TimeZone timeZone,
                MessagesController controller, TLRPC.Chat chat, int lowerExclusiveId,
                int upperInclusiveId, boolean forward) {
            this.clientUserId = clientUserId;
            this.generation = generation;
            this.today = today;
            this.target = target;
            this.callback = callback;
            this.startedAt = startedAt;
            this.timeZone = timeZone;
            this.controller = controller;
            this.connections = ConnectionsManager.getInstance(account);
            this.forum = chat.forum;
            this.channel = ChatObject.isChannel(chat);
            this.forward = forward;
            this.lowerExclusiveId = lowerExclusiveId;
            this.upperInclusiveId = upperInclusiveId;
            this.forwardCursor = lowerExclusiveId;
            this.coveredThroughId = lowerExclusiveId;
            this.snapshotPending = forward && upperInclusiveId < 0;
            addUsernames(UserConfig.getInstance(account).getCurrentUser());
            addUsernames(controller.getUser(clientUserId));
            Calendar day = Calendar.getInstance(timeZone);
            day.setTimeInMillis(startedAt);
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            lowerDateInclusive = today ? (int) (day.getTimeInMillis() / 1000L) : 0;
            day.add(Calendar.DAY_OF_MONTH, 1);
            // Telegram message dates have second resolution; include the action's current second.
            upperDateExclusive = (int) Math.min(startedAt / 1000L + 1,
                    today ? day.getTimeInMillis() / 1000L : Integer.MAX_VALUE);
        }

        private void addUsernames(TLRPC.User user) {
            if (user == null || user.id != clientUserId) {
                return;
            }
            if (user.username != null && !user.username.isEmpty()) {
                selfUsernames.add(user.username.toLowerCase(Locale.US));
            }
            if (user.usernames != null) {
                for (TLRPC.TL_username username : user.usernames) {
                    if (username.active && username.username != null && !username.username.isEmpty()) {
                        selfUsernames.add(username.username.toLowerCase(Locale.US));
                    }
                }
            }
        }
    }
}
