package org.telegram.messenger.ai;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;

/** Exercises the production selection factory; every case asserts there was no Telegram RPC. */
public final class SummarySelectedSnapshotTest {
    private static final int ACCOUNT = 0;
    private static final long OWNER = 1000, DIALOG = -100;
    private static final MessagesController controller = MessagesController.getInstance(ACCOUNT);
    private static final ConnectionsManager network = ConnectionsManager.getInstance(ACCOUNT);
    private static int cases, assertions;

    public static void main(String[] args) {
        test("exact noncontiguous selection and defensive immutable sources", SummarySelectedSnapshotTest::exactSelection);
        test("snapshot identity and mutated compatibility list are fenced", SummarySelectedSnapshotTest::identity);
        test("invalid accounts and account-slot reuse are rejected", SummarySelectedSnapshotTest::accounts);
        test("wrong dialog, cached dialog and mixed-account objects are rejected", SummarySelectedSnapshotTest::scopes);
        test("unreadable, migrated and unsupported chats are rejected", SummarySelectedSnapshotTest::unavailable);
        test("forum General and explicit topic scopes use actual metadata", SummarySelectedSnapshotTest::topics);
        test("all-topic selection preserves each selected topic", SummarySelectedSnapshotTest::allTopics);
        test("pending, scheduled, secret and media input never becomes text", SummarySelectedSnapshotTest::ineligible);
        test("ordinary auto-delete expires at Telegram's adjusted time", SummarySelectedSnapshotTest::ttl);
        test("reply identity and quote use selected messages only", SummarySelectedSnapshotTest::replies);
        test("self mention uses actual UID rather than display name", SummarySelectedSnapshotTest::mentions);
        test("no-forwards remains readable", SummarySelectedSnapshotTest::protectedText);
        test("broadcast peer type must match the selected chat", SummarySelectedSnapshotTest::peerType);
        test("empty, excessive, duplicate and malformed selections are rejected", SummarySelectedSnapshotTest::limits);
        System.out.println("SummarySelectedSnapshotTest: " + cases + " passed, " + assertions + " assertions");
    }

    private static void exactSelection() {
        MessageObject later = object(99, "selected later"), earlier = object(10, "selected earlier");
        later.messageOwner.date = now() - 1; earlier.messageOwner.date = now() - 20;
        ArrayList<MessageObject> selected = new ArrayList<>(Arrays.asList(later, earlier));
        SummaryHistoryLoader.Result result = snapshot(0, selected);
        selected.clear(); later.messageOwner.message = "edited after freezing";
        check(result.messages.size() == 2 && result.messages.get(0).id == 10
                && result.messages.get(1).id == 99, "selection was broadened or reordered incorrectly");
        check(result.messages.get(1).text.equals("selected later"), "mutable TL body leaked into snapshot");
        check(result.messages.get(0).sender.equals("Alice"), "display sender missing");
        check(result.complete && !result.hasMore && !result.partial && !result.truncated, "selection completeness wrong");
        check(result.lowerExclusiveId == 0 && result.upperInclusiveId == 0 && result.coveredThroughId == 0,
                "selection gaps claim continuous history coverage");
        check(result.scannedMessageCount == 2 && result.coverageNote.contains("#10")
                && result.coverageNote.contains("#99") && result.coverageNote.contains("不补抓"), "selection coverage missing");
    }

    private static void identity() {
        SummaryHistoryLoader.Result result = snapshot(0, object(10, "text"));
        check(result.selectedSnapshot && result.matchesSelectedScope(ACCOUNT, OWNER, DIALOG, 0), "identity not preserved");
        check(!result.matchesSelectedScope(1, OWNER, DIALOG, 0)
                && !result.matchesSelectedScope(ACCOUNT, OWNER + 1, DIALOG, 0)
                && !result.matchesSelectedScope(ACCOUNT, OWNER, DIALOG - 1, 0)
                && !result.matchesSelectedScope(ACCOUNT, OWNER, DIALOG, 1), "wrong scope accepted");
        result.messages.add(new SummaryMessage(DIALOG, 11, now(), "Other", "not selected"));
        check(!result.matchesSelectedScope(ACCOUNT, OWNER, DIALOG, 0), "altered sources retained selection authority");
    }

