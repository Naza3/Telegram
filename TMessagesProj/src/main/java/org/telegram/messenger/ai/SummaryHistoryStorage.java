/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import android.content.Context;
import android.util.AtomicFile;

import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Platform disk boundary: encrypted bytes only, excluded from Android application backups. */
final class SummaryHistoryStorage {
    private SummaryHistoryStorage() { }

    static String read(int account, long ownerId, int maxBytes) {
        File base = location(account, ownerId);
        if (!base.exists() && !new File(base.getPath() + ".bak").exists()
                && !new File(base.getPath() + ".new").exists()) return null;
        try (FileInputStream input = new AtomicFile(base).openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > maxBytes - count) {
                    throw new IOException("Encrypted history is oversized");
                }
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取总结历史，原记录未修改。", error);
        }
    }

    static void write(int account, long ownerId, String encrypted) {
        File base = location(account, ownerId);
        File directory = base.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建总结历史目录，未保存记录。");
        }
        AtomicFile file = new AtomicFile(base);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(encrypted.getBytes(StandardCharsets.UTF_8));
            // AtomicFile.finishWrite logs some fsync failures instead of throwing them.
            // Confirm the data write before the old backup is discarded.
            stream.getFD().sync();
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            if (stream != null) file.failWrite(stream);
            throw new IllegalStateException("无法保存总结历史，原记录已保留。", error);
        }
        // On some Android versions finishWrite also logs a failed rename without throwing.
        // Do not tell the caller that an unconfirmed publication succeeded; retry uses the same ID.
        if (!encrypted.equals(read(account, ownerId, SummaryHistoryStore.MAX_STORAGE_BYTES))) {
            throw new IllegalStateException("无法确认总结历史已保存，请重试保存。");
        }
    }

    static void delete(int account, long ownerId) {
        File base = location(account, ownerId);
        new AtomicFile(base).delete();
        if (base.exists() || new File(base.getPath() + ".bak").exists()
                || new File(base.getPath() + ".new").exists()) {
            throw new IllegalStateException("无法删除总结历史，请稍后重试。");
        }
    }

    private static File location(int account, long ownerId) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) throw new IllegalStateException("应用存储尚未就绪，请稍后重试。");
        File noBackup = context.getNoBackupFilesDir();
        if (noBackup == null) throw new IllegalStateException("应用存储尚未就绪，请稍后重试。");
        File directory = new File(noBackup, "ai_summary_history");
        return new File(directory, "account_" + account + "_owner_" + ownerId + ".encrypted");
    }
}
