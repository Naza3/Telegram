package org.telegram.messenger.ai;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.Calendar;
import java.util.TimeZone;

/** Exercises the real loader with a deterministic queued Telegram transport and main looper. */
public final class SummaryHistoryLoaderTest {
    private static final int ACCOUNT = 0;
    private static final long DIALOG = -100;
    private static int assertions;
    private static int passed;
    private static final ConnectionsManager network = ConnectionsManager.getInstance(ACCOUNT);
    private static final MessagesController controller = MessagesController.getInstance(ACCOUNT);

    private static final class Result implements SummaryHistoryLoader.Callback {
        SummaryHistoryLoader.Result loaded;
        String error;
        int calls;
        public void onLoaded(SummaryHistoryLoader.Result value) { loaded = value; calls++; }
        public void onError(String value) { error = value; calls++; }
    }

    public static void main(String[] args) {
        test("recent messages paginate through short, filtered and overlapping pages", SummaryHistoryLoaderTest::recent);
        test("today includes midnight and excludes earlier/future messages", SummaryHistoryLoaderTest::today);
        test("topic request, root cursor and plain text with link preview", SummaryHistoryLoaderTest::topic);
        test("cancel suppresses stale reply and restart remains usable", SummaryHistoryLoaderTest::cancel);
        test("account changes reject already pending history results", SummaryHistoryLoaderTest::accountChange);
        test("read-only broadcast history completes without ChatFull", SummaryHistoryLoaderTest::broadcast);
        test("broadcast incremental history keeps its fixed range", SummaryHistoryLoaderTest::broadcastRange);
        test("forum overview uses whole-chat history across topics", SummaryHistoryLoaderTest::forumOverview);
        test("unsupported and unavailable scopes never read history", SummaryHistoryLoaderTest::unavailable);
        test("read permission changes and RPC denial do not yield sources", SummaryHistoryLoaderTest::permissionChange);
        test("megagroup migration uncertainty remains partial", SummaryHistoryLoaderTest::migration);
        test("live auto-delete text remains in recent and today including forum topics", SummaryHistoryLoaderTest::autoDeleteText);
        test("recent history is not anchored to a stale device clock", SummaryHistoryLoaderTest::serverClock);
        test("readable no-forwards chats and text remain eligible", SummaryHistoryLoaderTest::noForwards);
        System.out.println("SummaryHistoryLoaderTest: " + passed + " passed, " + assertions + " assertions");
    }