    private static void accounts() {
        MessageObject message = object(10, "text");
        reject(() -> SummaryHistoryLoader.snapshotSelected(-1, OWNER, DIALOG, 0, Arrays.asList(message)));
        reject(() -> SummaryHistoryLoader.snapshotSelected(UserConfig.MAX_ACCOUNT_COUNT, OWNER, DIALOG, 0, Arrays.asList(message)));
        reject(() -> SummaryHistoryLoader.snapshotSelected(ACCOUNT, 0, DIALOG, 0, Arrays.asList(message)));
        reject(() -> SummaryHistoryLoader.snapshotSelected(ACCOUNT, OWNER + 1, DIALOG, 0, Arrays.asList(message)));
        UserConfig.selectedAccount = 1; reject(() -> snapshot(0, message)); UserConfig.selectedAccount = ACCOUNT;
        UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER + 1); reject(() -> snapshot(0, message));
    }

    private static void scopes() {
        MessageObject message = object(10, "text"); message.currentAccount = 1;
        reject(() -> snapshot(0, message)); message.currentAccount = ACCOUNT;
        message.messageOwner.peer_id.chat_id = 200; reject(() -> snapshot(0, message));
        message.messageOwner.dialog_id = DIALOG; reject(() -> snapshot(0, message));
        message.messageOwner.peer_id.chat_id = 100; message.messageOwner.dialog_id = -200;
        reject(() -> snapshot(0, message));
        reject(() -> SummaryHistoryLoader.snapshotSelected(ACCOUNT, OWNER, OWNER, 0, Arrays.asList(object(11, "private chat"))));
        reject(() -> SummaryHistoryLoader.snapshotSelected(ACCOUNT, OWNER, 1L << 32, 0, Arrays.asList(object(11, "secret dialog"))));
    }

    private static void unavailable() {
        MessageObject message = object(10, "text"); TLRPC.Chat chat = controller.getChat(-DIALOG);
        chat.kicked = true; reject(() -> snapshot(0, message)); chat.kicked = false;
        chat.monoforum = true; reject(() -> snapshot(0, message)); chat.monoforum = false;
        chat.migrated_to = new TLRPC.TL_inputChannel(); reject(() -> snapshot(0, message)); chat.migrated_to = null;
        chat.banned_rights = new TLRPC.TL_chatBannedRights(); chat.banned_rights.view_messages = true;
        reject(() -> snapshot(0, message)); controller.chats.clear(); reject(() -> snapshot(0, message));
    }

    private static void topics() {
        MessageObject general = object(10, "General");
        reject(() -> snapshot(1, general)); controller.getChat(-DIALOG).forum = true;
        check(snapshot(1, general).messages.get(0).topicId == 1, "General topic lost");
        MessageObject topic = topic(12, 42, "Topic");
        check(snapshot(42, topic).messages.get(0).topicId == 42, "explicit topic lost");
        reject(() -> snapshot(42, general, topic)); reject(() -> snapshot(-1, topic));
        reject(() -> snapshot((long) Integer.MAX_VALUE + 1, topic));
    }

    private static void allTopics() {
        controller.getChat(-DIALOG).forum = true;
        SummaryHistoryLoader.Result result = snapshot(0, object(10, "General"), topic(11, 42, "Other"));
        check(result.messages.get(0).topicId == 1 && result.messages.get(1).topicId == 42,
                "all-topic selection guessed a shared topic");
        check(result.matchesSelectedScope(ACCOUNT, OWNER, DIALOG, 0), "all-topic identity incorrect");
    }

    private static void ineligible() {
        MessageObject pending = object(-1, "pending"); pending.messageOwner.send_state = 1;
        MessageObject scheduled = object(2, "scheduled"); scheduled.scheduled = true;
        MessageObject media = object(3, "photo caption"); media.messageOwner.media = new TLRPC.TL_messageMediaPhoto();
        MessageObject service = typed(new TLRPC.TL_messageService(), 4, "service");
        MessageObject secret = typed(new TLRPC.TL_message_secret(), 5, "secret");
        MessageObject oldSecret = typed(new TLRPC.TL_message_secret_layer72(), 6, "old secret");
        MessageObject link = object(7, "original linked text"); link.messageOwner.media = new TLRPC.TL_messageMediaWebPage();
        SummaryHistoryLoader.Result result = snapshot(0, pending, scheduled, media, service, secret, oldSecret, link);
        check(result.messages.size() == 1 && result.messages.get(0).text.equals("original linked text"), "ineligible body was included");
        check(result.coverageNote.contains("跳过 6 条") && result.scannedMessageCount == 7, "skipped selection not disclosed");
        reject(() -> snapshot(0, pending, scheduled, media, service, secret, oldSecret));
    }

    private static void ttl() {
        MessageObject live = object(10, "live"); live.messageOwner.date = now() - 10; live.messageOwner.ttl_period = 11;
        MessageObject expired = object(11, "expired"); expired.messageOwner.date = now() - 10; expired.messageOwner.ttl_period = 10;
        MessageObject mediaTtl = object(12, "media TTL"); mediaTtl.messageOwner.media = new TLRPC.TL_messageMediaWebPage();
        mediaTtl.messageOwner.media.ttl_seconds = 60;
        SummaryHistoryLoader.Result result = snapshot(0, live, expired, mediaTtl);
        check(result.messages.size() == 1 && result.messages.get(0).id == 10, "TTL eligibility wrong");
    }

    private static void replies() {
        MessageObject self = object(10, "selected self"); self.messageOwner.from_id.user_id = OWNER;
        MessageObject reply = object(11, "reply"); reply.messageOwner.reply_to = new TLRPC.TL_messageReplyHeader();
        reply.messageOwner.reply_to.reply_to_msg_id = 10;
        reply.messageOwner.reply_to.flags = 1 << 6; reply.messageOwner.reply_to.quote_text = "literal excerpt";
        SummaryMessage withTarget = snapshot(0, self, reply).messages.get(1);
        check(withTarget.replyToId == 10 && withTarget.replyToDialogId == DIALOG
                && withTarget.replyToSelfKnown && withTarget.replyToSelf, "selected reply sender not resolved");
        check(withTarget.quoteText.equals("literal excerpt"), "server quote lost");
        SummaryMessage withoutTarget = snapshot(0, reply).messages.get(0);
        check(!withoutTarget.replyToSelfKnown && !withoutTarget.replyToSelf, "unselected reply target inferred");
        reply.messageOwner.reply_to.quote_text = "mutated";
        check(withTarget.quoteText.equals("literal excerpt"), "mutable reply metadata leaked");
    }

    private static void mentions() {
        MessageObject message = object(10, "hello Alice");
        TLRPC.TL_messageEntityMentionName mention = new TLRPC.TL_messageEntityMentionName(); mention.user_id = OWNER;
        message.messageOwner.entities.add(mention);
        check(snapshot(0, message).messages.get(0).mentionedSelf, "true UID mention lost");
        message.messageOwner.entities.clear();
        check(!snapshot(0, message).messages.get(0).mentionedSelf, "name guessed as own mention");
    }

    private static void protectedText() {
        controller.getChat(-DIALOG).noforwards = true;
        MessageObject message = object(10, "readable protected text"); message.messageOwner.noforwards = true;
        check(snapshot(0, message).messages.size() == 1, "forward protection incorrectly blocked readable text");
    }

    private static void peerType() {
        MessageObject message = object(10, "channel text");
        message.messageOwner.peer_id = new TLRPC.TL_peerChannel(); message.messageOwner.peer_id.channel_id = -DIALOG;
        reject(() -> snapshot(0, message));
        TLRPC.Chat channel = new TLRPC.TL_channel(); channel.id = -DIALOG; controller.chats.put(channel.id, channel);
        check(snapshot(0, message).messages.size() == 1, "readable broadcast was rejected");
        reject(() -> snapshot(0, object(11, "wrong basic-group peer type")));
    }

    private static void limits() {
        reject(() -> snapshot(0, new ArrayList<>())); reject(() -> snapshot(0, (ArrayList<MessageObject>) null));
        ArrayList<MessageObject> messages = new ArrayList<>();
        for (int i = 1; i <= SummaryHistoryLoader.MAX_SELECTED_COUNT; i++) messages.add(object(i, "text"));
        check(snapshot(0, messages).messages.size() == 100, "native selection limit rejected");
        messages.add(object(101, "text")); reject(() -> snapshot(0, messages));
        MessageObject same = object(1, "text"); reject(() -> snapshot(0, same, same));
        reject(() -> snapshot(0, (MessageObject) null)); same.messageOwner.peer_id = null; reject(() -> snapshot(0, same));
    }

    private static SummaryHistoryLoader.Result snapshot(long topic, MessageObject... messages) {
        return snapshot(topic, new ArrayList<>(Arrays.asList(messages)));
    }
    private static SummaryHistoryLoader.Result snapshot(long topic, ArrayList<MessageObject> messages) {
        return SummaryHistoryLoader.snapshotSelected(ACCOUNT, OWNER, DIALOG, topic, messages);
    }
    private static MessageObject object(int id, String text) { return typed(new TLRPC.TL_message(), id, text); }
    private static MessageObject typed(TLRPC.Message message, int id, String text) {
        message.id = id; message.date = now() - 1; message.message = text;
        message.peer_id = new TLRPC.TL_peerChat(); message.peer_id.chat_id = -DIALOG;
        message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = 55;
        return new MessageObject(ACCOUNT, message, false, false);
    }
    private static MessageObject topic(int id, int topic, String text) {
        MessageObject message = object(id, text); message.messageOwner.reply_to = new TLRPC.TL_messageReplyHeader();
        message.messageOwner.reply_to.forum_topic = true; message.messageOwner.reply_to.reply_to_top_id = topic;
        return message;
    }
    private static int now() { return network.getCurrentTime(); }
    private static void test(String name, Runnable task) {
        AndroidUtilities.reset(); network.reset(); controller.chats.clear(); controller.users.clear();
        UserConfig.selectedAccount = ACCOUNT; UserConfig.getInstance(ACCOUNT).setClientUserId(OWNER);
        TLRPC.Chat chat = new TLRPC.TL_chat(); chat.id = -DIALOG; controller.chats.put(chat.id, chat);
        TLRPC.User user = new TLRPC.User(); user.id = 55; user.first_name = "Alice"; controller.users.put(user.id, user);
        task.run();
        check(network.sent.isEmpty() && network.pending.isEmpty() && AndroidUtilities.pendingTimers() == 0,
                "selection factory performed background/history work");
        cases++; System.out.println("PASS " + name);
    }
    private static void reject(Runnable task) {
        try { task.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("invalid selection accepted");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
