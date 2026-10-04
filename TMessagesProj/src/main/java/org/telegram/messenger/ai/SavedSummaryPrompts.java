/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** User-authored instructions only. Empty by default; selection never changes saved task preferences. */
public final class SavedSummaryPrompts {
    public static final int MAX_PROMPTS = 50;
    public static final int MAX_NAME_CODE_POINTS = 80;
    public static final int MAX_TEXT_CODE_POINTS = PromptOptions.MAX_CUSTOM_CODE_POINTS;
    public static final int MAX_STORAGE_BYTES = 512 * 1024;
    private static final String NAMESPACE = "saved_prompts";
    private SavedSummaryPrompts() { }

    public static final class Entry {
        public final String id;
        public final String name;
        public final String text;
        public final long revision;
        public final long updatedAtMillis;
        private Entry(String id, String name, String text, long revision, long updatedAtMillis) {
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}") || revision <= 0 || updatedAtMillis <= 0) throw corrupt();
            validate(name, text);
            this.id = id; this.name = name.trim(); this.text = text;
            this.revision = revision; this.updatedAtMillis = updatedAtMillis;
        }
    }

    /** Run these disk APIs off the UI thread. Owner is the identity captured by the opening page. */
    public static synchronized List<Entry> list(int account, long ownerId) {
        requireOwner(account, ownerId);
        ArrayList<Entry> entries = read(account, ownerId);
        requireOwner(account, ownerId);
        return Collections.unmodifiableList(entries);
    }

    public static synchronized Entry create(int account, long ownerId, String name, String text) {
        requireOwner(account, ownerId);
        validate(name, text);
        ArrayList<Entry> entries = read(account, ownerId);
        if (entries.size() >= MAX_PROMPTS) throw new IllegalStateException("最多保存 " + MAX_PROMPTS + " 条要求，请先删除不再使用的要求。");
        requireUniqueName(entries, null, name);
        Entry entry = new Entry(UUID.randomUUID().toString(), name, text, 1, System.currentTimeMillis());
        entries.add(0, entry);
        write(account, ownerId, entries);
        return entry;
    }

    /** An old editor cannot resurrect a deleted entry or overwrite a newer edit. */
    public static synchronized Entry update(int account, long ownerId, String id, long expectedRevision, String name, String text) {
        requireOwner(account, ownerId);
        validate(name, text);
        ArrayList<Entry> entries = read(account, ownerId);
        int index = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(id)) { index = i; break; }
        if (index < 0) throw new IllegalStateException("这条要求已删除，未恢复旧内容。请返回列表重新操作。");
        Entry previous = entries.get(index);
        if (previous.revision != expectedRevision || expectedRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("这条要求已被修改，请返回列表重新打开。");
        }
        requireUniqueName(entries, id, name);
        Entry entry = new Entry(id, name, text, previous.revision + 1, System.currentTimeMillis());
        entries.remove(index); entries.add(0, entry);
        write(account, ownerId, entries);
        return entry;
    }

    public static synchronized void delete(int account, long ownerId, String id) {
        requireOwner(account, ownerId);
        ArrayList<Entry> entries = read(account, ownerId);
        if (entries.removeIf(entry -> entry.id.equals(id))) write(account, ownerId, entries);
    }

    /** Logout removes only the previous account owner, including the encryption key. */
    public static synchronized void clearOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0) return;
        SummaryPrivateStorage.delete(NAMESPACE, account, ownerId);
    }

    public static void validate(String name, String text) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("请填写要求名称。");
        unicode(name);
        if (name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("要求名称最多 " + MAX_NAME_CODE_POINTS + " 个 Unicode 字符。");
        }
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) {
            throw new IllegalArgumentException("要求名称不能包含换行或控制字符。");
        }
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("请填写你自己的核心总结要求。");
        // Share exact Unicode and character-budget validation with the actual request options.
        new PromptOptions(PromptOptions.GENERAL, text);
    }

    private static ArrayList<Entry> read(int account, long ownerId) {
        requireOwner(account, ownerId);
        String plaintext = SummaryPrivateStorage.read(NAMESPACE, account, ownerId, MAX_STORAGE_BYTES);
        requireOwner(account, ownerId);
        ArrayList<Entry> entries = new ArrayList<>();
        if (plaintext == null) return entries;
        if (plaintext.getBytes(StandardCharsets.UTF_8).length > MAX_STORAGE_BYTES) throw corrupt();
        try {
            JSONObject root = new JSONObject(plaintext);
            if (root.getInt("version") != 1) throw corrupt();
            JSONArray values = root.getJSONArray("entries");
            if (values.length() > MAX_PROMPTS) throw corrupt();
            HashSet<String> ids = new HashSet<>();
            HashSet<String> names = new HashSet<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                Entry entry = new Entry(value.getString("id"), value.getString("name"), value.getString("text"),
                        value.getLong("revision"), value.getLong("updated_at"));
                if (!ids.add(entry.id) || !names.add(normalizeName(entry.name))) throw corrupt();
                entries.add(entry);
            }
            return entries;
        } catch (JSONException | IllegalArgumentException failure) {
            throw corrupt();
        }
    }

    private static void write(int account, long ownerId, List<Entry> entries) {
        requireOwner(account, ownerId);
        try {
            JSONArray values = new JSONArray();
            for (Entry entry : entries) values.put(new JSONObject().put("id", entry.id).put("name", entry.name)
                    .put("text", entry.text).put("revision", entry.revision).put("updated_at", entry.updatedAtMillis));
            String plaintext = new JSONObject().put("version", 1).put("entries", values).toString();
            requireOwner(account, ownerId);
            SummaryPrivateStorage.write(NAMESPACE, account, ownerId, plaintext, MAX_STORAGE_BYTES);
            requireOwner(account, ownerId);
        } catch (JSONException impossible) {
            throw new IllegalStateException("无法编码我的要求，未保存修改。", impossible);
        }
    }

    private static void requireUniqueName(List<Entry> entries, String exceptId, String name) {
        String normalized = normalizeName(name);
        for (Entry entry : entries) if (!entry.id.equals(exceptId) && normalizeName(entry.name).equals(normalized)) {
            throw new IllegalArgumentException("已有同名要求，请更换名称或编辑原要求。");
        }
    }
    private static String normalizeName(String name) {
        return Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    private static void unicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("要求名称包含无效的 Unicode 字符。");
                }
            } else if (Character.isLowSurrogate(value)) throw new IllegalArgumentException("要求名称包含无效的 Unicode 字符。");
        }
    }
    private static void requireOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("当前账号已变化，请重新打开我的要求。");
        }
    }
    private static IllegalStateException corrupt() {
        return new IllegalStateException("我的要求格式已损坏，原文件未被修改。请检查设备存储。");
    }
}
