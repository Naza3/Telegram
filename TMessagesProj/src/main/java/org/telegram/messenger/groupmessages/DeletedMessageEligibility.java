/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.groupmessages;

import org.telegram.tgnet.TLRPC;

/** Strict eligibility for local copies; this never changes Telegram's deletion decision. */
public final class DeletedMessageEligibility {
    private DeletedMessageEligibility() { }

    /** Native mode 3 removes empty dialog cache entries automatically, including after a revoke. */
    public static boolean clearsLocalRecords(long dialogId, int mode) {
        return dialogId < 0 && dialogId != Long.MIN_VALUE && mode >= 0 && mode <= 2;
    }

    public static boolean accepts(long dialogId, TLRPC.Chat chat, TLRPC.Message message) {
        if (dialogId >= 0 || chat == null || chat.id != -dialogId || chat.broadcast
                || chat.monoforum || chat.left || chat.kicked
                || message == null || message.peer_id == null
                || message instanceof TLRPC.TL_messageService || message instanceof TLRPC.TL_messageEmpty
                || message instanceof TLRPC.TL_message_secret
                || message instanceof TLRPC.TL_message_secret_layer72
                || message.action != null && !(message.action instanceof TLRPC.TL_messageActionEmpty)
                || message.id <= 0 || message.date <= 0 || message.send_state != 0
                || message.ttl_period != 0 || message.ttl != 0 || message.destroyTime != 0
                || message.destroyTimeMillis != 0 || message.expire_date != 0
                || message.ephemeralAnchorMsgId != 0 || message.ephemeralReceiverBotId != 0
                || message.quick_reply_shortcut_id != 0
                || message.message == null || message.message.trim().isEmpty()
                || message.message.length() > 32768) {
            return false;
        }
        boolean ordinaryGroup = message.peer_id.chat_id == chat.id && message.peer_id.channel_id == 0
                && message.peer_id.user_id == 0;
        boolean superGroup = message.peer_id.channel_id == chat.id && chat.megagroup
                && message.peer_id.chat_id == 0 && message.peer_id.user_id == 0;
        if (!ordinaryGroup && !superGroup) return false;
        return message.media == null || message.media.ttl_seconds == 0
                && (message.media instanceof TLRPC.TL_messageMediaEmpty
                    || message.media instanceof TLRPC.TL_messageMediaWebPage);
    }

    public static long topicId(TLRPC.Chat chat, TLRPC.Message message) {
        if (chat == null || !chat.forum) return 0;
        if (message.reply_to == null || !message.reply_to.forum_topic) return 1;
        return message.reply_to.reply_to_top_id != 0
                ? message.reply_to.reply_to_top_id : message.reply_to.reply_to_msg_id;
    }
}