    private static void recent() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(3, result);
        AndroidUtilities.drain();
        check(network.next().request instanceof TLRPC.TL_messages_getHistory, "expected ordinary history request");
        TLRPC.TL_messages_getHistory first = (TLRPC.TL_messages_getHistory) network.next().request;
        check(first.limit == 100 && first.offset_id == 0 && first.offset_date == 0, "wrong initial pagination");
        int now = now();
        TLRPC.Message media = message(98, now - 2, "photo caption must not be included");
        media.media = new TLRPC.TL_messageMediaPhoto();
        reply(message(100, now, "newest"), message(99, now - 1, "   "), media);
        check(result.calls == 0, "short page must not be mistaken for end");
        TLRPC.TL_messages_getHistory second = (TLRPC.TL_messages_getHistory) network.next().request;
        check(second.offset_id == 98 && second.offset_date == 0, "filtered message must still advance cursor");
        check(second.max_id == 101, "snapshot message upper bound not preserved");
        reply(message(98, now - 2, "overlap should not count"), message(97, now - 3, "middle"));
        check(result.calls == 0, "overlap/duplicate incorrectly met target");
        check(((TLRPC.TL_messages_getHistory) network.next().request).offset_id == 97, "second cursor incorrect");
        reply(message(96, now - 4, "oldest"));
        check(result.calls == 1 && result.error == null && !result.loaded.truncated, "expected completed recent range");
        check(result.loaded.messages.size() == 3, "wrong number of source messages");
        check(result.loaded.messages.get(0).id == 96 && result.loaded.messages.get(1).id == 97
                && result.loaded.messages.get(2).id == 100, "sources must be sorted chronologically and deduplicated");
        check(result.loaded.messages.get(0).dialogId == DIALOG, "source dialog provenance lost");
        check(network.pending.isEmpty() && AndroidUtilities.pendingTimers() == 0, "completed load leaked transport/timer");
    }

    private static void today() {
        reset(false);
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
            int midnight = (int) (day.getTimeInMillis() / 1000L);
            Result result = new Result();
            new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadToday(result);
            AndroidUtilities.drain();
            int upper = ((TLRPC.TL_messages_getHistory) network.next().request).offset_date;
            check(upper > midnight, "invalid local-day range");
            // Deliberately unordered: loader must establish date coverage independently of array order.
            reply(message(100, midnight - 1, "yesterday"), message(103, upper, "after snapshot"),
                    message(101, midnight, "midnight included"), message(102, upper - 1, "latest included"));
            check(result.calls == 1 && result.error == null && !result.loaded.truncated, "date boundary should complete scan");
            check(result.loaded.messages.size() == 2 && result.loaded.messages.get(0).id == 101
                    && result.loaded.messages.get(1).id == 102, "incorrect inclusive lower/exclusive upper boundary");
            check(result.loaded.coverageNote.contains("Pacific/Honolulu"), "coverage note lost snapshot timezone");
            check(network.sent.size() == 1, "date boundary triggered unnecessary older page");
        } finally { TimeZone.setDefault(previous); }
    }

    private static void topic() {
        reset(true);
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 42).loadRecent(2, result);
        AndroidUtilities.drain();
        check(network.next().request instanceof TLRPC.TL_messages_getReplies, "topic must load replies");
        TLRPC.TL_messages_getReplies first = (TLRPC.TL_messages_getReplies) network.next().request;
        check(first.msg_id == 42 && first.peer.chat_id == -DIALOG, "wrong topic/peer request");
        check(first.offset_date == 0, "recent topic history must start at the newest message");
        int now = now();
        TLRPC.Message link = topicMessage(300, now, "Read https://example.invalid/source");
        link.media = new TLRPC.TL_messageMediaWebPage();
        link.reply_to.reply_to_msg_id = 298;
        link.reply_to.flags = 1 << 6;
        link.reply_to.quote_text = "earlier source";
        TLRPC.Message root = new TLRPC.TL_messageService();
        root.id = 42; root.date = now - 10000; root.peer_id = peer();
        root.action = new TLRPC.TL_messageActionTopicCreate();
        reply(root, link, topicMessage(299, now - 1, " "));
        check(result.calls == 0, "short topic page ended early");
        check(((TLRPC.TL_messages_getReplies) network.next().request).offset_id == 299,
                "old topic root must not skip remaining reply pages");
        reply(topicMessage(298, now - 2, "earlier source"));
        check(result.calls == 1 && result.error == null && result.loaded.messages.size() == 2, "topic result missing");
        check(result.loaded.messages.get(1).text.equals(link.message), "original link text was lost or replaced by webpage content");
        check(result.loaded.messages.get(0).id == 298 && result.loaded.messages.get(1).id == 300,
                "topic source references point to wrong messages");
        SummaryMessage quoted = result.loaded.messages.get(1);
        check(quoted.topicId == 42 && result.loaded.messages.get(0).topicId == 42
                && quoted.replyToId == 298 && quoted.replyToDialogId == DIALOG,
                "specific-topic history lost per-message topic or reply identity");
        check(quoted.replyToSelfKnown && quoted.quoteText.equals("earlier source"),
                "recent pagination or reply resolution dropped server quote metadata");
        check(result.loaded.coverageNote.contains("Topic #42"), "coverage missing topic scope");
    }

    private static void cancel() {
        reset(false);
        SummaryHistoryLoader loader = new SummaryHistoryLoader(ACCOUNT, DIALOG, 0);
        Result stale = new Result(); loader.loadRecent(1, stale); AndroidUtilities.drain();
        ConnectionsManager.Pending oldRequest = network.next();
        loader.cancel(); AndroidUtilities.drain();
        check(network.pending.isEmpty() && !network.cancelledGuids.isEmpty(), "cancel did not cancel transport GUID");
        check(AndroidUtilities.pendingTimers() == 0, "cancel leaked timer");
        TLRPC.TL_messages_messages late = page(message(1, now(), "stale"));
        oldRequest.delegate.run(late, null); AndroidUtilities.drain();
        check(stale.calls == 0, "cancel delivered stale callback");
        Result fresh = new Result(); loader.loadRecent(1, fresh); AndroidUtilities.drain();
        reply(message(2, now(), "new request"));
        check(fresh.calls == 1 && fresh.error == null && fresh.loaded.messages.get(0).id == 2,
                "cancelled loader could not restart");
        check(stale.calls == 0, "restart revived old callback");
    }

    private static void accountChange() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(1, result);
        AndroidUtilities.drain();
        // Response is waiting for the UI thread when the account slot gets reused.
        network.reply(page(message(7, now(), "old account message")), null);
        UserConfig.getInstance(ACCOUNT).setClientUserId(9000);
        AndroidUtilities.drain();
        check(result.calls == 1 && result.error != null && result.loaded == null, "account change mixed source identities");
        check(AndroidUtilities.pendingTimers() == 0, "account rejection leaked timer");
    }

    private static void broadcast() {
        reset(false);
        TLRPC.Chat chat = channel(false);
        // Read access is independent of posting rights and membership; the RPC is authoritative.
        chat.left = true;
        chat.banned_rights = new TLRPC.TL_chatBannedRights();
        chat.banned_rights.send_messages = true;
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(3, result);
        AndroidUtilities.drain();
        check(network.next().request instanceof TLRPC.TL_messages_getHistory, "broadcast must use history");
        TLRPC.TL_messages_getHistory request = (TLRPC.TL_messages_getHistory) network.next().request;
        check(request.peer instanceof TLRPC.TL_inputPeerChannel && request.peer.channel_id == -DIALOG
                && request.peer.access_hash == 12345, "broadcast input peer lost its access hash");
        TLRPC.Message first = channelMessage(9, now() - 1, "最新频道文字");
        first.post = true; first.from_id = null; first.post_author = "频道作者";
        reply(first, channelMessage(8, now() - 2, "较早频道文字"));
        check(result.calls == 0, "short broadcast page must still check the history end");
        reply();
        check(result.calls == 1 && result.error == null && result.loaded.complete && !result.loaded.partial,
                "broadcast without ChatFull was falsely marked incomplete");
        check(result.loaded.messages.size() == 2 && result.loaded.messages.get(1).sender.equals("频道作者"),
                "broadcast text or signed author was lost");
        check(result.loaded.coveredThroughId == 9 && !result.loaded.coverageNote.contains("无法确认是否存在迁移"),
                "broadcast inherited megagroup migration uncertainty");
        for (org.telegram.tgnet.TLObject sent : network.sent) {
            check(sent instanceof TLRPC.TL_messages_getHistory, "broadcast issued a non-history request");
        }
    }

    private static void broadcastRange() {
        reset(false); channel(false);
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRange(10, 20, 2, result);
        AndroidUtilities.drain();
        TLRPC.TL_messages_getHistory request = (TLRPC.TL_messages_getHistory) network.next().request;
        check(request.peer instanceof TLRPC.TL_inputPeerChannel && request.offset_id == 11
                && request.add_offset == -2, "broadcast forward request lost its exclusive lower bound");
        reply(channelMessage(15, now() - 1, "第二条"), channelMessage(12, now() - 2, "第一条"));
        check(result.calls == 1 && result.error == null && result.loaded.complete && result.loaded.hasMore,
                "broadcast forward batch did not complete");
        check(result.loaded.coveredThroughId == 15 && result.loaded.upperInclusiveId == 20
                && result.loaded.lowerExclusiveId == 10 && result.loaded.messages.get(0).id == 12,
                "broadcast forward batch changed fixed bounds or order");
    }

    private static void forumOverview() {
        reset(false); TLRPC.Chat chat = channel(true); chat.forum = true;
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(2, result);
        AndroidUtilities.drain();
        check(network.next().request instanceof TLRPC.TL_messages_getHistory, "forum overview must not pick one topic");
        TLRPC.Message one = channelMessage(52, now() - 1, "第一个话题");
        one.reply_to = new TLRPC.TL_messageReplyHeader(); one.reply_to.forum_topic = true;
        one.reply_to.reply_to_top_id = 42;
        TLRPC.Message two = channelMessage(51, now() - 2, "第二个话题");
        two.reply_to = new TLRPC.TL_messageReplyHeader(); two.reply_to.forum_topic = true;
        two.reply_to.reply_to_top_id = 43;
        reply(one, two);
        check(result.calls == 1 && result.error == null && result.loaded.messages.size() == 2
                && result.loaded.coverageNote.contains("全部 Topic"), "forum overview lost cross-topic sources");
    }

    private static void unavailable() {
        for (int variant = 0; variant < 8; variant++) {
            reset(false);
            TLRPC.Chat chat = controller.getChat(-DIALOG);
            switch (variant) {
                case 0: chat.monoforum = true; break;
                case 1: chat.migrated_to = new TLRPC.TL_inputChannel(); break;
                case 2: chat.kicked = true; break;
                case 3: chat.deactivated = true; break;
                case 4: chat = new TLRPC.TL_chatForbidden(); break;
                case 5: chat = new TLRPC.TL_channelForbidden(); break;
                case 6: chat = new TLRPC.TL_chatEmpty(); break;
                default:
                    chat.banned_rights = new TLRPC.TL_chatBannedRights();
                    chat.banned_rights.view_messages = true;
            }
            chat.id = -DIALOG; controller.chats.put(chat.id, chat);
            Result result = new Result();
            new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(1, result);
            AndroidUtilities.drain();
            check(result.calls == 1 && result.loaded == null && result.error != null,
                    "unavailable chat accepted variant " + variant);
            check(network.sent.isEmpty(), "unavailable chat made a request variant " + variant);
        }
        reset(false);
        Result secret = new Result();
        new SummaryHistoryLoader(ACCOUNT, 0x400000000000002aL, 0).loadRecent(1, secret);
        AndroidUtilities.drain();
        check(secret.calls == 1 && secret.error != null && secret.loaded == null && network.sent.isEmpty(),
                "secret chat must not become a channel/group history request");
        reset(false); channel(false);
        Result invalidTopic = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 42).loadRecent(1, invalidTopic);
        AndroidUtilities.drain();
        check(invalidTopic.calls == 1 && invalidTopic.error != null && network.sent.isEmpty(),
                "broadcast channel accepted a forum topic scope");
    }

    private static void permissionChange() {
        reset(false); channel(false);
        Result changed = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(1, changed);
        AndroidUtilities.drain();
        TLRPC.TL_messages_messages response = page(channelMessage(9, now() - 1, "不可继续读取"));
        TLRPC.Chat denied = new TLRPC.TL_channel(); denied.id = -DIALOG;
        denied.banned_rights = new TLRPC.TL_chatBannedRights(); denied.banned_rights.view_messages = true;
        response.chats.add(denied); network.reply(response, null); AndroidUtilities.drain();
        check(changed.calls == 1 && changed.error != null && changed.loaded == null,
                "fresh read restriction did not stop an in-flight broadcast load");
        check(network.pending.isEmpty() && AndroidUtilities.pendingTimers() == 0, "permission rejection leaked resources");
        reset(false); channel(false);
        Result failed = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(1, failed); AndroidUtilities.drain();
        TLRPC.TL_error error = new TLRPC.TL_error(); error.code = 400; error.text = "CHANNEL_PRIVATE";
        network.reply(null, error); AndroidUtilities.drain();
        check(failed.calls == 1 && failed.error.contains("CHANNEL_PRIVATE") && failed.loaded == null,
                "server read denial yielded a successful range");
    }

    private static void migration() {
        for (int variant = 0; variant < 3; variant++) {
            reset(false); channel(true);
            if (variant != 0) {
                TLRPC.ChatFull full = new TLRPC.ChatFull();
                full.migrated_from_chat_id = variant == 1 ? 7 : 0;
                controller.fullChats.put(-DIALOG, full);
            }
            Result result = new Result();
            new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(2, result); AndroidUtilities.drain();
            reply(channelMessage(9, now() - 1, "超级群文字")); reply();
            check(result.calls == 1 && result.error == null && result.loaded.complete == (variant == 2),
                    "megagroup migration completion was changed for variant " + variant);
        }
    }

    private static void autoDeleteText() {
        final int serverNow = 1_800_000_000;
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Calendar day = Calendar.getInstance(); day.setTimeInMillis(serverNow * 1000L);
            day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
            final int midnight = (int) (day.getTimeInMillis() / 1000L);
            for (boolean topic : new boolean[] {false, true}) {
                for (boolean today : new boolean[] {false, true}) {
                    reset(topic); network.currentTimeOverride = serverNow;
                    TLRPC.Message newest = message(100, serverNow - 60, "今日启用自动删除的新文字");
                    newest.ttl_period = 7 * 24 * 60 * 60;
                    TLRPC.Message recent = message(99, serverNow - 120, "今日仍然可见的新文字");
                    recent.ttl_period = 7 * 24 * 60 * 60;
                    TLRPC.Message old = message(10, midnight - 1, "昨日尚未启用自动删除的旧文字");
                    if (topic) {
                        for (TLRPC.Message message : new TLRPC.Message[] {newest, recent, old}) {
                            message.reply_to = new TLRPC.TL_messageReplyHeader();
                            message.reply_to.forum_topic = true; message.reply_to.reply_to_top_id = 42;
                        }
                    }
                    Result result = new Result();
                    SummaryHistoryLoader loader = new SummaryHistoryLoader(ACCOUNT, DIALOG, topic ? 42 : 0);
                    if (today) loader.loadToday(result); else loader.loadRecent(2, result);
                    AndroidUtilities.drain();
                    int offsetDate = topic ? ((TLRPC.TL_messages_getReplies) network.next().request).offset_date
                            : ((TLRPC.TL_messages_getHistory) network.next().request).offset_date;
                    check(offsetDate == (today ? serverNow + 1 : 0), "wrong server-time date bound");
                    // This corpus has independent dates, not dates manufactured from the request.
                    reply(old, newest, recent);
                    check(result.calls == 1 && result.error == null && result.loaded.complete,
                            "live auto-delete text failed topic=" + topic + " today=" + today);
                    check(result.loaded.messages.size() == 2 && result.loaded.messages.get(0).id == 99
                                    && result.loaded.messages.get(1).id == 100,
                            "new auto-delete messages were replaced with old history or an empty today");
                    check(result.loaded.coverageNote.contains("Asia/Shanghai"), "phone timezone was lost");
                }
            }
            reset(false); network.currentTimeOverride = serverNow;
            TLRPC.Message expired = message(101, serverNow - 10, "截止此秒已经过期"); expired.ttl_period = 10;
            TLRPC.Message live = message(100, serverNow - 20, "仍未到期"); live.ttl_period = 21;
            Result remaining = new Result();
            new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(1, remaining); AndroidUtilities.drain();
            reply(expired, live);
            check(remaining.calls == 1 && remaining.error == null && remaining.loaded.messages.size() == 1
                    && remaining.loaded.messages.get(0).id == 100, "expired auto-delete text was sent to the model");
        } finally { TimeZone.setDefault(previous); }
    }

    private static void serverClock() {
        reset(false);
        // Emulate Telegram's corrected clock being ahead of the host wall clock.
        final int serverNow = now() + 2 * 24 * 60 * 60;
        network.currentTimeOverride = serverNow;
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(2, result); AndroidUtilities.drain();
        TLRPC.TL_messages_getHistory request = (TLRPC.TL_messages_getHistory) network.next().request;
        check(request.offset_date == 0 && request.offset_id == 0, "recent messages were date-anchored to the device clock");
        reply(message(100, serverNow - 1, "服务器最新文字"), message(99, serverNow - 2, "服务器次新文字"),
                message(10, now() - 1, "手机慢时钟所在的旧文字"));
        check(result.calls == 1 && result.error == null && result.loaded.messages.get(0).id == 99
                && result.loaded.messages.get(1).id == 100, "server-new messages were discarded as future phone time");
    }

    private static void noForwards() {
        reset(false); controller.getChat(-DIALOG).noforwards = true;
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(1, result); AndroidUtilities.drain();
        TLRPC.Message text = message(100, now() - 1, "可阅读但禁止转发的普通文字"); text.noforwards = true;
        reply(text);
        check(result.calls == 1 && result.error == null && result.loaded.complete
                && result.loaded.messages.size() == 1 && result.loaded.messages.get(0).id == 100,
                "no-forwards was incorrectly treated as denied read access");
    }

    private static TLRPC.Chat channel(boolean megagroup) {
        TLRPC.Chat chat = new TLRPC.TL_channel(); chat.id = -DIALOG; chat.title = "只读频道";
        chat.megagroup = megagroup; chat.access_hash = 12345; controller.chats.put(chat.id, chat); return chat;
    }

    private static TLRPC.Message channelMessage(int id, int date, String text) {
        TLRPC.Message message = message(id, date, text);
        message.peer_id = new TLRPC.TL_peerChannel(); message.peer_id.channel_id = -DIALOG; return message;
    }

    private static void reset(boolean forum) {
        AndroidUtilities.reset(); network.reset();
        controller.chats.clear(); controller.users.clear(); controller.fullChats.clear(); controller.inputPeerOverrides.clear();
        UserConfig.getInstance(ACCOUNT).setClientUserId(1000);
        TLRPC.Chat chat = new TLRPC.TL_chat(); chat.id = -DIALOG; chat.title = "Test group"; chat.forum = forum;
        controller.chats.put(chat.id, chat);
        TLRPC.User user = new TLRPC.User(); user.id = 55; user.first_name = "Test sender";
        controller.users.put(user.id, user);
    }
    private static TLRPC.Peer peer() { TLRPC.Peer peer = new TLRPC.TL_peerChat(); peer.chat_id = -DIALOG; return peer; }
    private static int now() { return (int) (System.currentTimeMillis() / 1000L); }
    private static TLRPC.Message message(int id, int date, String text) {
        TLRPC.Message message = new TLRPC.TL_message(); message.id = id; message.date = date; message.message = text;
        message.peer_id = peer(); message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = 55; return message;
    }
    private static TLRPC.Message topicMessage(int id, int date, String text) {
        TLRPC.Message message = message(id, date, text); message.reply_to = new TLRPC.TL_messageReplyHeader();
        message.reply_to.forum_topic = true; message.reply_to.reply_to_top_id = 42; return message;
    }
    private static TLRPC.TL_messages_messages page(TLRPC.Message... messages) {
        TLRPC.TL_messages_messages response = new TLRPC.TL_messages_messages();
        java.util.Collections.addAll(response.messages, messages); return response;
    }
    private static void reply(TLRPC.Message... messages) { network.reply(page(messages), null); AndroidUtilities.drain(); }
    private static void test(String name, Runnable action) { action.run(); passed++; System.out.println("PASS " + name); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
