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
        System.out.println("SummaryHistoryLoaderTest: " + passed + " passed, " + assertions + " assertions");
    }

    private static void recent() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(ACCOUNT, DIALOG, 0).loadRecent(3, result);
        AndroidUtilities.drain();
        check(network.next().request instanceof TLRPC.TL_messages_getHistory, "expected ordinary history request");
        TLRPC.TL_messages_getHistory first = (TLRPC.TL_messages_getHistory) network.next().request;
        check(first.limit == 100 && first.offset_id == 0, "wrong initial pagination");
        int now = first.offset_date - 1;
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
        int now = first.offset_date - 1;
        TLRPC.Message link = topicMessage(300, now, "Read https://example.invalid/source");
        link.media = new TLRPC.TL_messageMediaWebPage();
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

    private static void reset(boolean forum) {
        AndroidUtilities.reset(); network.reset();
        controller.chats.clear(); controller.users.clear(); controller.fullChats.clear();
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
