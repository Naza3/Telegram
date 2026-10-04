/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Encrypted, bounded generated summaries. Raw source bodies and connection secrets are absent. */
public final class SummaryHistoryStore {
    public static final int MAX_RECORDS = 100;
    public static final int MAX_STORAGE_BYTES = 8 * 1024 * 1024;
    // AES-GCM/base64 overhead must also fit inside the on-disk bound.
    private static final int MAX_PLAINTEXT_BYTES = (MAX_STORAGE_BYTES - 512) / 4 * 3;
    private static final int MAX_SOURCES = 10000;
    private static final Comparator<Record> NEWEST_FIRST = (left, right) -> {
        int time = Long.compare(right.generatedAtMillis, left.generatedAtMillis);
        return time == 0 ? left.id.compareTo(right.id) : time;
    };

    private SummaryHistoryStore() { }

    public static final class Record {
        public final String id;
        public final long dialogId;
        public final long topicId;
        public final long generatedAtMillis;
        public final String chatTitle;
        public final String rangeLabel;
        public final String coverageNote;
        public final String templateLabel;
        public final String customInstructions;
        public final String model;
        public final String summary;
        public final boolean partial;
        /** Whether [mN] in this result denotes an ordered source reference. */
        public final boolean sourceLinks;
        public final List<SummarySourceReference> sources;

        public Record(String id, long dialogId, long topicId, long generatedAtMillis,
                String chatTitle, String rangeLabel, String coverageNote, String templateLabel,
                String customInstructions, String model, String summary, boolean partial,
                List<SummarySourceReference> sources) {
            this(id, dialogId, topicId, generatedAtMillis, chatTitle, rangeLabel, coverageNote, templateLabel,
                    customInstructions, model, summary, partial, sources, true);
        }

        public Record(String id, long dialogId, long topicId, long generatedAtMillis,
                String chatTitle, String rangeLabel, String coverageNote, String templateLabel,
                String customInstructions, String model, String summary, boolean partial,
                List<SummarySourceReference> sources, boolean sourceLinks) {
            requireId(id);
            if (dialogId >= 0 || dialogId == Long.MIN_VALUE || topicId < 0 || generatedAtMillis <= 0) {
                throw new IllegalArgumentException("总结历史的聊天或时间无效。");
            }
            if (summary == null || summary.trim().isEmpty() || summary.length() > MAX_PLAINTEXT_BYTES) {
                throw new IllegalArgumentException("总结正文为空或超过保存容量。");
            }
            if (sources == null || sources.size() > MAX_SOURCES) {
                throw new IllegalArgumentException("总结来源数量无效。");
            }
            ArrayList<SummarySourceReference> snapshot = new ArrayList<>(sources);
            for (SummarySourceReference source : snapshot) {
                if (source == null || source.dialogId != dialogId) {
                    throw new IllegalArgumentException("总结来源不属于当前聊天。");
                }
            }
            this.id = id;
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.generatedAtMillis = generatedAtMillis;
            this.chatTitle = metadata(chatTitle);
            this.rangeLabel = metadata(rangeLabel);
            this.coverageNote = metadata(coverageNote);
            this.templateLabel = metadata(templateLabel);
            this.customInstructions = metadata(customInstructions);
            this.model = metadata(model);
            this.summary = summary;
            this.partial = partial;
            this.sourceLinks = sourceLinks;
            this.sources = Collections.unmodifiableList(snapshot);
        }
    }

    /** Worker-thread API. dialogId=0 selects all chats; topicId=-1 selects all chat topics. */
    public static synchronized List<Record> list(int account, long ownerId, long dialogId, long topicId) {
        requireOwner(account, ownerId);
        requireScope(dialogId, topicId);
        ArrayList<Record> found = new ArrayList<>();
        for (Record record : read(account, ownerId)) {
            if (matches(record, dialogId, topicId)) found.add(record);
        }
        requireOwner(account, ownerId);
        return Collections.unmodifiableList(found);
    }

