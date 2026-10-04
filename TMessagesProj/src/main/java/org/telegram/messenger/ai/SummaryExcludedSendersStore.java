/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Encrypted, account-owner and group scoped UID exclusions. Disk APIs run off the UI thread. */
public final class SummaryExcludedSendersStore {
    public static final int MAX_UIDS = 1000;
    public static final int MAX_GROUPS = 256;
    public static final int MAX_INPUT_CHARACTERS = 24000;
    public static final int MAX_STORAGE_BYTES = 1024 * 1024;
    private static final String NAMESPACE = "excluded_senders";

    public static final class Snapshot {
        public final long revision;
        public final Set<Long> ids;
        private Snapshot(long revision, Set<Long> ids) {
            this.revision = revision;
            this.ids = immutableIds(ids);
        }
    }

    private SummaryExcludedSendersStore() { }

    public static synchronized Snapshot load(int account, long ownerId, long dialogId) {
        requireScope(account, ownerId, dialogId);
        Snapshot value = read(account, ownerId).get(dialogId);
        requireOwner(account, ownerId);
        return value == null ? new Snapshot(0, Collections.emptySet()) : value;
    }

    /** Empty lists retain a revision tombstone, so an older editor cannot restore a cleared list. */
    public static synchronized Snapshot save(int account, long ownerId, long dialogId,
            long expectedRevision, Set<Long> ids) {
        requireScope(account, ownerId, dialogId);
        Set<Long> copy = immutableIds(ids);
        LinkedHashMap<Long, Snapshot> groups = read(account, ownerId);
        Snapshot current = groups.get(dialogId);
        long revision = current == null ? 0 : current.revision;
        if (expectedRevision < 0 || expectedRevision != revision || revision == Long.MAX_VALUE) {
            throw new IllegalStateException("排除名单已在其他页面修改或清空，请重新打开后编辑。");
        }
        if (current == null && groups.size() >= MAX_GROUPS) {
            throw new IllegalStateException("已达到 " + MAX_GROUPS + " 个群的排除名单容量，原名单未修改。");
        }
        Snapshot next = new Snapshot(revision + 1, copy);
        groups.put(dialogId, next);
        write(account, ownerId, groups);
        return next;
    }

