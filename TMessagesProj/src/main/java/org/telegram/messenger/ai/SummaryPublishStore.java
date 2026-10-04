/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Encrypted sending metadata. This store observes Telegram's queue; it never sends or retries. */
public final class SummaryPublishStore {
    public static final int MAX_ATTEMPTS = 100;
    public static final int MAX_PARTS = 64;
    public static final int MAX_STORAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PLAINTEXT_BYTES = (MAX_STORAGE_BYTES - 512) / 4 * 3;
    private static final int MAX_EDITED_CHARACTERS = 512 * 1024;
    private static final String NAMESPACE = "publish";
    private static final String OWNER = "ai_summary_publish_owner";
    private static final String ATTEMPT = "ai_summary_publish_attempt";
    private static final String PART = "ai_summary_publish_part";
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "summary-publish-records"); thread.setDaemon(true); return thread;
    });

    public enum PartState { PREPARED, QUEUED, CONFIRMED, FAILED, INTERRUPTED, SCHEDULED }
    public enum Status { PREPARED, SENDING, SENT, FAILED, PARTIAL, INTERRUPTED, SCHEDULED }

    public static final class Part {
        public final int index, start, end, localId, serverMessageId;
        public final long randomId;
        public final String textHash;
        public final PartState state;
        private Part(int index, int start, int end, String textHash, int localId, long randomId,
                int serverMessageId, PartState state) {
            this.index = index; this.start = start; this.end = end; this.textHash = textHash;
            this.localId = localId; this.randomId = randomId; this.serverMessageId = serverMessageId; this.state = state;
        }
        private Part changed(int local, long random, int server, PartState next) {
            return new Part(index, start, end, textHash, local, random, server, next);
        }
    }

    public static final class Attempt {
        public final String id, recordId, editedSummary;
        public final long ownerId, dialogId, topicId, createdAtMillis;
        public final List<Part> parts;
        public final Status status;
        public final int confirmedCount, failedCount;
        private Attempt(String id, String recordId, long ownerId, long dialogId, long topicId,
                long createdAtMillis, String editedSummary, List<Part> parts) {
            this.id = id; this.recordId = recordId; this.ownerId = ownerId; this.dialogId = dialogId;
            this.topicId = topicId; this.createdAtMillis = createdAtMillis; this.editedSummary = editedSummary;
            this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
            int confirmed = 0, failed = 0, queued = 0, scheduled = 0, interrupted = 0;
            for (Part part : parts) {
                if (part.state == PartState.CONFIRMED) confirmed++;
                if (part.state == PartState.FAILED) failed++;
                if (part.state == PartState.QUEUED) queued++;
                if (part.state == PartState.SCHEDULED) scheduled++;
                if (part.state == PartState.INTERRUPTED) interrupted++;
            }
            confirmedCount = confirmed; failedCount = failed;
            status = confirmed == parts.size() ? Status.SENT : confirmed > 0 ? Status.PARTIAL
                    : queued > 0 ? Status.SENDING : scheduled > 0 ? Status.SCHEDULED
                    : failed == parts.size() ? Status.FAILED : interrupted > 0 ? Status.INTERRUPTED
                    : failed > 0 ? Status.PARTIAL : Status.PREPARED;
        }
        public String getPartText(int index) { Part part = parts.get(index); return editedSummary.substring(part.start, part.end); }
        private Attempt replacing(Part part) {
            ArrayList<Part> copy = new ArrayList<>(parts); copy.set(part.index, part);
            return new Attempt(id, recordId, ownerId, dialogId, topicId, createdAtMillis, editedSummary, copy);
        }
    }

    private SummaryPublishStore() { }

    /** Worker-thread API, called once after the native editor has frozen its actual split text. */
    public static Attempt prepare(int account, long ownerId, String recordId, long dialogId,
            long topicId, List<String> finalParts) {
        synchronized (SummaryHistoryStore.class) {
            requireOwner(account, ownerId);
            SummaryHistoryStore.Record history = SummaryHistoryStore.get(account, ownerId, recordId);
            if (!matches(history, dialogId, topicId)) throw new IllegalStateException("原总结已删除或发送目标已变化，请重新打开总结。");
            if (finalParts == null || finalParts.isEmpty() || finalParts.size() > MAX_PARTS) throw new IllegalArgumentException("发送分段数量无效。");
            StringBuilder text = new StringBuilder();
            ArrayList<Part> parts = new ArrayList<>();
            for (String piece : new ArrayList<>(finalParts)) {
                requireText(piece);
                if (text.length() + piece.length() > MAX_EDITED_CHARACTERS) throw new IllegalArgumentException("发送稿超过记录容量，请缩短正文。");
                int start = text.length(); text.append(piece);
                parts.add(new Part(parts.size(), start, text.length(), hash(piece), 0, 0, 0, PartState.PREPARED));
            }
            Attempt attempt = new Attempt(UUID.randomUUID().toString(), recordId, ownerId, dialogId,
                    topicId, System.currentTimeMillis(), text.toString(), parts);
            ArrayList<Attempt> records = read(account, ownerId);
            records.add(0, attempt);
            while (records.size() > MAX_ATTEMPTS || utf8(encode(records)) > MAX_PLAINTEXT_BYTES) {
                if (records.size() == 1) throw new IllegalStateException("发送记录超过保存容量。");
                records.remove(records.size() - 1);
            }
            write(account, ownerId, records);
            return attempt;
        }
    }

    /** Local Telegram message params only; no body or connection secret is embedded. */
    public static HashMap<String, String> partParams(Attempt attempt, int index, Map<String, String> existing) {
        if (attempt == null || index < 0 || index >= attempt.parts.size()) throw new IllegalArgumentException("发送记录分段无效。");
        HashMap<String, String> params = existing == null ? new HashMap<>() : new HashMap<>(existing);
        params.put(OWNER, Long.toString(attempt.ownerId)); params.put(ATTEMPT, attempt.id); params.put(PART, Integer.toString(index));
        return params;
    }

    public static List<Attempt> listForRecord(int account, long ownerId, String recordId) {
        synchronized (SummaryHistoryStore.class) {
            requireOwner(account, ownerId);
            ArrayList<Attempt> result = new ArrayList<>();
            for (Attempt attempt : read(account, ownerId)) if (attempt.recordId.equals(recordId)) result.add(attempt);
            return Collections.unmodifiableList(result);
        }
    }

    public static Attempt get(int account, long ownerId, String attemptId) {
        synchronized (SummaryHistoryStore.class) {
            requireOwner(account, ownerId);
            for (Attempt attempt : read(account, ownerId)) if (attempt.id.equals(attemptId)) return attempt;
            return null;
        }
    }

    /** One decryption for a history page; records are already stored newest first. */
    public static Map<String, Attempt> latestForRecords(int account, long ownerId, Set<String> recordIds) {
        synchronized (SummaryHistoryStore.class) {
            requireOwner(account, ownerId);
            if (recordIds == null) throw new IllegalArgumentException("缺少总结历史标识。");
            Set<String> selected = new HashSet<>(recordIds);
            HashMap<String, Attempt> result = new HashMap<>();
            for (Attempt attempt : read(account, ownerId)) {
                if (selected.contains(attempt.recordId) && !result.containsKey(attempt.recordId)) result.put(attempt.recordId, attempt);
            }
            return Collections.unmodifiableMap(result);
        }
    }

    public static Set<SummaryFilter.PublishedMessageId> confirmedMessages(int account, long ownerId, long dialogId) {
        synchronized (SummaryHistoryStore.class) {
            requireOwner(account, ownerId);
            HashSet<SummaryFilter.PublishedMessageId> result = new HashSet<>();
            for (Attempt attempt : read(account, ownerId)) if (attempt.dialogId == dialogId) {
                for (Part part : attempt.parts) if (part.state == PartState.CONFIRMED) {
                    result.add(new SummaryFilter.PublishedMessageId(dialogId, part.serverMessageId));
                }
            }
            return Collections.unmodifiableSet(result);
        }
    }

    public static void abandonPrepared(int account, long ownerId, String attemptId) {
        synchronized (SummaryHistoryStore.class) {
            requireOwner(account, ownerId);
            ArrayList<Attempt> records = read(account, ownerId);
            for (int i = 0; i < records.size(); i++) {
                Attempt attempt = records.get(i);
                if (!attempt.id.equals(attemptId)) continue;
                for (Part part : attempt.parts) if (part.localId != 0 || part.state != PartState.PREPARED) return;
                for (Part part : attempt.parts) attempt = attempt.replacing(part.changed(0, 0, 0, PartState.INTERRUPTED));
                records.set(i, attempt); write(account, ownerId, records); return;
            }
        }
    }

    /** No late event creates an attempt. Retries can only reuse the original local/random pair. */
    public static void markQueued(int account, long ownerId, String attemptId, int index, long dialogId,
            long topicId, int localId, long randomId, String actualText) {
        update(account, ownerId, attemptId, index, dialogId, topicId, localId, randomId, 0, actualText, PartState.QUEUED);
    }
    public static void markConfirmed(int account, long ownerId, String attemptId, int index, long dialogId,
            long topicId, int localId, long randomId, int serverMessageId, boolean scheduled) {
        if (serverMessageId <= 0) return;
        update(account, ownerId, attemptId, index, dialogId, topicId, localId, randomId, serverMessageId,
                null, scheduled ? PartState.SCHEDULED : PartState.CONFIRMED);
    }
    public static void markFailed(int account, long ownerId, String attemptId, int index, long dialogId,
            long topicId, int localId, long randomId) {
        update(account, ownerId, attemptId, index, dialogId, topicId, localId, randomId, 0, null, PartState.FAILED);
    }

    private static void update(int account, long ownerId, String attemptId, int index, long dialogId,
            long topicId, int localId, long randomId, int serverId, String text, PartState next) {
        synchronized (SummaryHistoryStore.class) {
            requireOwner(account, ownerId);
            if (localId >= 0 || randomId == 0) return;
            ArrayList<Attempt> records = read(account, ownerId);
            for (int i = 0; i < records.size(); i++) {
                Attempt attempt = records.get(i);
                if (!attempt.id.equals(attemptId)) continue;
                if (attempt.dialogId != dialogId || attempt.topicId != topicId || index < 0 || index >= attempt.parts.size()) return;
                Part part = attempt.parts.get(index);
                if (part.state == PartState.CONFIRMED || part.state == PartState.INTERRUPTED) return;
                if (part.localId != 0 && (part.localId != localId || part.randomId != randomId)) return;
                if (next == PartState.QUEUED) {
                    if (text == null || !part.textHash.equals(hash(text)) || part.state == PartState.SCHEDULED) return;
                    for (Attempt other : records) for (Part otherPart : other.parts) {
                        if (!(other.id.equals(attempt.id) && otherPart.index == index) && otherPart.randomId == randomId) return;
                    }
                } else if (part.localId == 0) return; // Must first have entered the existing Telegram queue.
                records.set(i, attempt.replacing(part.changed(localId, randomId, serverId, next)));
                write(account, ownerId, records); return;
            }
        }
    }

    public static void trackQueued(int account, Map<String, String> params, long dialogId, long topicId,
            int localId, long randomId, String text) {
        track(account, params, (owner, attempt, part) -> markQueued(account, owner, attempt, part, dialogId, topicId, localId, randomId, text));
    }
    public static void trackConfirmed(int account, Map<String, String> params, long dialogId, long topicId,
            int localId, long randomId, int serverId, boolean scheduled) {
        track(account, params, (owner, attempt, part) -> markConfirmed(account, owner, attempt, part, dialogId, topicId, localId, randomId, serverId, scheduled));
    }
    public static void trackFailed(int account, Map<String, String> params, long dialogId, long topicId,
            int localId, long randomId) {
        track(account, params, (owner, attempt, part) -> markFailed(account, owner, attempt, part, dialogId, topicId, localId, randomId));
    }
    private interface Event { void run(long owner, String attempt, int part); }
    private static void track(int account, Map<String, String> params, Event event) {
        if (params == null || !params.containsKey(ATTEMPT)) return;
        try {
            final long owner = Long.parseLong(params.get(OWNER));
            final String attempt = params.get(ATTEMPT);
            final int part = Integer.parseInt(params.get(PART));
            if (attempt == null || attempt.length() > 256) return;
            WRITER.execute(() -> {
                try { event.run(owner, attempt, part); }
                catch (RuntimeException ignored) { /* A record-write failure cannot alter/retry the native send. */ }
            });
        } catch (RuntimeException ignored) { /* Unrelated or malformed local params are not provenance. */ }
    }

    public static void clearOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0) return;
        synchronized (SummaryHistoryStore.class) { SummaryPrivateStorage.delete(NAMESPACE, account, ownerId); }
    }

    private static ArrayList<Attempt> read(int account, long owner) {
        requireOwner(account, owner);
        String plaintext = SummaryPrivateStorage.read(NAMESPACE, account, owner, MAX_STORAGE_BYTES);
        ArrayList<Attempt> result = new ArrayList<>();
        if (plaintext == null) return result;
        try {
            if (utf8(plaintext) > MAX_PLAINTEXT_BYTES) throw new IllegalArgumentException();
            JSONObject root = new JSONObject(plaintext);
            if (root.getInt("version") != 1 || !NAMESPACE.equals(root.getString("kind"))) throw new IllegalArgumentException();
            JSONArray attempts = root.getJSONArray("attempts");
            if (attempts.length() > MAX_ATTEMPTS) throw new IllegalArgumentException();
            Map<String, SummaryHistoryStore.Record> histories = new HashMap<>();
            for (SummaryHistoryStore.Record history : SummaryHistoryStore.list(account, owner, 0, -1)) histories.put(history.id, history);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < attempts.length(); i++) {
                JSONObject value = attempts.getJSONObject(i);
                String id = value.getString("id"), recordId = value.getString("record_id"), text = value.getString("edited_summary");
                long dialog = value.getLong("dialog_id"), topic = value.getLong("topic_id"), created = value.getLong("created_at");
                if (id.length() > 256 || id.isEmpty() || !ids.add(id) || value.getLong("owner_id") != owner || created <= 0) throw new IllegalArgumentException();
                requireText(text);
                if (text.length() > MAX_EDITED_CHARACTERS) throw new IllegalArgumentException();
                JSONArray encodedParts = value.getJSONArray("parts");
                if (encodedParts.length() < 1 || encodedParts.length() > MAX_PARTS) throw new IllegalArgumentException();
                ArrayList<Part> parts = new ArrayList<>(); int start = 0;
                for (int j = 0; j < encodedParts.length(); j++) {
                    JSONObject piece = encodedParts.getJSONObject(j);
                    int end = piece.getInt("end"), local = piece.getInt("local_id"), server = piece.getInt("server_id");
                    long random = piece.getLong("random_id"); PartState state = PartState.valueOf(piece.getString("state"));
                    String textHash = piece.getString("text_hash");
                    if (end <= start || end > text.length() || !hash(text.substring(start, end)).equals(textHash)
                            || local > 0 || (local == 0) != (random == 0) || server < 0
                            || ((state == PartState.CONFIRMED || state == PartState.SCHEDULED) != (server > 0))
                            || (state == PartState.PREPARED || state == PartState.INTERRUPTED ? local != 0 : local == 0)) throw new IllegalArgumentException();
                    parts.add(new Part(j, start, end, textHash, local, random, server, state)); start = end;
                }
                if (start != text.length()) throw new IllegalArgumentException();
                if (matches(histories.get(recordId), dialog, topic)) result.add(new Attempt(id, recordId, owner, dialog, topic, created, text, parts));
            }
            requireOwner(account, owner);
            if (result.size() != attempts.length()) write(account, owner, result);
            return result;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IllegalStateException("总结发送记录格式无效，未修改原记录。", error);
        }
    }

    private static void write(int account, long owner, List<Attempt> records) {
        requireOwner(account, owner);
        if (records.isEmpty()) {
            SummaryPrivateStorage.delete(NAMESPACE, account, owner);
            requireOwner(account, owner);
            return;
        }
        String plaintext = encode(records);
        if (records.size() > MAX_ATTEMPTS || utf8(plaintext) > MAX_PLAINTEXT_BYTES) throw new IllegalStateException("总结发送记录超过保存容量。");
        SummaryPrivateStorage.write(NAMESPACE, account, owner, plaintext, MAX_STORAGE_BYTES);
        requireOwner(account, owner);
    }
    private static String encode(List<Attempt> records) {
        try {
            JSONArray attempts = new JSONArray();
            for (Attempt attempt : records) {
                JSONArray parts = new JSONArray();
                for (Part part : attempt.parts) parts.put(new JSONObject().put("end", part.end).put("text_hash", part.textHash)
                        .put("local_id", part.localId).put("random_id", part.randomId).put("server_id", part.serverMessageId).put("state", part.state.name()));
                attempts.put(new JSONObject().put("id", attempt.id).put("record_id", attempt.recordId).put("owner_id", attempt.ownerId)
                        .put("dialog_id", attempt.dialogId).put("topic_id", attempt.topicId).put("created_at", attempt.createdAtMillis)
                        .put("edited_summary", attempt.editedSummary).put("parts", parts));
            }
            return new JSONObject().put("kind", NAMESPACE).put("version", 1).put("attempts", attempts).toString();
        } catch (JSONException impossible) { throw new IllegalStateException("无法编码发送记录。", impossible); }
    }
    private static boolean matches(SummaryHistoryStore.Record record, long dialog, long topic) {
        return record != null && dialog < 0 && dialog != Long.MIN_VALUE && topic >= 0 && record.dialogId == dialog
                && (record.topicId == 0 || record.topicId == topic);
    }
    private static void requireOwner(int account, long owner) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || owner <= 0
                || UserConfig.getInstance(account).getClientUserId() != owner) throw new IllegalStateException("账号身份已变化，发送记录未更新。");
    }
    private static void requireText(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("发送稿为空。");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("发送稿包含无效Unicode字符。");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("发送稿包含无效Unicode字符。");
        }
    }
    private static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) { result.append(Character.forDigit((value >>> 4) & 15, 16)); result.append(Character.forDigit(value & 15, 16)); }
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static int utf8(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
}