    public static synchronized Record get(int account, long ownerId, String id) {
        requireOwner(account, ownerId);
        requireId(id);
        for (Record record : read(account, ownerId)) {
            if (record.id.equals(id)) {
                requireOwner(account, ownerId);
                return record;
            }
        }
        requireOwner(account, ownerId);
        return null;
    }

    public static synchronized void save(int account, long ownerId, Record record) {
        requireOwner(account, ownerId);
        if (record == null) throw new IllegalArgumentException("没有可保存的总结。");
        ArrayList<Record> records = read(account, ownerId);
        records.removeIf(existing -> existing.id.equals(record.id));
        records.add(record);
        records.sort(NEWEST_FIRST);
        String plaintext;
        while (true) {
            plaintext = encode(records);
            if (records.size() <= MAX_RECORDS && utf8Length(plaintext) <= MAX_PLAINTEXT_BYTES) break;
            Record removed = records.remove(records.size() - 1);
            if (removed.id.equals(record.id)) {
                throw new IllegalStateException("这条总结超出历史保存容量，请缩短正文或先清理旧记录。");
            }
        }
        write(account, ownerId, plaintext);
    }

    public static synchronized void delete(int account, long ownerId, String id) {
        requireOwner(account, ownerId);
        requireId(id);
        ArrayList<Record> records = read(account, ownerId);
        if (!records.removeIf(record -> record.id.equals(id))) return;
        writeRecords(account, ownerId, records);
    }

    public static synchronized void clear(int account, long ownerId, long dialogId, long topicId) {
        requireOwner(account, ownerId);
        requireScope(dialogId, topicId);
        if (dialogId == 0) {
            // Explicit clear-all remains usable even if the key or JSON cannot be recovered.
            clearOwner(account, ownerId);
            return;
        }
        ArrayList<Record> records = read(account, ownerId);
        if (!records.removeIf(record -> matches(record, dialogId, topicId))) return;
        writeRecords(account, ownerId, records);
    }

