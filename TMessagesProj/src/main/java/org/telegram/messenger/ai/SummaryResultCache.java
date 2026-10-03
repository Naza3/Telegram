/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Bounded process-memory history of completed results, only for an explicit "view existing" action.
 * A model alias can change behind the same endpoint. This cache never authorizes automatic reuse,
 * stores no partials, and has no persistence. Regeneration must bypass it and may invalidate its key.
 */
public final class SummaryResultCache {
    public static final int DEFAULT_MAX_ENTRIES = 8;
    public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024;
    private static final int PARSER_VERSION = 1;
    private static final SummaryResultCache INSTANCE = new SummaryResultCache(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);

    public static final class Key {
        public final int account;
        public final long ownerId;
        public final long dialogId;
        public final long topicId;
        private final String fingerprint;
        private final long[] sourceDialogs;
        private final int[] sourceIds;
        private final long retainedBytes;

        private Key(int account, long ownerId, long dialogId, long topicId, String fingerprint,
                long[] sourceDialogs, int[] sourceIds) {
            this.account = account;
            this.ownerId = ownerId;
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.fingerprint = fingerprint;
            this.sourceDialogs = sourceDialogs;
            this.sourceIds = sourceIds;
            retainedBytes = 384L + 12L * sourceIds.length;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Key && fingerprint.equals(((Key) other).fingerprint);
        }
        @Override public int hashCode() { return fingerprint.hashCode(); }
        @Override public String toString() { return "SummaryResultCache.Key"; }
    }

    public static final class Entry {
        public final String summary;
        public final long generatedAtMillis;
        private final long retainedBytes;
        private Entry(String summary, long generatedAtMillis, long retainedBytes) {
            this.summary = summary;
            this.generatedAtMillis = generatedAtMillis;
            this.retainedBytes = retainedBytes;
        }
    }

    private final int maxEntries;
    private final long maxBytes;
    private long retainedBytes;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public SummaryResultCache(int maxEntries, long maxBytes) {
        if (maxEntries < 1 || maxEntries > DEFAULT_MAX_ENTRIES || maxBytes < 1024 || maxBytes > DEFAULT_MAX_BYTES) {
            throw new IllegalArgumentException("缓存容量超出允许范围。");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public static SummaryResultCache getInstance() { return INSTANCE; }

    /** Creates an opaque digest; neither source text, Prompt nor API key is retained in the key. */
    public static Key key(int account, long ownerId, long dialogId, long topicId,
            List<SummaryMessage> messages, PromptOptions options, AiSummarySettings.Config config,
            String actualModelVersion) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0 || dialogId >= 0
                || topicId < 0 || messages == null || messages.isEmpty() || options == null || config == null) {
            throw new IllegalArgumentException("缓存缺少有效的任务快照。");
        }
        MessageDigest digest = digest();
        putInt(digest, PARSER_VERSION);
        putInt(digest, account); putLong(digest, ownerId); putLong(digest, dialogId); putLong(digest, topicId);
        putString(digest, options.templateId); putString(digest, options.customInstructions);
        putInt(digest, options.focusSelf ? 1 : 0);
        putInt(digest, options.templateVersion); putInt(digest, options.builtinRulesVersion);
        putString(digest, AiSummaryPrompt.SYSTEM_PROMPT);
        putString(digest, config.baseUrl); putString(digest, config.model);
        putString(digest, hashSecret(config.apiKey));
        putInt(digest, config.maxOutputTokens); putInt(digest, config.inputCharacterBudget);
        putInt(digest, config.stream ? 1 : 0);
        putString(digest, "sampling=server-default");
        putString(digest, actualModelVersion == null ? "" : actualModelVersion);
        putString(digest, TimeZone.getDefault().getID());
        putInt(digest, messages.size());
        long[] dialogs = new long[messages.size()];
        int[] ids = new int[messages.size()];
        for (int i = 0; i < messages.size(); i++) {
            SummaryMessage message = messages.get(i);
            if (message == null) throw new IllegalArgumentException("缓存来源快照不完整。");
            dialogs[i] = message.dialogId;
            ids[i] = message.id;
            putLong(digest, message.dialogId); putInt(digest, message.id); putInt(digest, message.date);
            putString(digest, message.sender); putString(digest, message.text); putInt(digest, message.editDate);
            putLong(digest, message.senderId); putInt(digest, message.replyToId); putLong(digest, message.replyToDialogId);
            putInt(digest, message.mentionedSelf ? 1 : 0); putInt(digest, message.outgoing ? 1 : 0);
            putInt(digest, message.replyToSelfKnown ? 1 : 0); putInt(digest, message.replyToSelf ? 1 : 0);
        }
        return new Key(account, ownerId, dialogId, topicId, hex(digest.digest()), dialogs, ids);
    }