    public static synchronized void clearOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0) return;
        SummaryPrivateStorage.delete(NAMESPACE, account, ownerId);
    }

    /** Strict decimal parsing, without floating-point conversion or username resolution. */
    public static Set<Long> parse(String input) {
        if (input == null || input.length() > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("UID 输入最多 " + MAX_INPUT_CHARACTERS + " 个字符。");
        }
        TreeSet<Long> ids = new TreeSet<>();
        int start = -1;
        for (int i = 0; i <= input.length(); i++) {
            char value = i == input.length() ? '\n' : input.charAt(i);
            boolean separator = value == ',' || value == '，' || Character.isWhitespace(value) || Character.isSpaceChar(value);
            if (separator) {
                if (start >= 0) {
                    ids.add(positiveDecimal(input.substring(start, i)));
                    if (ids.size() > MAX_UIDS) throw new IllegalArgumentException("每个群最多排除 " + MAX_UIDS + " 个 UID。");
                    start = -1;
                }
            } else if (value >= '0' && value <= '9') {
                if (start < 0) start = i;
                if (i - start >= 19) throw invalidUid();
            } else throw invalidUid();
        }
        return Collections.unmodifiableSet(ids);
    }

    public static String format(Set<Long> ids) {
        StringBuilder result = new StringBuilder();
        for (long id : immutableIds(ids)) {
            if (result.length() > 0) result.append('\n');
            result.append(id);
        }
        return result.toString();
    }

    private static Set<Long> immutableIds(Set<Long> ids) {
        if (ids == null || ids.size() > MAX_UIDS) throw new IllegalArgumentException("每个群最多排除 " + MAX_UIDS + " 个 UID。");
        TreeSet<Long> copy = new TreeSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) throw invalidUid();
            copy.add(id);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static LinkedHashMap<Long, Snapshot> read(int account, long ownerId) {
        requireOwner(account, ownerId);
        String value = SummaryPrivateStorage.read(NAMESPACE, account, ownerId, MAX_STORAGE_BYTES);
        requireOwner(account, ownerId);
        LinkedHashMap<Long, Snapshot> result = new LinkedHashMap<>();
        if (value == null) return result;
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_STORAGE_BYTES) throw corrupt();
        try {
            JSONObject root = new JSONObject(value);
            if (!Integer.valueOf(1).equals(root.get("version"))) throw corrupt();
            JSONArray groups = root.getJSONArray("groups");
            if (groups.length() > MAX_GROUPS) throw corrupt();
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.getJSONObject(i);
                // Identity strings preserve every 64-bit integer on any JSON implementation.
                String rawDialog = exactString(group.get("dialog"));
                if (!rawDialog.matches("-[1-9][0-9]{0,18}")) throw corrupt();
                long dialog = Long.parseLong(rawDialog);
                if (dialog == Long.MIN_VALUE) throw corrupt();
                long revision = positiveDecimal(exactString(group.get("revision")));
                JSONArray values = group.getJSONArray("ids");
                if (values.length() > MAX_UIDS) throw corrupt();
                TreeSet<Long> ids = new TreeSet<>();
                for (int j = 0; j < values.length(); j++) {
                    if (!ids.add(positiveDecimal(exactString(values.get(j))))) throw corrupt();
                }
                if (result.put(dialog, new Snapshot(revision, ids)) != null) throw corrupt();
            }
            return result;
        } catch (JSONException | IllegalArgumentException error) { throw corrupt(); }
    }

    private static void write(int account, long ownerId, LinkedHashMap<Long, Snapshot> groups) {
        requireOwner(account, ownerId);
        try {
            JSONArray values = new JSONArray();
            for (Map.Entry<Long, Snapshot> entry : groups.entrySet()) {
                JSONArray ids = new JSONArray();
                for (long id : entry.getValue().ids) ids.put(Long.toString(id));
                values.put(new JSONObject().put("dialog", Long.toString(entry.getKey()))
                        .put("revision", Long.toString(entry.getValue().revision)).put("ids", ids));
            }
            String value = new JSONObject().put("version", 1).put("groups", values).toString();
            requireOwner(account, ownerId);
            SummaryPrivateStorage.write(NAMESPACE, account, ownerId, value, MAX_STORAGE_BYTES);
            requireOwner(account, ownerId);
        } catch (JSONException error) { throw new IllegalStateException("无法保存 UID 排除名单，原名单未修改。", error); }
    }

    private static String exactString(Object value) {
        if (!(value instanceof String)) throw corrupt();
        return (String) value;
    }
    private static long positiveDecimal(String value) {
        if (!value.matches("[0-9]{1,19}")) throw invalidUid();
        try {
            long result = Long.parseLong(value);
            if (result > 0) return result;
        } catch (NumberFormatException ignored) { }
        throw invalidUid();
    }
    private static IllegalArgumentException invalidUid() {
        return new IllegalArgumentException("请只填写正整数 Telegram 用户 UID，用逗号、空格或换行分隔；不支持用户名、负数或超过 64 位的数字。");
    }
    private static void requireScope(int account, long ownerId, long dialogId) {
        requireOwner(account, ownerId);
        if (dialogId >= 0 || dialogId == Long.MIN_VALUE) throw new IllegalArgumentException("请先选择有效的来源群。");
    }
    private static void requireOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("账号身份已变化，请重新打开排除名单。");
        }
    }
    private static IllegalStateException corrupt() {
        return new IllegalStateException("UID 排除名单格式损坏，原文件未修改。请检查设备存储。");
    }
}
