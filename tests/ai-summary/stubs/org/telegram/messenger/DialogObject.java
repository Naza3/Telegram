package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public final class DialogObject {
    public static boolean isChatDialog(long id) { return id < 0; }
    public static boolean isEncryptedDialog(long id) { return (id >> 32) != -1 && (id >> 32) != 0; }
    public static long getPeerDialogId(TLRPC.Peer peer) {
        if (peer == null) return 0;
        if (peer.chat_id != 0) return -peer.chat_id;
        if (peer.channel_id != 0) return -peer.channel_id;
        return peer.user_id;
    }
}
