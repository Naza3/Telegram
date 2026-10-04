/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.groupmessages;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Local display rules. Separate from AI exclusions and bound to the real account owner. */
public final class GroupMessageSettings {
    public static final int OFF = 0, FOLD = 1, HIDE = 2;
    public static final int MAX_UIDS = 1000, MAX_KEYWORDS = 100, MAX_KEYWORD_LENGTH = 100;
    private static final int MAX_UID_INPUT = 24000, MAX_KEYWORD_INPUT = 12000;
    private static final Rule DEFAULT = new Rule(0, OFF, Collections.emptySet(), Collections.emptyList(), false);
    private static final Map<String, Rule> cache = new LinkedHashMap<String, Rule>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Rule> entry) { return size() > 512; }
    };

    public static final class Rule {
        public final long revision;
        public final int filterMode;
        public final Set<Long> uids;
        public final List<String> keywords;
        public final boolean antiRevoke;

        private Rule(long revision, int filterMode, Set<Long> uids, List<String> keywords, boolean antiRevoke) {
            this.revision = revision;
            this.filterMode = filterMode;
            this.uids = Collections.unmodifiableSet(new LinkedHashSet<>(uids));
            this.keywords = Collections.unmodifiableList(new ArrayList<>(keywords));
            this.antiRevoke = antiRevoke;
        }
    }

    private GroupMessageSettings() { }

    /** Rendering/capture never enables a feature after unreadable or stale-owner settings. */
    public static synchronized Rule get(int account, long dialogId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || dialogId >= 0 || dialogId == Long.MIN_VALUE) return DEFAULT;
        long owner = UserConfig.getInstance(account).getClientUserId();
        if (owner <= 0) return DEFAULT;
        try { return load(account, owner, dialogId); }
        catch (RuntimeException ignored) { return DEFAULT; }
    }

    /** Editors should load on a worker thread; corruption is reported instead of silently overwritten. */
    public static synchronized Rule load(int account, long ownerId, long dialogId) {
        requireScope(account, ownerId, dialogId);
        String key = cacheKey(account, ownerId, dialogId);
        Rule result = cache.get(key);
        if (result == null) {
            String value = preferences(account, ownerId).getString(Long.toString(dialogId), null);
            result = value == null ? DEFAULT : decode(value);
            requireOwner(account, ownerId);
            cache.put(key, result);
        }
        requireOwner(account, ownerId);
        return result;
    }

    /** Validate the whole edit before one durable write; never overwrite another open editor. */
    public static synchronized Rule save(int account, long ownerId, long dialogId, long expectedRevision,
            int filterMode, String uidText, String keywordText, boolean antiRevoke) {
        requireScope(account, ownerId, dialogId);
        if (filterMode < OFF || filterMode > HIDE) throw new IllegalArgumentException("请选择有效的屏蔽方式。");
        Set<Long> uids = parseUids(uidText);
        List<String> keywords = parseKeywords(keywordText);
        Rule current = load(account, ownerId, dialogId);
        if (current.revision != expectedRevision || expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("本群设置已在其他页面修改，请重新打开后编辑。");
        }
        Rule next = new Rule(current.revision + 1, filterMode, uids, keywords, antiRevoke);
        String encoded = encode(next);
        requireOwner(account, ownerId);
        SharedPreferences prefs = preferences(account, ownerId);
        String key = Long.toString(dialogId);
        String previous = prefs.getString(key, null);
        if (!prefs.edit().putString(key, encoded).commit()) {
            // SharedPreferences changes memory even when its disk write fails; undo that memory state.
            SharedPreferences.Editor rollback = prefs.edit();
            if (previous == null) rollback.remove(key); else rollback.putString(key, previous);
            rollback.apply();
            throw new IllegalStateException("保存失败，请检查手机存储空间后重试。");
        }
        requireOwner(account, ownerId);
        cache.put(cacheKey(account, ownerId, dialogId), next);
        return next;
    }

    /** Logout API: fixed owner namespace prevents an old cleanup from touching a new login. */
    public static synchronized boolean clearOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0) return false;
        String prefix = account + ":" + ownerId + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
        return preferences(account, ownerId).edit().clear().commit();
    }

    public static Set<Long> parseUids(String value) {
        if (value == null || value.length() > MAX_UID_INPUT) throw new IllegalArgumentException("UID 输入最多 " + MAX_UID_INPUT + " 个字符。");
        TreeSet<Long> result = new TreeSet<>();
        int start = -1;
        for (int i = 0; i <= value.length(); i++) {
            char c = i == value.length() ? '\n' : value.charAt(i);
            if (c == ',' || c == '，' || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (start >= 0) {
                    try {
                        long id = Long.parseLong(value.substring(start, i));
                        if (id <= 0) throw invalidUid();
                        result.add(id);
                    } catch (NumberFormatException e) { throw invalidUid(); }
                    if (result.size() > MAX_UIDS) throw new IllegalArgumentException("每个群最多填写 " + MAX_UIDS + " 个 UID。");
                    start = -1;
                }
            } else if (c >= '0' && c <= '9') {
                if (start < 0) start = i;
                if (i - start >= 19) throw invalidUid();
            } else throw invalidUid();
        }
        return Collections.unmodifiableSet(result);
    }

    public static List<String> parseKeywords(String value) {
        if (value == null || value.length() > MAX_KEYWORD_INPUT) throw new IllegalArgumentException("关键词输入最多 " + MAX_KEYWORD_INPUT + " 个字符。");
        ArrayList<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : value.split("\\r\\n|\\r|\\n", -1)) {
            String word = line.trim();
            if (word.isEmpty()) continue;
            if (word.length() > MAX_KEYWORD_LENGTH) throw new IllegalArgumentException("每条关键词最多 " + MAX_KEYWORD_LENGTH + " 个字符。");
            for (int i = 0; i < word.length(); i++) {
                if (Character.isISOControl(word.charAt(i))) throw new IllegalArgumentException("关键词不能含制表符等控制字符。");
            }
            if (seen.add(word.toLowerCase(Locale.ROOT))) result.add(word);
            if (result.size() > MAX_KEYWORDS) throw new IllegalArgumentException("每个群最多填写 " + MAX_KEYWORDS + " 条关键词。");
        }
        return Collections.unmodifiableList(result);
    }

    public static String formatUids(Set<Long> ids) {
        StringBuilder text = new StringBuilder();
        for (Long id : ids) { if (text.length() > 0) text.append('\n'); text.append(id); }
        return text.toString();
    }

    public static String formatKeywords(List<String> words) {
        StringBuilder text = new StringBuilder();
        for (String word : words) { if (text.length() > 0) text.append('\n'); text.append(word); }
        return text.toString();
    }

    private static String encode(Rule rule) {
        try {
            return new JSONObject().put("version", 1).put("revision", Long.toString(rule.revision))
                    .put("mode", rule.filterMode).put("uids", formatUids(rule.uids))
                    .put("keywords", formatKeywords(rule.keywords)).put("retain", rule.antiRevoke).toString();
        } catch (JSONException e) { throw new IllegalStateException("无法保存本群设置。", e); }
    }

    private static Rule decode(String value) {
        try {
            if (value.length() > 50000) throw new IllegalArgumentException();
            JSONObject json = new JSONObject(value);
            if (!Integer.valueOf(1).equals(json.get("version"))) throw new IllegalArgumentException();
            Object revisionValue = json.get("revision"), modeValue = json.get("mode"),
                    uidValue = json.get("uids"), keywordsValue = json.get("keywords"), retain = json.get("retain");
            if (!(revisionValue instanceof String) || !(modeValue instanceof Integer) || !(uidValue instanceof String)
                    || !(keywordsValue instanceof String) || !(retain instanceof Boolean)) throw new IllegalArgumentException();
            long revision = Long.parseLong((String) revisionValue);
            int mode = (Integer) modeValue;
            if (revision <= 0 || mode < OFF || mode > HIDE) throw new IllegalArgumentException();
            return new Rule(revision, mode, parseUids((String) uidValue), parseKeywords((String) keywordsValue), (Boolean) retain);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IllegalStateException("本群设置无法读取，请先关闭页面后重试。", e);
        }
    }

    private static SharedPreferences preferences(int account, long ownerId) {
        return ApplicationLoader.applicationContext.getSharedPreferences("group_messages_" + account + "_" + ownerId, Context.MODE_PRIVATE);
    }
    private static String cacheKey(int account, long ownerId, long dialogId) { return account + ":" + ownerId + ":" + dialogId; }
    private static IllegalArgumentException invalidUid() { return new IllegalArgumentException("请填写正整数 Telegram UID，用换行、空格或逗号分隔；不支持 @用户名。"); }
    private static void requireScope(int account, long ownerId, long dialogId) {
        requireOwner(account, ownerId);
        if (dialogId >= 0 || dialogId == Long.MIN_VALUE) throw new IllegalArgumentException("请从有效的群打开消息管理。");
    }
    private static void requireOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("账号已切换或退出，请重新打开消息管理。");
        }
    }
}
