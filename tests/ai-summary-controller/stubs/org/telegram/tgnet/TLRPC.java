package org.telegram.tgnet;
public final class TLRPC {
 public static class Chat {public long id;public String title="Synthetic group";public boolean kicked,channel,forum;public Object migrated_to;}
 public static class TL_forumTopic {public String title="Synthetic topic";}
 public static class Peer {public long id;}
 public static class ReplyHeader {public int reply_to_msg_id,flags;public boolean reply_to_scheduled,reply_to_ephemeral;public Peer reply_to_peer_id;public String quote_text;}
 public static class Message {public int id,edit_date,date;public String message;public boolean usable=true,out;public long dialogId,topicId;public Peer from_id;public ReplyHeader reply_to;}
}