    /** Only call after a full, validated result; false means caching was skipped, not task failure. */
    public synchronized boolean put(Key key, String summary, long generatedAtMillis) {
        if (key == null || !currentOwner(key) || summary == null || summary.isEmpty() || generatedAtMillis <= 0) return false;
        if (summary.length() > maxBytes) { invalidate(key); return false; }
        try { AiSummaryPrompt.validateReferences(summary, key.sourceIds.length); }
        catch (IllegalArgumentException error) { return false; }
        // Conservative retained-size estimate includes UTF-16/UTF-8 payload, source IDs and object overhead.
        long bytes = key.retainedBytes + 192L + Math.max(2L * summary.length(), summary.getBytes(StandardCharsets.UTF_8).length);
        invalidate(key);
        if (bytes > maxBytes) return false;
        entries.put(key, new Entry(summary, generatedAtMillis, bytes));
        retainedBytes += bytes;
        trim();
        return true;
    }

    /** A false flag is an unconditional miss and must be used for normal/forced generation paths. */
    public synchronized Entry get(Key key, boolean explicitlyRequested) {
        if (!explicitlyRequested || key == null || !currentOwner(key)) return null;
        return entries.get(key);
    }

    public synchronized void invalidate(Key key) {
        Entry removed = entries.remove(key);
        if (removed != null) retainedBytes -= removed.retainedBytes;
    }

    public synchronized void invalidateMessage(int account, long ownerId, long dialogId, int messageId) {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Entry> item = iterator.next();
            Key key = item.getKey();
            if (key.account != account || key.ownerId != ownerId) continue;
            for (int i = 0; i < key.sourceIds.length; i++) {
                if (key.sourceDialogs[i] == dialogId && key.sourceIds[i] == messageId) {
                    retainedBytes -= item.getValue().retainedBytes; iterator.remove(); break;
                }
            }
        }
    }

    public synchronized void clearScope(int account, long ownerId, long dialogId, long topicId) {
        removeMatching(account, ownerId, dialogId, topicId, false);
    }

    public synchronized void clearOwner(int account, long ownerId) {
        removeMatching(account, ownerId, 0, 0, true);
    }

    public synchronized void clear() { entries.clear(); retainedBytes = 0; }
    public synchronized int size() { return entries.size(); }
    public synchronized long retainedBytes() { return retainedBytes; }

    private void removeMatching(int account, long ownerId, long dialogId, long topicId, boolean entireOwner) {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Entry> item = iterator.next(); Key key = item.getKey();
            if (key.account == account && key.ownerId == ownerId
                    && (entireOwner || key.dialogId == dialogId && key.topicId == topicId)) {
                retainedBytes -= item.getValue().retainedBytes; iterator.remove();
            }
        }
    }

    private void trim() {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || retainedBytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<Key, Entry> item = iterator.next(); retainedBytes -= item.getValue().retainedBytes; iterator.remove();
        }
    }

    private static boolean currentOwner(Key key) { return UserConfig.getInstance(key.account).getClientUserId() == key.ownerId; }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static String hashSecret(String value) { return hex(digest().digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static void putString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); putInt(digest, bytes.length); digest.update(bytes);
    }
    private static void putInt(MessageDigest digest, int value) { putLong(digest, value); }
    private static void putLong(MessageDigest digest, long value) { for (int i = 7; i >= 0; i--) digest.update((byte) (value >>> (i * 8))); }
    private static String hex(byte[] value) {
        char[] chars = new char[value.length * 2]; final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) { chars[i * 2] = digits[(value[i] >>> 4) & 15]; chars[i * 2 + 1] = digits[value[i] & 15]; }
        return new String(chars);
    }
}
