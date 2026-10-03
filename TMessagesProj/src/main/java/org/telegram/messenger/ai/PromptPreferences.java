/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

/** Saved directions are namespaced by real account identity, dialog and topic, never slot alone. */
public final class PromptPreferences {
    private static final String PREFIX = "local_ai_prompt_";
    public enum Scope { BUILTIN, ACCOUNT, CHAT, SESSION }

    public static final class Resolved {
        public final PromptOptions options;
        public final Scope scope;
        private Resolved(PromptOptions options, Scope scope) { this.options = options; this.scope = scope; }
    }

    private PromptPreferences() {}

    public static synchronized Resolved load(int account, long ownerId, long dialogId, long topicId) {
        checkOwner(account, ownerId);
        checkChat(dialogId, topicId);
        SharedPreferences preferences = MessagesController.getMainSettings(account);
        PromptOptions chat = decode(preferences.getString(chatKey(ownerId, dialogId, topicId), null));
        PromptOptions global = decode(preferences.getString(ownerPrefix(ownerId) + "default", null));
        checkOwner(account, ownerId);
        return chat != null ? new Resolved(chat, Scope.CHAT)
                : global != null ? new Resolved(global, Scope.ACCOUNT)
                : new Resolved(PromptOptions.DEFAULT, Scope.BUILTIN);
    }

    public static synchronized void save(int account, long ownerId, long dialogId, long topicId,
            Scope scope, PromptOptions options) {
        checkOwner(account, ownerId);
        checkChat(dialogId, topicId);
        if (options == null || scope == null || scope == Scope.BUILTIN) {
            throw new IllegalArgumentException("请选择有效的总结方向和保存范围。");
        }
        if (scope == Scope.SESSION) return;
        String key = scope == Scope.CHAT ? chatKey(ownerId, dialogId, topicId) : ownerPrefix(ownerId) + "default";
        String encoded = encode(options);
        checkOwner(account, ownerId);
        writeKey(MessagesController.getMainSettings(account), key, encoded, "总结方向保存失败，请重试。");
    }

    public static synchronized void clearChat(int account, long ownerId, long dialogId, long topicId) {
        checkOwner(account, ownerId);
        checkChat(dialogId, topicId);
        writeKey(MessagesController.getMainSettings(account), chatKey(ownerId, dialogId, topicId), null,
                "清除群总结方向失败，请重试。");
    }

    public static synchronized void clearAccountDefault(int account, long ownerId) {
        checkOwner(account, ownerId);
        writeKey(MessagesController.getMainSettings(account), ownerPrefix(ownerId) + "default", null,
                "清除账号总结方向失败，请重试。");
    }

    /** Logout may call this after identity changed; only the explicitly named owner's keys are removed. */
    public static synchronized void clearOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0) return;
        SharedPreferences preferences = MessagesController.getMainSettings(account);
        SharedPreferences.Editor editor = preferences.edit();
        String prefix = ownerPrefix(ownerId);
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        // Logout cleanup should not prevent Telegram account teardown if persistence fails.
        editor.apply();
    }

    private static void writeKey(SharedPreferences preferences, String key, String value, String failure) {
        String previous = preferences.getString(key, null);
        SharedPreferences.Editor editor = preferences.edit();
        if (value == null) editor.remove(key); else editor.putString(key, value);
        if (editor.commit()) return;
        // Android updates the process-local preferences before reporting a disk write failure.
        // Restore the prior value so a failed save does not silently become the next task's direction.
        SharedPreferences.Editor restore = preferences.edit();
        if (previous == null) restore.remove(key); else restore.putString(key, previous);
        boolean restored = restore.commit();
        throw new IllegalStateException(restored ? failure
                : failure + " 原设置已恢复到本次会话，但磁盘写入仍未确认，重启后请检查。");
    }

    private static void checkOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("Telegram 账号已变化，请重新打开总结面板。");
        }
    }

    private static void checkChat(long dialogId, long topicId) {
        if (dialogId >= 0 || topicId < 0 || topicId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("群或话题范围无效。");
        }
    }

    private static String ownerPrefix(long ownerId) { return PREFIX + ownerId + "_"; }
    private static String chatKey(long ownerId, long dialogId, long topicId) {
        return ownerPrefix(ownerId) + "chat_" + dialogId + "_topic_" + topicId;
    }

    private static String encode(PromptOptions options) {
        try {
            return new JSONObject().put("template", options.templateId).put("custom", options.customInstructions)
                    .put("template_version", options.templateVersion).put("rules_version", options.builtinRulesVersion).toString();
        } catch (JSONException error) {
            throw new IllegalArgumentException("总结方向无法保存。", error);
        }
    }

    private static PromptOptions decode(String encoded) {
        if (encoded == null) return null;
        try {
            JSONObject json = new JSONObject(encoded);
            int templateVersion = json.optInt("template_version", 1);
            int rulesVersion = json.optInt("rules_version", 1);
            if (templateVersion < 1 || templateVersion > PromptOptions.TEMPLATE_VERSION
                    || rulesVersion < 1 || rulesVersion > PromptOptions.BUILTIN_RULES_VERSION) return null;
            return new PromptOptions(json.getString("template"), json.optString("custom", ""));
        } catch (JSONException | IllegalArgumentException error) {
            // Invalid/unsupported preferences fall back through the documented scope hierarchy.
            return null;
        }
    }
}
