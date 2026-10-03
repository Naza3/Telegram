package org.telegram.messenger.ai;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;

/** Real loader, queued RPC transport, and an independent ordered-history window simulator. */
public final class SummaryHistoryRangeTest {
    private static final long DIALOG = -100;
    private static final ConnectionsManager network = ConnectionsManager.getInstance(0);
    private static final MessagesController controller = MessagesController.getInstance(0);
    private static int passed;
    private static int assertions;

    private static class Result implements SummaryHistoryLoader.Callback {
        SummaryHistoryLoader.Result loaded;
        String error;
        int calls;
        final ArrayList<Integer> progress = new ArrayList<>();
        public void onLoaded(SummaryHistoryLoader.Result value) { loaded = value; calls++; }
        public void onError(String value) { error = value; calls++; }
        public void onProgress(int scanned, int textCount) { progress.add(scanned); }
    }

    public static void main(String[] args) {
        test("old backlog is read from the saved cursor, with a frozen upper ID", SummaryHistoryRangeTest::oldestBatch);
        test("sparse IDs, overlaps and filtered media preserve continuous batch coverage", SummaryHistoryRangeTest::sparsePages);
        test("a repeated forward page is partial and cannot commit a new cursor", SummaryHistoryRangeTest::stalledPage);
        test("unread upper boundary is supplied and excludes later arrivals", SummaryHistoryRangeTest::unreadBounds);
        test("service-only batches advance a checked range without inventing a summary", SummaryHistoryRangeTest::emptyTextBatch);
        test("empty and beyond-upper windows cover absent positions", SummaryHistoryRangeTest::emptyWindows);
        test("invalid and unavailable unread boundaries do not fall back to recent", SummaryHistoryRangeTest::invalidBounds);
        test("normal and General topics use forward reply windows without root skips", SummaryHistoryRangeTest::topicWindows);
        test("foreign topic and peer responses abort even when their text is filtered", SummaryHistoryRangeTest::foreignScope);
        test("later RPC failure and timeout never return a committable partial success", SummaryHistoryRangeTest::failure);
        test("bounded page scanning is distinguishable from an intentional complete batch", SummaryHistoryRangeTest::pageLimit);
        test("progress cancellation suppresses completion and further pages", SummaryHistoryRangeTest::progressCancel);
        test("metadata resolves IDs, explicit mentions and only known reply senders", SummaryHistoryRangeTest::metadata);
        test("account identity is checked across the snapshot and forward requests", SummaryHistoryRangeTest::accountChange);
        test("server-time auto-delete messages survive since, unread and fixed-range batches", SummaryHistoryRangeTest::autoDeleteWindows);
        System.out.println("SummaryHistoryRangeTest: " + passed + " passed, " + assertions + " assertions");
    }

    private static void oldestBatch() {
        reset(false);
        ArrayList<TLRPC.Message> corpus = new ArrayList<>();
        for (int id = 1; id <= 1000; id++) corpus.add(message(id, "m" + id));
        Result first = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadSince(10, 3, first);
        AndroidUtilities.drain();
        TLRPC.TL_messages_getHistory probe = history();
        check(probe.limit == 1 && probe.offset_id == 0 && probe.offset_date == 0, "missing latest-server fixed-upper probe");
        serve(corpus);
        TLRPC.TL_messages_getHistory next = history();
        check(next.offset_id == 11 && next.add_offset == -3 && next.limit == 3, "wrong oldest-first window");
        check(next.max_id == 0 && next.min_id == 0, "server min/max filters could move the forward window");
        corpus.add(message(1001, "arrived during load"));
        serve(corpus);
        checkComplete(first, 13, true);
        check(first.loaded.upperInclusiveId == 1000 && first.loaded.lowerExclusiveId == 10, "snapshot changed with new arrivals");
        check(ids(first.loaded).equals("11,12,13"), "newest-N skipped the backlog");
        check(first.loaded.scannedMessageCount == 3 && first.progress.get(first.progress.size() - 1) == 3, "missing raw progress");

        Result second = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(13, 1000, 3, second);
        AndroidUtilities.drain(); serve(corpus);
        checkComplete(second, 16, true);
        check(ids(second.loaded).equals("14,15,16"), "next batch skipped or repeated the committed batch");
    }

