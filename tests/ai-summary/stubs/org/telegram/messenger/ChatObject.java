package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public final class ChatObject {
    public static boolean isChannel(TLRPC.Chat chat) { return chat instanceof TLRPC.TL_channel; }
    public static boolean isChannelAndNotMegaGroup(TLRPC.Chat chat) { return isChannel(chat) && !chat.megagroup; }
    public static boolean isForum(TLRPC.Chat chat) { return chat != null && chat.forum; }
}
