/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

/** The minimum message data sent to the configured local model. */
public final class SummaryMessage {
    public final long dialogId;
    public final int id;
    public final int date;
    public final String sender;
    public final String text;

    public SummaryMessage(long dialogId, int id, int date, String sender, String text) {
        this.dialogId = dialogId;
        this.id = id;
        this.date = date;
        this.sender = sender == null ? "" : sender;
        this.text = text == null ? "" : text;
    }
}
