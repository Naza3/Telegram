package org.telegram.messenger;import org.telegram.tgnet.TLRPC;
public final class ChatObject {public static boolean isKickedFromChat(TLRPC.Chat c){return c.kicked;}public static boolean isChannel(TLRPC.Chat c){return c.channel;}}
