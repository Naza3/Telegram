/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Atomic per-chat coverage metadata. No source text, API key or generated text is persisted. */
public final class SummaryStateStore {
    private static final String PREFIX = "local_ai_coverage_v1_";

    private SummaryStateStore() { }

    public static final class State {
        public final int cursor;
        public final int lastLowerExclusive;
        public final int lastUpperInclusive;
        public final int messageCount;
        public final long completedAt;
        public final String resultDigest;

        private State(int cursor, int lower, int upper, int count, long completedAt, String digest) {
            this.cursor = cursor;
            lastLowerExclusive = lower;
            lastUpperInclusive = upper;
            messageCount = count;
            this.completedAt = completedAt;
            resultDigest = digest;
        }
    }

    /** Cancellation and publishing a completed batch have a single ordering. */
    public static final class CompletionToken {
        private boolean cancelled;
        private boolean committed;

        public synchronized void cancel() {
            if (!committed) cancelled = true;
        }

        public synchronized boolean isCancelled() { return cancelled; }
    }

    public static synchronized State load(int account, long ownerId, long dialogId, long topicId) {
        requireOwner(account, ownerId);
        String value = preferences(account).getString(key(ownerId, dialogId, topicId), null);
        return decode(value);
    }

    /**
     * Must run off the UI thread. advance=false preserves the existing incremental cursor.
     * Initialization (expectedCursor=0) is only called after an explicitly selected initial batch.
     * Subsequent commits compare both the stored cursor and the contiguous lower boundary.
     */
    public static synchronized boolean recordSuccess(int account, long ownerId, long dialogId,
            long topicId, int expectedCursor, int lowerExclusive, int coveredThrough,
            boolean complete, boolean advance, int messageCount, String result,
            String promptRevision, CompletionToken token) {
        if (token == null) throw new IllegalArgumentException("A completion token is required");
        synchronized (token) {
            if (token.cancelled) return false;
            if (token.committed) throw new IllegalStateException("本次结果已经记录。");
            requireOwner(account, ownerId);
            if (!complete || lowerExclusive < 0 || coveredThrough < lowerExclusive
                    || messageCount < 0 || result == null || result.isEmpty()) {
                throw new IllegalArgumentException("范围不完整，未更新总结进度。");
            }
            SharedPreferences prefs = preferences(account);
            String storageKey = key(ownerId, dialogId, topicId);
            String previous = prefs.getString(storageKey, null);
            State state = decode(previous);
            int cursor = state.cursor;
            if (advance) {
                if (expectedCursor != cursor || coveredThrough < cursor
                        || (cursor > 0 && lowerExclusive != cursor)) {
                    throw new IllegalStateException("总结进度已变化，请重新读取后继续，避免跳过消息。");
                }
                cursor = coveredThrough;
            }
            try {
                JSONObject record = new JSONObject()
                        .put("cursor", cursor)
                        .put("lower", lowerExclusive)
                        .put("upper", coveredThrough)
                        .put("count", messageCount)
                        .put("completed_at", System.currentTimeMillis())
                        .put("digest", digest(result))
                        .put("prompt_revision", promptRevision == null ? "" : promptRevision);
                requireOwner(account, ownerId);
                if (!prefs.edit().putString(storageKey, record.toString()).commit()) {
                    // SharedPreferences may update its memory map even when a disk write fails.
                    // Restore that view too; Android's atomic file writer preserves the prior file.
                    restore(prefs, storageKey, previous);
                    throw new IllegalStateException("无法保存总结进度，未确认本批完成。请稍后重试。");
                }
                token.committed = true;
                return true;
            } catch (JSONException error) {
                throw new IllegalStateException("无法记录总结范围，请重试。");
            }
        }
    }

    public static synchronized void clear(int account, long ownerId, long dialogId, long topicId) {
        requireOwner(account, ownerId);
        SharedPreferences prefs = preferences(account);
        String storageKey = key(ownerId, dialogId, topicId);
        String old = prefs.getString(storageKey, null);
        if (!prefs.edit().remove(storageKey).commit()) {
            restore(prefs, storageKey, old);
            throw new IllegalStateException("无法清除总结进度，请重试。");
        }
    }

    /** Called after UserConfig clears identity, and serialized with any pending coverage writes. */
    public static synchronized void clearOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0) return;
        SharedPreferences prefs = preferences(account);
        SharedPreferences.Editor editor = prefs.edit();
        String prefix = PREFIX + ownerId + "_";
        for (String candidate : prefs.getAll().keySet()) {
            if (candidate.startsWith(prefix)) editor.remove(candidate);
        }
        editor.apply();
    }

    private static State decode(String value) {
        if (value == null) return new State(0, 0, 0, 0, 0, "");
        try {
            JSONObject record = new JSONObject(value);
            int cursor = record.getInt("cursor");
            int lower = record.getInt("lower");
            int upper = record.getInt("upper");
            int count = record.getInt("count");
            long completed = record.getLong("completed_at");
            String digest = record.getString("digest");
            if (cursor < 0 || lower < 0 || upper < lower || count < 0 || completed <= 0
                    || !digest.matches("[a-f0-9]{64}")) throw new JSONException("Invalid state");
            return new State(cursor, lower, upper, count, completed, digest);
        } catch (JSONException error) {
            throw new IllegalStateException("本地总结进度记录无效。请清除该聊天的总结进度后重新选择起点。");
        }
    }

    private static void restore(SharedPreferences prefs, String key, String value) {
        SharedPreferences.Editor editor = prefs.edit();
        if (value == null) editor.remove(key); else editor.putString(key, value);
        editor.commit();
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String key(long ownerId, long dialogId, long topicId) {
        if (dialogId >= 0 || topicId < 0) throw new IllegalArgumentException("无效群聊范围");
        return PREFIX + ownerId + "_" + dialogId + "_" + topicId;
    }

    private static SharedPreferences preferences(int account) { return MessagesController.getMainSettings(account); }

    private static void requireOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("当前账号已变化，请重新打开群聊。");
        }
    }
}
