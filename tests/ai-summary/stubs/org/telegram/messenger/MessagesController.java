package org.telegram.messenger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.telegram.tgnet.TLRPC;

public final class MessagesController {
    private static final MessagesController[] INSTANCES = new MessagesController[4];
    private static final android.content.MemoryPreferences[] PREFERENCES = new android.content.MemoryPreferences[4];
    public final Map<Long, TLRPC.Chat> chats = new HashMap<>();
    public final Map<Long, TLRPC.User> users = new HashMap<>();
    public final Map<Long, TLRPC.ChatFull> fullChats = new HashMap<>();
    public final Map<Long, TLRPC.InputPeer> inputPeerOverrides = new HashMap<>();
    public static MessagesController getInstance(int account) {
        if (INSTANCES[account] == null) INSTANCES[account] = new MessagesController();
        return INSTANCES[account];
    }
    public static android.content.MemoryPreferences getMainSettings(int account) {
        if (PREFERENCES[account] == null) PREFERENCES[account] = new android.content.MemoryPreferences();
        return PREFERENCES[account];
    }
    public TLRPC.Chat getChat(long id) { return chats.get(id); }
    public TLRPC.User getUser(long id) { return users.get(id); }
    public TLRPC.ChatFull getChatFull(long id) { return fullChats.get(id); }
    public TLRPC.InputPeer getInputPeer(long id) {
        if (inputPeerOverrides.containsKey(id)) return inputPeerOverrides.get(id);
        TLRPC.InputPeer peer;
        if (id < 0 && ChatObject.isChannel(getChat(-id))) {
            peer = new TLRPC.TL_inputPeerChannel(); peer.channel_id = -id;
            peer.access_hash = getChat(-id).access_hash;
        } else if (id < 0) {
            peer = new TLRPC.TL_inputPeerChat(); peer.chat_id = -id;
        } else {
            peer = new TLRPC.TL_inputPeerUser(); peer.user_id = id;
        }
        return peer;
    }
    public void putUsers(ArrayList<TLRPC.User> values, boolean fromCache) {
        for (TLRPC.User value : values) users.put(value.id, value);
    }
    public void putChats(ArrayList<TLRPC.Chat> values, boolean fromCache) {
        for (TLRPC.Chat value : values) chats.put(value.id, value);
    }
}