    private static void sparsePages() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(10, 100, 5, result);
        AndroidUtilities.drain();
        reply(message(20, "twenty"), message(11, "eleven"), message(12, "twelve"));
        check(result.calls == 0 && history().offset_id == 21 && history().limit == 2, "short sparse page lost its cursor");
        TLRPC.Message media = message(25, "filtered caption"); media.media = new TLRPC.TL_messageMediaPhoto();
        reply(message(20, "duplicate"), media);
        check(result.calls == 0 && history().offset_id == 26 && history().limit == 1, "overlap counted against batch size");
        reply(message(40, "forty"));
        checkComplete(result, 40, true);
        check(ids(result.loaded).equals("11,12,20,40"), "sparse IDs must not be treated as missing messages");
        check(result.loaded.scannedMessageCount == 5, "media must count toward the complete raw batch");
    }

    private static void stalledPage() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(10, 100, 3, result);
        AndroidUtilities.drain(); reply(message(11, "read but not committed"));
        reply(message(11, "repeated page"));
        check(result.error == null && result.loaded.partial && result.loaded.truncated, "stalled page was called complete");
        check(!result.loaded.complete && result.loaded.coveredThroughId == 10 && result.loaded.hasMore,
                "incomplete reads must preserve the old cursor");
        check(network.pending.isEmpty() && AndroidUtilities.pendingTimers() == 0, "partial result leaked work");
    }

    private static void unreadBounds() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadUnread(10, 20, result);
        AndroidUtilities.drain();
        check(history().offset_id == 11 && history().offset_date == 0, "unread mode replaced the captured upper bound with a probe");
        TLRPC.Message captured = message(20, "already in unread snapshot"); captured.date += 1000;
        reply(message(21, "later arrival"), captured, message(11, "first unread"));
        checkComplete(result, 20, false);
        check(ids(result.loaded).equals("11,20") && result.loaded.upperInclusiveId == 20, "unread bounds were not preserved");
        check(network.sent.size() == 1, "unread fetched an unnecessary new upper bound");
    }

    private static void emptyTextBatch() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(0, 3, 2, result);
        AndroidUtilities.drain();
        TLRPC.Message photo = message(2, "caption"); photo.media = new TLRPC.TL_messageMediaPhoto();
        reply(photo, service(1));
        checkComplete(result, 2, true);
        check(result.loaded.messages.isEmpty() && result.loaded.scannedMessageCount == 2, "empty text batch lost service/media coverage");
        check(result.loaded.coverageNote.contains("实际纳入 0 条"), "coverage must not claim a model summarized hidden text");
        Result finalBatch = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(2, 3, 2, finalBatch);
        AndroidUtilities.drain(); reply(service(3));
        checkComplete(finalBatch, 3, false);
    }

    private static void emptyWindows() {
        reset(false);
        Result empty = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(10, 20, 5, empty);
        AndroidUtilities.drain(); reply();
        checkComplete(empty, 20, false);
        check(empty.loaded.messages.isEmpty() && empty.loaded.scannedMessageCount == 0, "empty range invented content");
        Result newer = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(10, 20, 5, newer);
        AndroidUtilities.drain(); reply(message(25, "past upper bound"));
        checkComplete(newer, 20, false);
        check(newer.loaded.messages.isEmpty(), "future message entered a fixed range");
        Result none = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadSince(30, 5, none);
        AndroidUtilities.drain(); reply(message(29, "newest visible message was deleted"));
        checkComplete(none, 30, false);
        check(none.loaded.upperInclusiveId == 30, "empty incremental range moved cursor backwards");
    }

    private static void invalidBounds() {
        reset(false);
        Result unavailable = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadUnread(10, -1, unavailable);
        AndroidUtilities.drain();
        check(unavailable.error != null && network.sent.isEmpty(), "unknown unread boundary silently fell back to latest");
        Result inverted = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(20, 10, 5, inverted);
        AndroidUtilities.drain(); check(inverted.error != null, "inverted range accepted");
        Result count = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(10, 20, 501, count);
        AndroidUtilities.drain(); check(count.error != null, "unbounded raw batch accepted");
        Result same = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(20, 20, 5, same);
        AndroidUtilities.drain(); checkComplete(same, 20, false);
        check(network.sent.isEmpty(), "empty range performed unnecessary RPC");
        Result max = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1, max);
        AndroidUtilities.drain();
        check(history().offset_id == Integer.MAX_VALUE, "forward cursor overflow");
        reply(message(Integer.MAX_VALUE, "last representable position"));
        checkComplete(max, Integer.MAX_VALUE, false);
    }

    private static void topicWindows() {
        reset(true);
        Result topic = new Result();
        new SummaryHistoryLoader(0, DIALOG, 42).loadRange(42, 60, 2, topic);
        AndroidUtilities.drain();
        TLRPC.TL_messages_getReplies request = (TLRPC.TL_messages_getReplies) network.next().request;
        check(request.msg_id == 42 && request.offset_id == 43 && request.add_offset == -2, "wrong forward Topic window");
        TLRPC.Message root = service(42); root.action = new TLRPC.TL_messageActionTopicCreate();
        reply(root, topicMessage(51, 42), topicMessage(50, 42));
        checkComplete(topic, 51, true);
        check(ids(topic.loaded).equals("50,51"), "topic anchor skipped older replies");
        Result general = new Result();
        new SummaryHistoryLoader(0, DIALOG, 1).loadRange(0, 4, 2, general);
        AndroidUtilities.drain();
        request = (TLRPC.TL_messages_getReplies) network.next().request;
        check(request.msg_id == 1 && request.offset_id == 1, "General did not start at the beginning");
        reply(message(2, "General text"), service(1));
        checkComplete(general, 2, true);
        check(general.loaded.scannedMessageCount == 2 && ids(general.loaded).equals("2"), "General creation did not count as a covered position");
    }

    private static void foreignScope() {
        reset(true);
        Result result = new Result();
        new SummaryHistoryLoader(0, DIALOG, 42).loadRange(42, 60, 2, result);
        AndroidUtilities.drain();
        TLRPC.Message photo = topicMessage(50, 99); photo.media = new TLRPC.TL_messageMediaPhoto();
        reply(photo);
        check(result.error != null && result.loaded == null, "filtered wrong-topic messages advanced the cursor");
        Result peer = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(42, 60, 2, peer);
        AndroidUtilities.drain();
        TLRPC.Message foreign = message(50, "other group"); foreign.peer_id.chat_id = 101;
        reply(foreign);
        check(peer.error != null && peer.loaded == null, "foreign peer mixed with group history");
    }

    private static void failure() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(10, 20, 5, result);
        AndroidUtilities.drain(); reply(message(11, "first page"));
        TLRPC.TL_error error = new TLRPC.TL_error(); error.code = 500; error.text = "SERVER_ERROR";
        network.reply(null, error); AndroidUtilities.drain();
        check(result.error != null && result.loaded == null, "later RPC error returned a successful smaller batch");
        Result timeout = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(10, 20, 5, timeout);
        AndroidUtilities.drain(); AndroidUtilities.fireTimers();
        check(timeout.error != null && timeout.loaded == null && network.pending.isEmpty(), "timeout returned a committable result");
    }

    private static void pageLimit() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(10, 1000, 500, result);
        AndroidUtilities.drain();
        for (int id = 11; id <= 110; id++) reply(message(id, "short page"));
        check(result.loaded != null && !result.loaded.complete && result.loaded.partial, "scan safety limit became an intentional batch");
        check(result.loaded.coveredThroughId == 10 && result.loaded.scannedMessageCount == 100, "scan-limit result advanced the old cursor");
    }

    private static void progressCancel() {
        reset(false);
        SummaryHistoryLoader loader = new SummaryHistoryLoader(0, DIALOG, 0);
        Result result = new Result() {
            public void onProgress(int scanned, int textCount) { super.onProgress(scanned, textCount); loader.cancel(); }
        };
        loader.loadRange(10, 20, 5, result);
        AndroidUtilities.drain(); reply(message(11, "cancel from progress"));
        check(result.calls == 0 && network.sent.size() == 1 && network.pending.isEmpty(), "progress cancellation started another page");
        check(AndroidUtilities.pendingTimers() == 0, "progress cancellation leaked timeout");
    }

    private static void metadata() {
        reset(false);
        TLRPC.User self = new TLRPC.User(); self.id = 1000; self.first_name = "Same name"; self.username = "Owner";
        TLRPC.TL_username alias = new TLRPC.TL_username(); alias.username = "OwnerAlias"; alias.active = true;
        self.usernames.add(alias); controller.users.put(self.id, self);
        controller.users.get(55L).first_name = "Same name";
        Result result = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(0, 10, 10, result);
        AndroidUtilities.drain();
        TLRPC.Message own = message(1, "own photo caption"); own.from_id.user_id = 1000; own.out = true;
        own.media = new TLRPC.TL_messageMediaPhoto();
        TLRPC.Message replySelf = replying(message(2, "reply to your photo"), 1); replySelf.edit_date = 1234;
        TLRPC.Message sameName = message(3, "Same name must not count as an explicit mention");
        TLRPC.Message byId = message(4, "Same name");
        TLRPC.TL_messageEntityMentionName nameEntity = new TLRPC.TL_messageEntityMentionName(); nameEntity.user_id = 1000;
        byId.entities.add(nameEntity);
        TLRPC.Message otherId = message(5, "Same name");
        TLRPC.TL_messageEntityMentionName otherEntity = new TLRPC.TL_messageEntityMentionName(); otherEntity.user_id = 55;
        otherId.entities.add(otherEntity);
        TLRPC.Message username = mentioned(message(6, "@oWnEr check this"), 6); username.mentioned = true;
        TLRPC.Message oldOwner = mentioned(message(7, "@Owner historical ownership unknown"), 6);
        TLRPC.Message usernameAlias = mentioned(message(8, "@OwnerAlias check this"), 11); usernameAlias.mentioned = true;
        TLRPC.Message outside = replying(message(9, "unknown replied sender"), 99);
        TLRPC.Message foreign = replying(message(10, "reply into another chat"), 1);
        foreign.reply_to.reply_to_peer_id = peer(); foreign.reply_to.reply_to_peer_id.chat_id = 200;
        reply(own, replySelf, sameName, byId, otherId, username, oldOwner, usernameAlias, outside, foreign);
        checkComplete(result, 10, false);
        SummaryMessage m2 = find(result.loaded, 2);
        check(m2.senderId == 55 && m2.replyToId == 1 && m2.replyToDialogId == DIALOG, "peer/reply IDs were guessed from names");
        check(m2.replyToSelfKnown && m2.replyToSelf && m2.editDate == 1234, "known filtered parent did not resolve reply sender");
        check(!find(result.loaded, 3).mentionedSelf && find(result.loaded, 4).mentionedSelf
                && !find(result.loaded, 5).mentionedSelf, "same-name users confused explicit mentions");
        check(find(result.loaded, 6).mentionedSelf && !find(result.loaded, 7).mentionedSelf
                && find(result.loaded, 8).mentionedSelf, "username entity or owner-notification evidence not respected");
        check(!find(result.loaded, 9).replyToSelfKnown && !find(result.loaded, 10).replyToSelfKnown,
                "missing/out-of-dialog replied sender was guessed");
        SummaryMessage legacy = new SummaryMessage(DIALOG, 99, now(), "legacy", "text");
        check(legacy.senderId == 0 && legacy.replyToId == 0 && !legacy.mentionedSelf
                && !legacy.replyToSelfKnown, "legacy constructor invented metadata");
    }

    private static void accountChange() {
        reset(false);
        Result result = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadSince(10, 5, result);
        AndroidUtilities.drain(); reply(message(20, "fixed upper"));
        check(history().offset_id == 11, "did not enter forward load");
        UserConfig.getInstance(0).setClientUserId(2000);
        reply(message(11, "old account data"));
        check(result.error != null && result.loaded == null, "snapshot and page mixed account owners");
    }

    private static void autoDeleteWindows() {
        reset(false);
        final int serverNow = 1_800_000_000;
        network.currentTimeOverride = serverNow;
        ArrayList<TLRPC.Message> corpus = new ArrayList<>();
        TLRPC.Message old = message(9, "旧文字"); old.date = serverNow - 86400;
        TLRPC.Message first = message(11, "尚未到期的新文字1");
        first.date = serverNow - 120; first.ttl_period = 604800;
        TLRPC.Message second = message(12, "尚未到期的新文字2");
        second.date = serverNow - 60; second.ttl_period = 604800;
        TLRPC.Message later = message(13, "固定未读上界之后的文字"); later.date = serverNow - 20;
        TLRPC.Message expired = message(14, "刚到自动删除期限的文字");
        expired.date = serverNow - 10; expired.ttl_period = 10;
        java.util.Collections.addAll(corpus, old, first, second, later, expired);

        Result since = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadSince(10, 2, since); AndroidUtilities.drain();
        check(history().offset_id == 0 && history().offset_date == 0 && history().limit == 1,
                "since snapshot must start at the actual newest server ID");
        serve(corpus); serve(corpus);
        checkComplete(since, 12, true);
        check(since.loaded.upperInclusiveId == 14 && ids(since.loaded).equals("11,12"),
                "since skipped live auto-delete text or changed the server snapshot");

        Result unread = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadUnread(10, 12, 5, unread); AndroidUtilities.drain();
        check(history().offset_id == 11 && history().limit == 5, "unread inserted a latest-ID probe");
        serve(corpus); checkComplete(unread, 12, false);
        check(ids(unread.loaded).equals("11,12") && unread.loaded.upperInclusiveId == 12,
                "unread omitted live TTL text or crossed the captured upper bound");

        Result range = new Result();
        new SummaryHistoryLoader(0, DIALOG, 0).loadRange(12, 14, 3, range); AndroidUtilities.drain();
        serve(corpus); checkComplete(range, 14, false);
        check(ids(range.loaded).equals("13") && range.loaded.scannedMessageCount == 2,
                "expired text must be excluded while its raw history position remains covered");
    }

    private static void reset(boolean forum) {
        AndroidUtilities.reset(); network.reset();
        controller.chats.clear(); controller.users.clear(); controller.fullChats.clear();
        UserConfig.getInstance(0).setClientUserId(1000);
        TLRPC.Chat chat = new TLRPC.TL_chat(); chat.id = -DIALOG; chat.title = "Test"; chat.forum = forum;
        controller.chats.put(chat.id, chat);
        TLRPC.User sender = new TLRPC.User(); sender.id = 55; sender.first_name = "Sender";
        controller.users.put(sender.id, sender);
    }
    private static int now() { return (int) (System.currentTimeMillis() / 1000L) - 1; }
    private static TLRPC.Peer peer() { TLRPC.Peer p = new TLRPC.TL_peerChat(); p.chat_id = -DIALOG; return p; }
    private static TLRPC.Message message(int id, String text) {
        TLRPC.Message m = new TLRPC.TL_message(); m.id = id; m.date = now(); m.message = text; m.peer_id = peer();
        m.from_id = new TLRPC.TL_peerUser(); m.from_id.user_id = 55; return m;
    }
    private static TLRPC.Message service(int id) {
        TLRPC.Message m = new TLRPC.TL_messageService(); m.id = id; m.date = now(); m.peer_id = peer(); return m;
    }
    private static TLRPC.Message topicMessage(int id, int topic) {
        TLRPC.Message m = message(id, "topic message"); m.reply_to = new TLRPC.TL_messageReplyHeader();
        m.reply_to.forum_topic = true; m.reply_to.reply_to_top_id = topic; return m;
    }
    private static TLRPC.Message replying(TLRPC.Message m, int id) {
        m.reply_to = new TLRPC.TL_messageReplyHeader(); m.reply_to.reply_to_msg_id = id; return m;
    }
    private static TLRPC.Message mentioned(TLRPC.Message m, int length) {
        TLRPC.TL_messageEntityMention e = new TLRPC.TL_messageEntityMention(); e.offset = 0; e.length = length;
        m.entities.add(e); return m;
    }
    private static TLRPC.TL_messages_getHistory history() {
        return (TLRPC.TL_messages_getHistory) network.next().request;
    }
    private static void reply(TLRPC.Message... messages) {
        TLRPC.TL_messages_messages page = new TLRPC.TL_messages_messages();
        Collections.addAll(page.messages, messages); network.reply(page, null); AndroidUtilities.drain();
    }
    private static void serve(ArrayList<TLRPC.Message> corpus) {
        TLRPC.TL_messages_getHistory req = history();
        ArrayList<TLRPC.Message> ordered = new ArrayList<>(corpus);
        ordered.sort((a, b) -> Integer.compare(b.id, a.id));
        int index = 0;
        while (index < ordered.size() && ((req.offset_id > 0 && ordered.get(index).id >= req.offset_id)
                || (req.offset_id == 0 && req.offset_date > 0 && ordered.get(index).date >= req.offset_date))) index++;
        int start = Math.max(0, index + req.add_offset);
        TLRPC.TL_messages_messagesSlice page = new TLRPC.TL_messages_messagesSlice();
        for (int i = start; i < Math.min(ordered.size(), start + req.limit); i++) page.messages.add(ordered.get(i));
        network.reply(page, null); AndroidUtilities.drain();
    }
    private static String ids(SummaryHistoryLoader.Result result) {
        StringBuilder out = new StringBuilder();
        for (SummaryMessage m : result.messages) { if (out.length() > 0) out.append(','); out.append(m.id); }
        return out.toString();
    }
    private static SummaryMessage find(SummaryHistoryLoader.Result result, int id) {
        for (SummaryMessage m : result.messages) if (m.id == id) return m;
        throw new AssertionError("Missing message " + id);
    }
    private static void checkComplete(Result result, int through, boolean more) {
        check(result.calls == 1 && result.error == null && result.loaded != null, "missing complete result: " + result.error);
        check(result.loaded.complete && !result.loaded.truncated && !result.loaded.partial, "intentional complete batch marked partial");
        check(result.loaded.coveredThroughId == through && result.loaded.hasMore == more, "incorrect batch endpoint or remaining flag");
    }
    private static void test(String name, Runnable action) { action.run(); passed++; System.out.println("PASS " + name); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
