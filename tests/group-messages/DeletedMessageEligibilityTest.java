package org.telegram.messenger.groupmessages;

import java.util.function.Consumer;
import org.telegram.tgnet.TLRPC;

/** Tests real generated Telegram message classes, including every ephemeral boundary. */
public final class DeletedMessageEligibilityTest {
    private static int assertions;
    public static void main(String[] args) {
        TLRPC.Chat chat = group();
        check(DeletedMessageEligibility.accepts(-42, chat, message()), "ordinary group text");
        TLRPC.Message emptyAction = message(); emptyAction.action = new TLRPC.TL_messageActionEmpty();
        check(DeletedMessageEligibility.accepts(-42, chat, emptyAction), "legacy empty action");
        check(!DeletedMessageEligibility.accepts(42, chat, message()), "private dialog rejected");
        check(!DeletedMessageEligibility.accepts(-43, chat, message()), "foreign dialog rejected");
        check(!DeletedMessageEligibility.accepts(-42, null, message()), "unknown group rejected");
        rejected(m -> m.peer_id = null, "missing peer");
        rejected(m -> { m.peer_id = new TLRPC.TL_peerUser(); m.peer_id.user_id = 42; }, "private peer");
        rejected(m -> m.peer_id.chat_id = 43, "foreign group peer");
        rejected(m -> m.id = -1, "pending message");
        rejected(m -> m.date = 0, "missing date");
        rejected(m -> m.send_state = 1, "unsent message");
        rejected(m -> m.ttl_period = 60, "future normal auto-delete is also excluded");
        rejected(m -> m.ttl_period = -1, "invalid ttl is excluded");
        rejected(m -> m.ttl = 1, "secret ttl");
        rejected(m -> m.destroyTime = 1, "destroy seconds");
        rejected(m -> m.destroyTimeMillis = 1, "destroy millis");
        rejected(m -> m.expire_date = 1, "expiry");
        rejected(m -> m.ephemeralAnchorMsgId = 1, "ephemeral anchor");
        rejected(m -> m.ephemeralReceiverBotId = 1, "ephemeral receiver");
        rejected(m -> m.quick_reply_shortcut_id = 1, "quick reply");
        rejected(m -> m.message = " \n\t", "blank text");
        rejected(m -> m.message = null, "null text");
        rejected(m -> m.message = "x".repeat(32769), "oversized text");
        rejected(m -> m.action = new TLRPC.TL_messageActionChatAddUser(), "service action");
        rejected(m -> m.media = new TLRPC.TL_messageMediaPhoto(), "photo caption");
        rejected(m -> m.media = new TLRPC.TL_messageMediaDocument(), "document caption");
        rejected(m -> { m.media = new TLRPC.TL_messageMediaWebPage(); m.media.ttl_seconds = 1; }, "ttl link");
        TLRPC.Message link = message(); link.media = new TLRPC.TL_messageMediaWebPage();
        check(DeletedMessageEligibility.accepts(-42, chat, link), "ordinary link text");
        for (TLRPC.Message forbidden : new TLRPC.Message[]{new TLRPC.TL_messageService(),
                new TLRPC.TL_messageEmpty(), new TLRPC.TL_message_secret(), new TLRPC.TL_message_secret_layer72()}) {
            forbidden.id = 1; forbidden.date = 100; forbidden.message = "text"; forbidden.peer_id = message().peer_id;
            check(!DeletedMessageEligibility.accepts(-42, chat, forbidden), "forbidden type " + forbidden.getClass());
        }
        TLRPC.Chat channel = new TLRPC.TL_channel(); channel.id = 42;
        TLRPC.Message superMessage = message(); superMessage.peer_id = new TLRPC.TL_peerChannel(); superMessage.peer_id.channel_id = 42;
        check(!DeletedMessageEligibility.accepts(-42, channel, superMessage), "broadcast channel rejected");
        channel.megagroup = true;
        check(DeletedMessageEligibility.accepts(-42, channel, superMessage), "supergroup accepted");
        channel.monoforum = true;
        check(!DeletedMessageEligibility.accepts(-42, channel, superMessage), "direct channel topic excluded");
        channel.monoforum = false; channel.broadcast = true;
        check(!DeletedMessageEligibility.accepts(-42, channel, superMessage), "broadcast flags rejected");
        chat.left = true;
        check(!DeletedMessageEligibility.accepts(-42, chat, message()), "left group");
        chat.left = false; chat.kicked = true;
        check(!DeletedMessageEligibility.accepts(-42, chat, message()), "kicked group");
        chat = group(); TLRPC.Message thread = message();
        check(DeletedMessageEligibility.topicId(chat, thread) == 0, "ordinary group not a topic");
        chat.forum = true;
        check(DeletedMessageEligibility.topicId(chat, thread) == 1, "general forum topic");
        thread.reply_to = new TLRPC.TL_messageReplyHeader(); thread.reply_to.forum_topic = true;
        thread.reply_to.reply_to_msg_id = 12; thread.reply_to.reply_to_top_id = 9;
        check(DeletedMessageEligibility.topicId(chat, thread) == 9, "forum reply top");
        thread.reply_to.reply_to_top_id = 0;
        check(DeletedMessageEligibility.topicId(chat, thread) == 12, "forum root reply");
        check(DeletedMessageEligibility.clearsLocalRecords(-42, 0), "explicit delete group clears copies");
        check(DeletedMessageEligibility.clearsLocalRecords(-42, 1), "explicit clear history clears copies");
        check(DeletedMessageEligibility.clearsLocalRecords(-42, 2), "explicit clear cache clears copies");
        check(!DeletedMessageEligibility.clearsLocalRecords(-42, 3), "last-message revoke automatic empty cleanup preserves copies");
        check(!DeletedMessageEligibility.clearsLocalRecords(-42, 4), "unknown cleanup mode cannot erase copies");
        check(!DeletedMessageEligibility.clearsLocalRecords(42, 0), "private cleanup has no group copies");
        System.out.println("Deleted message native eligibility: " + assertions + " assertions passed");
    }
    private static TLRPC.Chat group() { TLRPC.Chat chat = new TLRPC.TL_chat(); chat.id = 42; return chat; }
    private static TLRPC.Message message() {
        TLRPC.Message value = new TLRPC.TL_message(); value.id = 5; value.date = 100; value.message = "普通文字 🐱";
        value.peer_id = new TLRPC.TL_peerChat(); value.peer_id.chat_id = 42; return value;
    }
    private static void rejected(Consumer<TLRPC.Message> change, String label) {
        TLRPC.Message value = message(); change.accept(value);
        check(!DeletedMessageEligibility.accepts(-42, group(), value), label);
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
