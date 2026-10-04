/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.groupmessages;

/** An immutable local copy; eligibility (group, text, no TTL) is checked before capture. */
public final class DeletedMessageRecord {
    public static final int MAX_TEXT_CHARS = 32768;
    public static final int MAX_SENDER_NAME_CHARS = 512;

    public final long ownerId;
    public final long dialogId;
    public final long topicId;
    public final int messageId;
    /** Telegram message timestamp, in Unix seconds. */
    public final int sentAt;
    /** Zero denotes an anonymous/unavailable user identity. */
    public final long senderUserId;
    /** User UID when positive, channel/group sender identity when negative, zero if unknown. */
    public final long senderPeerId;
    public final String senderName;
    public final String text;
    /** Time the deletion was observed, in Unix seconds. */
    public final long deletedAt;

    public DeletedMessageRecord(long ownerId, long dialogId, long topicId, int messageId,
            int sentAt, long senderUserId, String senderName, String text, long deletedAt) {
        this(ownerId, dialogId, topicId, messageId, sentAt, senderUserId, senderName, text, deletedAt, senderUserId);
    }

    public DeletedMessageRecord(long ownerId, long dialogId, long topicId, int messageId,
            int sentAt, long senderUserId, String senderName, String text, long deletedAt, long senderPeerId) {
        if (ownerId <= 0 || dialogId >= 0 || dialogId == Long.MIN_VALUE || topicId < 0
                || messageId <= 0 || sentAt <= 0 || senderUserId < 0 || deletedAt <= 0
                || senderPeerId == Long.MIN_VALUE
                || senderUserId > 0 && senderPeerId != senderUserId
                || senderUserId == 0 && senderPeerId > 0) {
            throw new IllegalArgumentException("本地撤回记录的身份或时间无效。");
        }
        if (text == null || text.trim().isEmpty() || text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("本地撤回记录的正文为空或过长。");
        }
        String name = senderName == null ? "" : senderName;
        if (name.length() > MAX_SENDER_NAME_CHARS) {
            throw new IllegalArgumentException("本地撤回记录的发送人名称过长。");
        }
        requireUnicode(text);
        requireUnicode(name);
        this.ownerId = ownerId;
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.messageId = messageId;
        this.sentAt = sentAt;
        this.senderUserId = senderUserId;
        this.senderPeerId = senderPeerId;
        this.senderName = name;
        this.text = text;
        this.deletedAt = deletedAt;
    }

    private static void requireUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("本地撤回记录包含无效文字编码。");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("本地撤回记录包含无效文字编码。");
            }
        }
    }
}
