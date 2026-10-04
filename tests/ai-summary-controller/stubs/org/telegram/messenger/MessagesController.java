package org.telegram.messenger;
import android.content.MemoryPreferences;import java.util.HashMap;import org.telegram.tgnet.TLRPC;
public final class MessagesController {
 private static final MessagesController[] values={new MessagesController(),new MessagesController(),new MessagesController(),new MessagesController()};
 private static final MemoryPreferences[] prefs={new MemoryPreferences(),new MemoryPreferences(),new MemoryPreferences(),new MemoryPreferences()};
 public final HashMap<Long,TLRPC.Chat> chats=new HashMap<>();
 public static MessagesController getInstance(int a){return values[a];}
 public static MemoryPreferences getMainSettings(int a){return prefs[a];}
 public TLRPC.Chat getChat(long id){return chats.get(id);}
 public TopicsController getTopicsController(){return new TopicsController();}
 public static final class TopicsController {public TLRPC.TL_forumTopic findTopic(long chat,long topic){return new TLRPC.TL_forumTopic();}}
}