    /** Logout cleanup is allowed after identity changes, but can only touch the supplied old owner. */
    public static synchronized void clearOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0) return;
        RuntimeException failure = null;
        try {
            SummaryHistoryStorage.delete(account, ownerId);
        } catch (RuntimeException error) {
            failure = error;
        }
        try {
            // Also revoke access to a leftover encrypted file if file deletion failed.
            SummaryHistoryCipher.deleteKey(account, ownerId);
        } catch (RuntimeException error) {
            if (failure == null) failure = error;
        }
        if (failure != null) throw failure;
    }

    private static void writeRecords(int account, long ownerId, ArrayList<Record> records) {
        requireOwner(account, ownerId);
        if (records.isEmpty()) SummaryHistoryStorage.delete(account, ownerId);
        else write(account, ownerId, encode(records));
    }

    private static void write(int account, long ownerId, String plaintext) {
        requireOwner(account, ownerId);
        String encrypted = SummaryHistoryCipher.encrypt(account, ownerId, plaintext);
        if (utf8Length(encrypted) > MAX_STORAGE_BYTES) {
            throw new IllegalStateException("加密后的总结历史超过保存容量，未写入新记录。");
        }
        requireOwner(account, ownerId);
        SummaryHistoryStorage.write(account, ownerId, encrypted);
        requireOwner(account, ownerId);
    }

    private static ArrayList<Record> read(int account, long ownerId) {
        requireOwner(account, ownerId);
        String encrypted = SummaryHistoryStorage.read(account, ownerId, MAX_STORAGE_BYTES);
        if (encrypted == null) return new ArrayList<>();
        String plaintext = SummaryHistoryCipher.decrypt(account, ownerId, encrypted);
        requireOwner(account, ownerId);
        if (utf8Length(plaintext) > MAX_PLAINTEXT_BYTES) throw corrupt();
        try {
            JSONObject root = new JSONObject(plaintext);
            if (root.getInt("version") != 1) throw corrupt();
            JSONArray entries = root.getJSONArray("records");
            if (entries.length() > MAX_RECORDS) throw corrupt();
            ArrayList<Record> records = new ArrayList<>();
            HashSet<String> ids = new HashSet<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject value = entries.getJSONObject(i);
                JSONArray encodedSources = value.getJSONArray("sources");
                if (encodedSources.length() > MAX_SOURCES) throw corrupt();
                ArrayList<SummarySourceReference> sources = new ArrayList<>();
                for (int j = 0; j < encodedSources.length(); j++) {
                    JSONObject source = encodedSources.getJSONObject(j);
                    sources.add(new SummarySourceReference(source.getLong("dialog_id"), source.getInt("id"),
                            source.getInt("date"), source.getInt("edit_date"), source.getLong("sender_id"),
                            source.getString("text_hash")));
                }
                // Missing on older records, whose [mN] output used ordered source references.
                Object sourceLinks = value.has("source_links") ? value.get("source_links") : Boolean.TRUE;
                if (!(sourceLinks instanceof Boolean)) throw corrupt();
                Record record = new Record(value.getString("id"), value.getLong("dialog_id"),
                        value.getLong("topic_id"), value.getLong("generated_at"), value.getString("chat_title"),
                        value.getString("range_label"), value.getString("coverage_note"), value.getString("template_label"),
                        value.getString("custom_instructions"), value.getString("model"), value.getString("summary"),
                        value.getBoolean("partial"), sources, (Boolean) sourceLinks);
                if (!ids.add(record.id)) throw corrupt();
                records.add(record);
            }
            records.sort(NEWEST_FIRST);
            return records;
        } catch (JSONException | IllegalArgumentException error) {
            throw corrupt();
        }
    }

    private static String encode(List<Record> records) {
        try {
            JSONArray entries = new JSONArray();
            for (Record record : records) {
                JSONArray sources = new JSONArray();
                for (SummarySourceReference source : record.sources) {
                    sources.put(new JSONObject().put("dialog_id", source.dialogId).put("id", source.id)
                            .put("date", source.date).put("edit_date", source.editDate)
                            .put("sender_id", source.senderId).put("text_hash", source.textHash));
                }
                entries.put(new JSONObject().put("id", record.id).put("dialog_id", record.dialogId)
                        .put("topic_id", record.topicId).put("generated_at", record.generatedAtMillis)
                        .put("chat_title", record.chatTitle).put("range_label", record.rangeLabel)
                        .put("coverage_note", record.coverageNote).put("template_label", record.templateLabel)
                        .put("custom_instructions", record.customInstructions).put("model", record.model)
                        .put("summary", record.summary).put("partial", record.partial)
                        .put("source_links", record.sourceLinks).put("sources", sources));
            }
            return new JSONObject().put("version", 1).put("records", entries).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("无法编码总结历史，未保存记录。", impossible);
        }
    }

    private static boolean matches(Record record, long dialogId, long topicId) {
        return dialogId == 0 || record.dialogId == dialogId && (topicId == -1 || record.topicId == topicId);
    }

    private static String metadata(String value) {
        if (value == null) return "";
        if (value.length() > 65536) throw new IllegalArgumentException("总结历史附加信息过长。");
        return value;
    }

    private static int utf8Length(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }

    private static void requireScope(long dialogId, long topicId) {
        if (dialogId > 0 || dialogId == Long.MIN_VALUE || topicId < -1) {
            throw new IllegalArgumentException("无效的总结历史范围。");
        }
    }

    private static void requireId(String id) {
        if (id == null || id.trim().isEmpty() || id.length() > 256) {
            throw new IllegalArgumentException("总结历史记录标识无效。");
        }
    }

    private static void requireOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("当前账号已变化，请重新打开总结历史。");
        }
    }

    private static IllegalStateException corrupt() {
        return new IllegalStateException("总结历史格式已损坏，未修改原记录。可清空该账号的总结历史后重试。");
    }
}
