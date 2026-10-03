/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Stable source identity and content digest for saved summaries; retains no original text. */
public final class SummarySourceReference {
    public final long dialogId;
    public final int id;
    public final int date;
    public final int editDate;
    public final long senderId;
    public final String textHash;

    public SummarySourceReference(long dialogId, int id, int date, int editDate, long senderId, String textHash) {
        if (dialogId >= 0 || dialogId == Long.MIN_VALUE || id <= 0 || date <= 0 || editDate < 0
                || senderId == Long.MIN_VALUE || textHash == null || textHash.length() != 64) {
            throw new IllegalArgumentException("摘要来源引用无效，请重新读取原消息。");
        }
        for (int i = 0; i < textHash.length(); i++) {
            char c = textHash.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F')) {
                throw new IllegalArgumentException("摘要来源文本校验值无效，请重新读取原消息。");
            }
        }
        this.dialogId = dialogId;
        this.id = id;
        this.date = date;
        this.editDate = editDate;
        this.senderId = senderId;
        this.textHash = textHash.toLowerCase(Locale.US);
    }

    public static SummarySourceReference from(SummaryMessage message) {
        if (message == null) throw new IllegalArgumentException("缺少摘要来源消息。");
        return new SummarySourceReference(message.dialogId, message.id, message.date, message.editDate,
                message.senderId, hashText(message.text));
    }

    public boolean matchesText(String text) {
        return text != null && textHash.equals(hashText(text));
    }

    private static String hashText(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            final char[] alphabet = "0123456789abcdef".toCharArray();
            char[] hex = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = alphabet[(digest[i] >>> 4) & 15];
                hex[i * 2 + 1] = alphabet[digest[i] & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
