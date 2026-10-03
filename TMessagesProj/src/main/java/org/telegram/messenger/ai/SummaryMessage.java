/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

/** The minimum message data sent to the configured local model. */
public final class SummaryMessage {
    public final long dialogId;
    public final int id;
    public final int date;
    public final String sender;
    public final String text;
    /** Telegram peer ID: positive user, negative chat/channel, zero when unavailable. */
    public final long senderId;
    public final int replyToId;
    public final long replyToDialogId;
    /** Explicit mention entity resolved to the current user; display-name matches never count. */
    public final boolean mentionedSelf;
    public final boolean outgoing;
    public final int editDate;
    /** Only known when the replied-to message's sender was available in this snapshot. */
    public final boolean replyToSelfKnown;
    public final boolean replyToSelf;

    public SummaryMessage(long dialogId, int id, int date, String sender, String text) {
        this(dialogId, id, date, sender, text, 0, 0, 0, false, false, 0, false, false);
    }

    public SummaryMessage(long dialogId, int id, int date, String sender, String text,
            long senderId, int replyToId, long replyToDialogId, boolean mentionedSelf,
            boolean outgoing, int editDate, boolean replyToSelfKnown, boolean replyToSelf) {
        this.dialogId = dialogId;
        this.id = id;
        this.date = date;
        this.sender = sender == null ? "" : sender;
        this.text = text == null ? "" : text;
        this.senderId = senderId;
        this.replyToId = replyToId;
        this.replyToDialogId = replyToDialogId;
        this.mentionedSelf = mentionedSelf;
        this.outgoing = outgoing;
        this.editDate = editDate;
        this.replyToSelfKnown = replyToSelfKnown;
        this.replyToSelf = replyToSelfKnown && replyToSelf;
    }
}
