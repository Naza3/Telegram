/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

/** Only a bounded interruption marker. It cannot resume a model request or retain its input. */
public final class SummaryTaskCheckpoint {
    private static final String PREFIX = "local_ai_task_checkpoint_";

    public static final class Record {
        public final String taskId;
        public final long dialogId, topicId, startedAtMillis;
        private Record(String taskId, long dialogId, long topicId, long startedAtMillis) {
            this.taskId = taskId;
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.startedAtMillis = startedAtMillis;
        }
    }

    private SummaryTaskCheckpoint() { }

    public static synchronized boolean begin(int account, long ownerId, String taskId,
            long dialogId, long topicId, long startedAtMillis) {
        checkOwner(account, ownerId);
        validate(taskId, dialogId, topicId, startedAtMillis);
        try {
            String value = new JSONObject().put("version", 1).put("task_id", taskId)
                    .put("dialog_id", dialogId).put("topic_id", topicId)
                    .put("started_at", startedAtMillis).toString();
            checkOwner(account, ownerId);
            return replace(MessagesController.getMainSettings(account), key(ownerId), value);
        } catch (JSONException impossible) {
            throw new IllegalArgumentException("无法保存任务标记。", impossible);
        }
    }

    /** A marker without a live task means the prior task did not confirm its end, not that it failed. */
    public static synchronized Record read(int account, long ownerId) {
        checkOwner(account, ownerId);
        String value = MessagesController.getMainSettings(account).getString(key(ownerId), null);
        if (value == null) return null;
        try {
            if (value.length() > 512) throw new IllegalArgumentException();
            JSONObject json = new JSONObject(value);
            if (json.getInt("version") != 1) throw new IllegalArgumentException();
            Record record = new Record(json.getString("task_id"), json.getLong("dialog_id"),
                    json.getLong("topic_id"), json.getLong("started_at"));
            validate(record.taskId, record.dialogId, record.topicId, record.startedAtMillis);
            checkOwner(account, ownerId);
            return record;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IllegalStateException("上次任务的恢复标记无法读取，请查看已保存的总结历史；不会自动重发请求。");
        }
    }

    /** A late callback must never clear a newer task's marker. */
    public static synchronized boolean finish(int account, long ownerId, String taskId) {
        checkOwner(account, ownerId);
        Record record = read(account, ownerId);
        if (record == null || !record.taskId.equals(taskId)) return true;
        checkOwner(account, ownerId);
        return replace(MessagesController.getMainSettings(account), key(ownerId), null);
    }

    /** Explicit recovery dismissal or logout; safe even after the account slot changed. */
    public static synchronized void clearOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0) return;
        MessagesController.getMainSettings(account).edit().remove(key(ownerId)).apply();
    }

    private static String key(long ownerId) { return PREFIX + ownerId; }

    private static void checkOwner(int account, long ownerId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.getInstance(account).getClientUserId() != ownerId) {
            throw new IllegalStateException("Telegram 账号已变化，请重新打开 AI 总结。");
        }
    }

    private static void validate(String taskId, long dialogId, long topicId, long startedAtMillis) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,80}") || dialogId >= 0
                || dialogId == Long.MIN_VALUE || topicId < 0 || topicId > Integer.MAX_VALUE
                || startedAtMillis <= 0) throw new IllegalArgumentException("任务标记无效。");
    }

    private static boolean replace(SharedPreferences prefs, String key, String value) {
        String previous = prefs.getString(key, null);
        SharedPreferences.Editor edit = prefs.edit();
        if (value == null) edit.remove(key); else edit.putString(key, value);
        if (edit.commit()) return true;
        SharedPreferences.Editor rollback = prefs.edit();
        if (previous == null) rollback.remove(key); else rollback.putString(key, previous);
        rollback.commit();
        return false;
    }
}
