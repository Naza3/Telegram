/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Foreground lifetime for explicitly started summary tasks; never restarts or reconstructs work. */
public final class SummaryForegroundService extends Service {
    public static final String ACTION_OPEN_CENTER = "org.telegram.messenger.OPEN_AI_SUMMARY_CENTER";
    public static final String EXTRA_ACCOUNT = "currentAccount";
    public static final String EXTRA_OWNER = "summaryOwnerId";
    public static final long MAX_TASK_DURATION_MS = 2 * 60 * 60 * 1000L;
    private static final String ACTION_START = "org.telegram.messenger.START_AI_SUMMARY";
    private static final String ACTION_CANCEL = "org.telegram.messenger.CANCEL_AI_SUMMARY";
    private static final String EXTRA_TASK = "summaryTaskId";
    private static final String CHANNEL_ID = "ai_summary_progress";
    private static final int NOTIFICATION_ID = 0x41495355;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Accessed only on the main thread. UUIDs are additionally checked against account and owner.
    private static final LinkedHashMap<String, Task> TASKS = new LinkedHashMap<>();

    public interface FailureCallback { void onFailure(String safeMessage); }

    private static final class Task {
        final int account;
        final long ownerId, startedAt = SystemClock.elapsedRealtime();
        final String taskId;
        final Runnable cancel;
        final FailureCallback failure;
        SummaryForegroundService host;
        Runnable startupTimeout, deadline;
        String stage = "正在读取消息";
        int completed, total;

        Task(int account, long ownerId, String taskId, Runnable cancel, FailureCallback failure) {
            this.account = account; this.ownerId = ownerId; this.taskId = taskId;
            this.cancel = cancel; this.failure = failure;
        }
    }

