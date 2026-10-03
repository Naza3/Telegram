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
    }

    public static final class Result {
        public final ArrayList<SummaryMessage> messages;
        public final boolean truncated;
        public final String coverageNote;

        private Result(ArrayList<SummaryMessage> messages, boolean truncated, String coverageNote) {
            this.messages = new ArrayList<>(messages);
            this.truncated = truncated;
            this.coverageNote = coverageNote;
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

    public void cancel() {
        final int cancelledGeneration = generation.incrementAndGet();
        AndroidUtilities.runOnUIThread(() -> {
            if (generation.get() == cancelledGeneration) {
                cancelActive();
            }
        });
    }

    private void begin(boolean today, int count, Callback callback) {
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
                    startedAt, timeZone, controller, chat);
            active = run;
            requestPage(run);
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
        final TLObject request;
        if (topicId != 0) {
            TLRPC.TL_messages_getReplies replies = new TLRPC.TL_messages_getReplies();
            replies.peer = peer;
            replies.msg_id = (int) topicId;
            replies.offset_id = run.offsetId;
            replies.offset_date = run.offsetId == 0 ? run.upperDateExclusive : 0;
            replies.max_id = run.maxIdExclusive;
            replies.limit = PAGE_SIZE;
            request = replies;
        } else {
            TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
            history.peer = peer;
            history.offset_id = run.offsetId;
            history.offset_date = run.offsetId == 0 ? run.upperDateExclusive : 0;
            history.max_id = run.maxIdExclusive;
            history.limit = PAGE_SIZE;
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
            if (!isUsableText(message)) {
                run.skipped++;
                continue;
            }
            run.messages.add(new SummaryMessage(dialogId, message.id, message.date,
                    senderName(run, message), message.message));
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
            requestPage(run);
        }
    }

    private boolean isUsableText(TLRPC.Message message) {
        return !(message instanceof TLRPC.TL_messageService)
                && !(message instanceof TLRPC.TL_messageEmpty)
                && message.action == null
                && message.id > 0 && message.date > 0 && message.send_state == 0
                && message.peer_id != null
                && !message.noforwards && message.ttl_period == 0 && message.ttl == 0
                && message.destroyTime == 0 && message.destroyTimeMillis == 0
                && message.expire_date == 0
                && message.ephemeralAnchorMsgId == 0 && message.ephemeralReceiverBotId == 0
                && message.quick_reply_shortcut_id == 0
                // Link-preview messages still have ordinary user-authored text. Only send that
                // text, never the webpage object's title, description or remotely fetched body.
                && (message.media == null || message.media instanceof TLRPC.TL_messageMediaEmpty
                    || message.media instanceof TLRPC.TL_messageMediaWebPage)
                && message.message != null && !message.message.trim().isEmpty();
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
            int byDate = Integer.compare(left.date, right.date);
            return byDate != 0 ? byDate : Integer.compare(left.id, right.id);
        });
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(run.timeZone);
        String scope = topicId != 0 ? "当前 Topic #" + topicId
                : run.forum ? "当前群的全部 Topic" : "当前群";
        StringBuilder note = new StringBuilder(scope).append("；手机时区 ")
                .append(run.timeZone.getID()).append("，固定截止 ")
                .append(format.format(new Date(run.startedAt))).append("。\n");
        if (run.today) {
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
        Result result = new Result(run.messages, truncated, note.toString());
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
        final ArrayList<SummaryMessage> messages = new ArrayList<>();
        final HashSet<Integer> seen = new HashSet<>();
        int offsetId;
        int maxIdExclusive;
        int scanned;
        int skipped;
        int pages;
        Runnable timeout;

        Run(int account, long clientUserId, int generation, boolean today, int target,
                Callback callback, long startedAt, TimeZone timeZone,
                MessagesController controller, TLRPC.Chat chat) {
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
    }
}
