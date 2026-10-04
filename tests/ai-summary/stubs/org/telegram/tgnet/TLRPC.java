package org.telegram.tgnet;

import java.util.ArrayList;

/** Only the TL fields touched by the summary feature; no Telegram transport. */
public final class TLRPC {
    public static class Peer extends TLObject {
        public long user_id, chat_id, channel_id;
    }
    public static class TL_peerUser extends Peer {}
    public static class TL_peerChat extends Peer {}
    public static class TL_peerChannel extends Peer {}
    public static class InputPeer extends Peer {
        public long access_hash;
        public InputPeer peer;
        public int msg_id;
    }
    public static class TL_inputPeerEmpty extends InputPeer {}
    public static class TL_inputPeerUser extends InputPeer {}
    public static class TL_inputPeerChat extends InputPeer {}
    public static class TL_inputPeerChannel extends InputPeer {}
    public static class TL_inputPeerChannelFromMessage extends InputPeer {}
    public static class User extends TLObject {
        public long id;
        public String first_name, last_name, username;
        public ArrayList<TL_username> usernames = new ArrayList<>();
    }
    public static class TL_username extends TLObject { public String username; public boolean active; }
    public static class Chat extends TLObject {
        public long id, access_hash;
        public String title, username;
        public boolean megagroup, forum, noforwards, monoforum, min, kicked, deactivated, left;
        public InputChannel migrated_to;
        public TL_chatBannedRights banned_rights;
    }
    public static class TL_chatBannedRights extends TLObject { public boolean view_messages, send_messages; }
    public static class InputChannel extends TLObject {
        public long channel_id, access_hash;
    }
    public static class TL_inputChannel extends InputChannel {}
    public static class TL_inputChannelFromMessage extends InputChannel {
        public InputPeer peer;
        public int msg_id;
    }
    public static class ChatFull extends TLObject { public long migrated_from_chat_id; }
    public static class TL_chat extends Chat {}
    public static class TL_chatEmpty extends Chat {}
    public static class TL_channel extends Chat {}
    public static class TL_chatForbidden extends Chat {}
    public static class TL_channelForbidden extends Chat {}
    public static class MessageReplyHeader extends TLObject {
        public int flags, reply_to_msg_id, reply_to_top_id;
        public String quote_text;
        public boolean forum_topic, reply_to_scheduled, reply_to_ephemeral;
        public Peer reply_to_peer_id;
    }
    public static class TL_messageReplyHeader extends MessageReplyHeader {}
    public static class MessageAction extends TLObject {}
    public static class TL_messageActionEmpty extends MessageAction {}
    public static class TL_messageActionTopicCreate extends MessageAction {}
    public static class MessageMedia extends TLObject {
        public int ttl_seconds;
    }
    public static class TL_messageMediaEmpty extends MessageMedia {}
    public static class TL_messageMediaPhoto extends MessageMedia {}
    public static class TL_messageMediaWebPage extends MessageMedia {}
    public static class MessageEntity extends TLObject { public int offset, length; }
    public static class TL_messageEntityMention extends MessageEntity {}
    public static class TL_messageEntityMentionName extends MessageEntity { public long user_id; }
    public static class Message extends TLObject {
        public int id, date, edit_date, send_state, ttl_period, ttl, destroyTime, expire_date, ephemeralAnchorMsgId, quick_reply_shortcut_id;
        public long dialog_id, destroyTimeMillis, ephemeralReceiverBotId;
        public String message, post_author;
        public Peer peer_id, from_id;
        public MessageReplyHeader reply_to;
        public MessageMedia media;
        public MessageAction action;
        public boolean noforwards, out, post, mentioned;
        public ArrayList<MessageEntity> entities = new ArrayList<>();
    }
    public static class TL_message extends Message {}
    public static class TL_message_secret extends TL_message {}
    public static class TL_message_secret_layer72 extends TL_message {}
    public static class TL_messageEmpty extends Message {}
    public static class TL_messageService extends Message {}
    public static class TL_error extends TLObject {
        public int code;
        public String text;
    }
    public static class messages_Messages extends TLObject {
        public ArrayList<Message> messages = new ArrayList<>();
        public ArrayList<User> users = new ArrayList<>();
        public ArrayList<Chat> chats = new ArrayList<>();
        public int count;
    }
    public static class TL_messages_messages extends messages_Messages {}
    public static class TL_messages_messagesSlice extends messages_Messages {}
    public static class TL_messages_channelMessages extends messages_Messages {}
    public static class TL_messages_messagesNotModified extends messages_Messages {}
    public static class TL_messages_getHistory extends TLObject {
        public InputPeer peer;
        public int offset_id, offset_date, add_offset, limit, max_id, min_id;
        public long hash;
    }
    public static class TL_messages_getMessages extends TLObject {
        public ArrayList<Integer> id = new ArrayList<>();
    }
    public static class TL_channels_getMessages extends TLObject {
        public InputChannel channel;
        public ArrayList<Integer> id = new ArrayList<>();
    }
    public static class TL_messages_getReplies extends TLObject {
        public InputPeer peer;
        public int msg_id, offset_id, offset_date, add_offset, limit, max_id, min_id;
        public long hash;
    }
}