    private PowerManager.WakeLock wakeLock;
    private long wakeDeadline;
    private long lastNotificationAt;
    private int latestStartId;
    private boolean foreground;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            for (Task task : new ArrayList<>(TASKS.values())) {
                if (task.host == SummaryForegroundService.this) {
                    if (!validOwner(task.account, task.ownerId)) finish(task, null, true);
                    else if (SystemClock.elapsedRealtime() - task.startedAt >= MAX_TASK_DURATION_MS) {
                        finish(task, "本次总结已达到 2 小时运行上限，任务未完成。请缩小范围后重新开始。", false);
                    }
                }
            }
            refreshSafely();
            if (foreground) MAIN.postDelayed(this, 1000);
        }
    };

    /** Call from a visible UI's explicit user action, before starting asynchronous loading/inference. */
    public static boolean start(int account, long ownerId, String taskId, Runnable cancelTask) {
        return start(account, ownerId, taskId, cancelTask, null);
    }

    /** False is an immediate start rejection; later service failures use the main-thread callback. */
    public static boolean start(int account, long ownerId, String taskId, Runnable cancelTask,
            FailureCallback onFailure) {
        if (Looper.myLooper() != Looper.getMainLooper() || ApplicationLoader.mainInterfacePaused
                || !validOwner(account, ownerId) || taskId == null || taskId.isEmpty() || cancelTask == null) {
            return false;
        }
        Task existing = TASKS.get(taskId);
        if (existing != null) return matches(existing, account, ownerId, taskId);
        for (Task task : TASKS.values()) if (task.account == account) return false;
        Task task = new Task(account, ownerId, taskId, cancelTask, onFailure);
        TASKS.put(taskId, task);
        task.startupTimeout = () -> {
            if (TASKS.get(taskId) == task && task.host == null) {
                finish(task, "无法启动后台总结服务，任务未完成。请回到应用重新开始。", false);
            }
        };
        task.deadline = () -> finish(task, "本次总结已达到 2 小时运行上限，任务未完成。请缩小范围后重新开始。", false);
        MAIN.postDelayed(task.startupTimeout, 10_000);
        MAIN.postDelayed(task.deadline, MAX_TASK_DURATION_MS);
        try {
            Context context = ApplicationLoader.applicationContext;
            Intent intent = taskIntent(context, task, ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
            return true;
        } catch (RuntimeException error) {
            // Includes Android 12 background-start restrictions and permission/service-type errors.
            remove(task);
            return false;
        }
    }

    /** The notification maps this stage to fixed labels; it never echoes supplied text or model output. */
    public static void update(int account, long ownerId, String taskId, String safeStage, int completed, int total) {
        onMain(() -> {
            Task task = TASKS.get(taskId);
            if (!matches(task, account, ownerId, taskId)) return;
            if (!validOwner(account, ownerId)) { finish(task, null, true); return; }
            task.stage = stageLabel(safeStage);
            task.total = Math.max(0, total);
            task.completed = Math.max(0, Math.min(completed, task.total));
            if (task.host != null) task.host.refreshSafely();
        });
    }

    /** Normal completion, controller cancellation and error all release this exact task's lease. */
    public static void stop(int account, long ownerId, String taskId) {
        onMain(() -> {
            Task task = TASKS.get(taskId);
            if (matches(task, account, ownerId, taskId)) finish(task, null, false);
        });
    }

    /** Logout targets the old real owner, so account-slot reuse cannot stop a replacement account. */
    public static void stopOwner(int account, long ownerId) {
        onMain(() -> {
            for (Task task : new ArrayList<>(TASKS.values())) {
                if (task.account == account && task.ownerId == ownerId) finish(task, null, true);
            }
        });
    }

    private static boolean validOwner(int account, long ownerId) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && ownerId > 0
                && UserConfig.getInstance(account).isClientActivated()
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    private static boolean matches(Task task, int account, long ownerId, String taskId) {
        return task != null && task.account == account && task.ownerId == ownerId && task.taskId.equals(taskId);
    }

    private static void onMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else MAIN.post(runnable);
    }

    private static void remove(Task task) {
        if (TASKS.get(task.taskId) != task) return;
        TASKS.remove(task.taskId);
        MAIN.removeCallbacks(task.startupTimeout);
        MAIN.removeCallbacks(task.deadline);
    }

    private static void finish(Task task, String failure, boolean cancelled) {
        if (TASKS.get(task.taskId) != task) return;
        remove(task);
        SummaryForegroundService host = task.host;
        task.host = null;
        if (host != null) host.refreshSafely();
        notifyStopped(task, failure, cancelled);
    }

    private static void notifyStopped(Task task, String failure, boolean cancelled) {
        try {
            if (failure != null && task.failure != null) task.failure.onFailure(failure);
            else if (failure != null || cancelled) {
                task.cancel.run();
                if (failure != null) Toast.makeText(ApplicationLoader.applicationContext, failure, Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException error) {
            FileLog.e("AI summary foreground callback failed");
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        Task task = intent == null ? null : TASKS.get(intent.getStringExtra(EXTRA_TASK));
        if (intent == null || !matches(task, intent.getIntExtra(EXTRA_ACCOUNT, -1),
                intent.getLongExtra(EXTRA_OWNER, 0), intent.getStringExtra(EXTRA_TASK))) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            finish(task, null, true);
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || !validOwner(task.account, task.ownerId)) {
            finish(task, null, true);
            stopIfIdle();
            return START_NOT_STICKY;
        }
        task.host = this;
        try {
            createChannel();
            Notification notification = notification(task);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else startForeground(NOTIFICATION_ID, notification);
            foreground = true;
            lastNotificationAt = SystemClock.elapsedRealtime();
            acquireWakeLock();
            MAIN.removeCallbacks(task.startupTimeout);
            MAIN.removeCallbacks(task.deadline);
            MAIN.postDelayed(task.deadline, Math.max(0, task.startedAt + MAX_TASK_DURATION_MS - SystemClock.elapsedRealtime()));
            MAIN.removeCallbacks(tick);
            MAIN.postDelayed(tick, 1000);
        } catch (RuntimeException error) {
            failAttached("系统未允许后台总结继续运行，任务未完成。请回到应用重新开始。");
        }
        return START_NOT_STICKY;
    }

    private Task displayedTask() {
        Task selected = null;
        for (Task task : TASKS.values()) if (task.host == this) selected = task;
        return selected;
    }

    private void refreshSafely() {
        if (stopIfIdle()) return;
        try {
            acquireWakeLock();
            // Android 13 allows an FGS without notification permission; do not request it here.
            long now = SystemClock.elapsedRealtime();
            if (now - lastNotificationAt >= 1000 && (Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(displayedTask()));
                lastNotificationAt = now;
            }
        } catch (RuntimeException error) {
            failAttached("后台运行保护已停止，任务未完成。请回到应用重新开始。");
        }
    }

    private void acquireWakeLock() {
        long deadline = 0;
        for (Task task : TASKS.values()) if (task.host == this) deadline = Math.max(deadline, task.startedAt + MAX_TASK_DURATION_MS);
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new IllegalStateException("Summary deadline reached");
        if (wakeLock != null && wakeLock.isHeld() && wakeDeadline == deadline) return;
        releaseWakeLock();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":ai-summary");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(remaining);
        wakeDeadline = deadline;
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (RuntimeException ignored) { }
            wakeLock = null;
        }
        wakeDeadline = 0;
    }

    private boolean stopIfIdle() {
        if (displayedTask() != null) return false;
        releaseForeground();
        stopSelfResult(latestStartId);
        return true;
    }

    private void releaseForeground() {
        MAIN.removeCallbacks(tick);
        releaseWakeLock();
        foreground = false;
        try { stopForeground(true); } catch (RuntimeException ignored) { }
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID); }
        catch (RuntimeException ignored) { }
    }

    private void failAttached(String reason) {
        ArrayList<Task> failed = new ArrayList<>();
        for (Task task : new ArrayList<>(TASKS.values())) {
            if (task.host == this) { remove(task); task.host = null; failed.add(task); }
        }
        // Stop foreground work before controller callbacks, including Android 15's short timeout grace period.
        releaseForeground();
        stopSelfResult(latestStartId);
        for (Task task : failed) notifyStopped(task, reason, false);
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        failAttached("系统后台运行时限已到，总结未完成。请回到应用重新开始。");
        stopSelf();
    }

    @Override public void onDestroy() {
        failAttached("后台服务已停止，总结未完成。请回到 AI 总结中心重新开始。");
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "AI 总结进度", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示用户主动发起的总结进度，可返回总结中心或停止任务。");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification notification(Task task) {
        int count = 0;
        for (Task active : TASKS.values()) if (active.host == this) count++;
        long seconds = Math.max(0, (SystemClock.elapsedRealtime() - task.startedAt) / 1000);
        String text = task.stage + " · 已用 " + String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        if (task.total > 0) text += " · " + task.completed + "/" + task.total;
        Intent open = new Intent(this, LaunchActivity.class).setAction(ACTION_OPEN_CENTER)
                .putExtra(EXTRA_ACCOUNT, task.account).putExtra(EXTRA_OWNER, task.ownerId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = taskIntent(this, task, ACTION_CANCEL).setData(new Uri.Builder()
                .scheme("tg-summary").authority("cancel").appendPath(task.taskId).build());
        PendingIntent stop = PendingIntent.getService(this, NOTIFICATION_ID, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.notification)
                .setContentTitle(count > 1 ? "AI 总结 · " + count + " 项任务" : "AI 总结进行中")
                .setContentText(text).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .setSilent(true).setLocalOnly(true).setShowWhen(false).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(task.total, task.completed, task.total == 0)
                .addAction(0, "返回 AI 总结中心", content).addAction(0, "停止此任务", stop).build();
    }

    private static Intent taskIntent(Context context, Task task, String action) {
        return new Intent(context, SummaryForegroundService.class).setAction(action)
                .putExtra(EXTRA_ACCOUNT, task.account).putExtra(EXTRA_OWNER, task.ownerId).putExtra(EXTRA_TASK, task.taskId);
    }

    private static String stageLabel(String stage) {
        if (stage != null && stage.contains("读取")) return "正在读取消息";
        if (stage != null && stage.contains("保存")) return "正在保存总结";
        if (stage != null && stage.contains("合并")) return "正在合并总结";
        if (stage != null && (stage.contains("校验") || stage.contains("验证"))) return "正在核对结果";
        return "正在生成总结";
    }
}
