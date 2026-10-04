/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui.Components;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Android file transport only; source formatting stays in the independently tested formatter. */
final class SummaryExportFileHelper {
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final String DIRECTORY = "ai-chat-exports";
    private static final DispatchQueue QUEUE = new DispatchQueue("groupSummaryExport");
    // A remote DocumentsProvider may block despite cancellation. Do not hold up local file
    // generation or cleanup, and reject further saves once both bounded workers are occupied.
    private static final ThreadPoolExecutor SAVE_WORKERS = new ThreadPoolExecutor(0, 2, 60,
            TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> new Thread(runnable, "groupSummarySave"));

    interface Cancellation {
        boolean isCancelled();
    }

    static final class PreparedFile {
        final File file;
        final String mimeType;
        final long bytes;
        private boolean delivered;

        PreparedFile(File file, String mimeType) {
            this.file = file;
            this.mimeType = mimeType;
            bytes = file.length();
        }

        /** A chooser can pause/dismiss the parent synchronously. Transfer ownership first. */
        synchronized void share(Context context) throws IOException {
            if (!file.isFile()) throw new IOException("导出临时文件已被清理，请重新生成。");
            Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mimeType);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newUri(context.getContentResolver(), "群聊文字导出", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "分享待总结消息");
            chooser.setClipData(send.getClipData());
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            boolean previouslyDelivered = delivered;
            delivered = true;
            file.setLastModified(System.currentTimeMillis());
            try {
                context.startActivity(chooser);
            } catch (RuntimeException error) {
                delivered = previouslyDelivered;
                if (!delivered) QUEUE.postRunnable(() -> file.delete());
                throw error;
            }
            scheduleCleanup(context.getApplicationContext());
        }

        synchronized void release() {
            if (!delivered) QUEUE.postRunnable(() -> file.delete());
        }
    }

    private SummaryExportFileHelper() { }

    static void post(Runnable runnable) {
        QUEUE.postRunnable(runnable);
    }

    static boolean postSave(Runnable runnable) {
        try {
            SAVE_WORKERS.execute(runnable);
            return true;
        } catch (RejectedExecutionException busy) {
            return false;
        }
    }

    /** Worker thread only. A cancelled or failed write never leaves a readable partial file. */
    static PreparedFile prepare(Context context, byte[] data, String extension, String mimeType,
                                Cancellation cancellation) throws IOException {
        checkCancelled(cancellation);
        if (data == null || data.length == 0 || data.length > MAX_FILE_BYTES
                || !("md".equals(extension) || "json".equals(extension))) {
            throw new IOException("导出内容为空或超过 8 MiB，请缩小范围后重试。");
        }
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建私有导出缓存目录。");
        cleanup(directory);
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File file = new File(directory, "telegram-chat-" + timestamp + "-" + UUID.randomUUID() + "." + extension);
        boolean success = false;
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                for (int offset = 0; offset < data.length; offset += 16384) {
                    checkCancelled(cancellation);
                    output.write(data, offset, Math.min(16384, data.length - offset));
                }
                output.flush();
                checkCancelled(cancellation);
            }
            checkCancelled(cancellation);
            success = true;
        } finally {
            if (!success) file.delete();
        }
        scheduleCleanup(context.getApplicationContext());
        return new PreparedFile(file, mimeType);
    }

    /** Worker thread only; URI is granted by a single ACTION_CREATE_DOCUMENT result. */
    static void save(Context context, PreparedFile prepared, Uri destination,
                     Cancellation cancellation) throws IOException {
        checkCancelled(cancellation);
        if (destination == null || !"content".equals(destination.getScheme())) {
            throw new IOException("文件选择器未返回可写的文件地址。");
        }
        try (FileInputStream input = new FileInputStream(prepared.file)) {
            checkCancelled(cancellation);
            try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("无法打开所选文件。");
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(cancellation);
                    output.write(buffer, 0, count);
                }
                output.flush();
                checkCancelled(cancellation);
            }
        }
        checkCancelled(cancellation);
    }

    private static void checkCancelled(Cancellation cancellation) throws InterruptedIOException {
        if (cancellation.isCancelled()) throw new InterruptedIOException("已取消导出。");
    }

    private static void scheduleCleanup(Context context) {
        QUEUE.postRunnable(() -> cleanup(new File(context.getCacheDir(), DIRECTORY)), MAX_AGE_MS + 1000);
    }

    private static void cleanup(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        long threshold = System.currentTimeMillis() - MAX_AGE_MS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() <= threshold) file.delete();
        }
    }
}
