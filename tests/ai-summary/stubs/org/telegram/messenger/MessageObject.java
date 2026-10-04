package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public final class MessageObject {
    public int currentAccount;
    public TLRPC.Message messageOwner;
    public boolean scheduled;
    public MessageObject(int account, TLRPC.Message message, boolean generateLayout, boolean checkMedia) {
        currentAccount = account;
        messageOwner = message;
    }
    public long getDialogId() {
        return messageOwner.dialog_id != 0 ? messageOwner.dialog_id : DialogObject.getPeerDialogId(messageOwner.peer_id);
    }
    public static long getTopicId(int account, TLRPC.Message message, boolean forum) {
        if (message.action instanceof TLRPC.TL_messageActionTopicCreate) return message.id;
        if (message.reply_to == null || !message.reply_to.forum_topic) return forum ? 1 : 0;
        return message.reply_to.reply_to_top_id != 0 ? message.reply_to.reply_to_top_id : message.reply_to.reply_to_msg_id;
    }
    public static boolean isEphemeral(TLRPC.Message message) { return false; }
}
