/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.groupmessages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Bounded, authenticated local copies. All calls perform disk I/O and belong on a worker queue.
 * Main Telegram storage, unread counters and server state are never touched by this store.
 */
public final class DeletedMessageStore {
    public static final int MAX_RECORDS = 2000;
    public static final int MAX_STORAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAGIC = 0x54445231; // TDR1
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int ENVELOPE_BYTES = 8 + IV_BYTES + TAG_BYTES;
    private static final int MAX_PLAINTEXT_BYTES = MAX_STORAGE_BYTES - ENVELOPE_BYTES;
    private static final Object DISK_LOCK = new Object();
    private static final Comparator<DeletedMessageRecord> NEWEST_FIRST = (left, right) -> {
        int order = Long.compare(right.deletedAt, left.deletedAt);
        if (order == 0) order = Integer.compare(right.sentAt, left.sentAt);
        if (order == 0) order = Long.compare(left.dialogId, right.dialogId);
        return order == 0 ? Integer.compare(right.messageId, left.messageId) : order;
    };

    public interface KeyProvider {
        /** create=false must not generate a replacement key for existing ciphertext. */
        SecretKey getKey(long ownerId, boolean create) throws Exception;
        void deleteKey(long ownerId) throws Exception;
    }

    private final File root;
    private final KeyProvider keys;

    public DeletedMessageStore(File root, KeyProvider keys) {
        if (root == null || keys == null) throw new IllegalArgumentException("缺少本地记录存储配置。");
        this.root = root;
        this.keys = keys;
    }

    /** dialogId=0 lists all of this owner's groups; results are immutable and newest first. */
    public List<DeletedMessageRecord> read(long ownerId, long dialogId) {
        requireOwner(ownerId);
        requireScope(dialogId, true);
        synchronized (DISK_LOCK) {
            ArrayList<DeletedMessageRecord> records = readAll(ownerId);
            if (dialogId != 0) records.removeIf(record -> record.dialogId != dialogId);
            return Collections.unmodifiableList(records);
        }
    }

    /** Repeated deletion events preserve the first captured copy and its observed deletion time. */
    public void putAll(long ownerId, List<DeletedMessageRecord> additions) {
        requireOwner(ownerId);
        if (additions == null) throw new IllegalArgumentException("没有可保存的本地记录。");
        ArrayList<DeletedMessageRecord> snapshot = new ArrayList<>(additions);
        for (DeletedMessageRecord record : snapshot) {
            if (record == null || record.ownerId != ownerId) {
                throw new IllegalArgumentException("本地记录不属于当前账号。");
            }
        }
        if (snapshot.isEmpty()) return;
        synchronized (DISK_LOCK) {
            LinkedHashMap<String, DeletedMessageRecord> unique = new LinkedHashMap<>();
            for (DeletedMessageRecord record : readAll(ownerId)) unique.put(id(record), record);
            boolean changed = false;
            for (DeletedMessageRecord record : snapshot) {
                if (!unique.containsKey(id(record))) {
                    unique.put(id(record), record);
                    changed = true;
                }
            }
            if (!changed) return;
            ArrayList<DeletedMessageRecord> records = new ArrayList<>(unique.values());
            records.sort(NEWEST_FIRST);
            int bytes = 8; // plaintext version + count
            int keep = 0;
            for (DeletedMessageRecord record : records) {
                int size = encodedSize(record);
                if (keep == MAX_RECORDS || bytes + size > MAX_PLAINTEXT_BYTES) break;
                bytes += size;
                keep++;
            }
            records.subList(keep, records.size()).clear();
            writeAll(ownerId, records);
        }
    }

    /** Requires the actual group ID: ordinary-group message IDs alone are not a group identity. */
    public void removeIds(long ownerId, long dialogId, List<Integer> ids) {
        requireOwner(ownerId);
        requireScope(dialogId, false);
        if (ids == null) throw new IllegalArgumentException("缺少要清除的消息编号。");
        Set<Integer> selected = new HashSet<>();
        for (Integer id : ids) {
            if (id == null || id <= 0) throw new IllegalArgumentException("消息编号无效。");
            selected.add(id);
        }
        if (selected.isEmpty()) return;
        synchronized (DISK_LOCK) {
            ArrayList<DeletedMessageRecord> records = readAll(ownerId);
            if (records.removeIf(record -> record.dialogId == dialogId && selected.contains(record.messageId))) {
                writeAll(ownerId, records);
            }
        }
    }

    public void removeIds(long ownerId, long dialogId, int[] ids) {
        if (ids == null) throw new IllegalArgumentException("缺少要清除的消息编号。");
        ArrayList<Integer> boxed = new ArrayList<>(ids.length);
        for (int id : ids) boxed.add(id);
        removeIds(ownerId, dialogId, boxed);
    }

    public void clearDialog(long ownerId, long dialogId) {
        requireOwner(ownerId);
        requireScope(dialogId, false);
        synchronized (DISK_LOCK) {
            ArrayList<DeletedMessageRecord> records = readAll(ownerId);
            if (records.removeIf(record -> record.dialogId == dialogId)) writeAll(ownerId, records);
        }
    }

    /** Removes exactly the identified sender; zero would match unrelated anonymous senders. */
    public void removeSender(long ownerId, long dialogId, long senderPeerId) {
        requireOwner(ownerId);
        requireScope(dialogId, false);
        if (senderPeerId == 0 || senderPeerId == Long.MIN_VALUE) throw new IllegalArgumentException("发送人身份无效。");
        synchronized (DISK_LOCK) {
            ArrayList<DeletedMessageRecord> records = readAll(ownerId);
            if (records.removeIf(record -> record.dialogId == dialogId && record.senderPeerId == senderPeerId)) {
                writeAll(ownerId, records);
            }
        }
    }

    public void removeTopic(long ownerId, long dialogId, long topicId) {
        requireOwner(ownerId);
        requireScope(dialogId, false);
        if (topicId <= 0) throw new IllegalArgumentException("话题编号无效。");
        synchronized (DISK_LOCK) {
            ArrayList<DeletedMessageRecord> records = readAll(ownerId);
            if (records.removeIf(record -> record.dialogId == dialogId && record.topicId == topicId)) {
                writeAll(ownerId, records);
            }
        }
    }

    /** Inclusive original-message date range, in Unix seconds. Zero min is allowed. */
    public void removeDateRange(long ownerId, long dialogId, int min, int max) {
        requireOwner(ownerId);
        requireScope(dialogId, false);
        if (min < 0 || max < min) throw new IllegalArgumentException("要清除的日期范围无效。");
        synchronized (DISK_LOCK) {
            ArrayList<DeletedMessageRecord> records = readAll(ownerId);
            if (records.removeIf(record -> record.dialogId == dialogId
                    && record.sentAt >= min && record.sentAt <= max)) writeAll(ownerId, records);
        }
    }

    /** Remains usable when ciphertext is damaged or its key is lost. Does not read or decrypt. */
    public void clearOwner(long ownerId) {
        requireOwner(ownerId);
        synchronized (DISK_LOCK) {
            Exception failure = null;
            try {
                deleteFile(file(ownerId));
                File[] files = root.listFiles();
                if (files != null) {
                    String prefix = filename(ownerId) + ".";
                    for (File file : files) {
                        if (file.getName().startsWith(prefix) && file.getName().endsWith(".tmp")) deleteFile(file);
                    }
                }
            } catch (Exception error) {
                failure = error;
            }
            try {
                // Revoking the key also protects a leftover file if deleting it failed.
                keys.deleteKey(ownerId);
            } catch (Exception error) {
                if (failure == null) failure = error;
            }
            if (failure != null) throw storageFailure("无法完整清除本地撤回记录。", failure);
        }
    }

    private ArrayList<DeletedMessageRecord> readAll(long ownerId) {
        File source = file(ownerId);
        if (!source.exists()) return new ArrayList<>();
        try {
            long length = source.length();
            if (length < ENVELOPE_BYTES + 8 || length > MAX_STORAGE_BYTES) throw new IOException("Invalid file size");
            byte[] encoded = new byte[(int) length];
            try (DataInputStream input = new DataInputStream(new FileInputStream(source))) {
                input.readFully(encoded);
                if (input.read() != -1) throw new IOException("File grew while reading");
            }
            DataInputStream envelope = new DataInputStream(new ByteArrayInputStream(encoded));
            if (envelope.readInt() != MAGIC || envelope.readInt() != VERSION) throw new IOException("Unknown format");
            byte[] iv = new byte[IV_BYTES];
            envelope.readFully(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKey key = keys.getKey(ownerId, false);
            if (key == null) throw new IOException("Missing encryption key");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(aad(ownerId));
            byte[] plain = cipher.doFinal(encoded, 8 + IV_BYTES, encoded.length - 8 - IV_BYTES);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(plain));
            if (input.readInt() != VERSION) throw new IOException("Unknown record version");
            int count = input.readInt();
            if (count < 0 || count > MAX_RECORDS) throw new IOException("Invalid record count");
            ArrayList<DeletedMessageRecord> records = new ArrayList<>(count);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < count; i++) {
                DeletedMessageRecord record = new DeletedMessageRecord(input.readLong(), input.readLong(),
                        input.readLong(), input.readInt(), input.readInt(), input.readLong(),
                        readString(input, DeletedMessageRecord.MAX_SENDER_NAME_CHARS),
                        readString(input, DeletedMessageRecord.MAX_TEXT_CHARS), input.readLong(), input.readLong());
                if (record.ownerId != ownerId || !ids.add(id(record))) throw new IOException("Invalid record identity");
                records.add(record);
            }
            if (input.read() != -1) throw new IOException("Trailing data");
            records.sort(NEWEST_FIRST);
            return records;
        } catch (Exception error) {
            throw storageFailure("无法读取本地撤回记录，原文件未修改。可清空记录后重试。", error);
        }
    }

    private void writeAll(long ownerId, List<DeletedMessageRecord> records) {
        try {
            if (records.isEmpty()) {
                deleteFile(file(ownerId));
                return;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(VERSION);
            output.writeInt(records.size());
            for (DeletedMessageRecord record : records) {
                output.writeLong(record.ownerId);
                output.writeLong(record.dialogId);
                output.writeLong(record.topicId);
                output.writeInt(record.messageId);
                output.writeInt(record.sentAt);
                output.writeLong(record.senderUserId);
                writeString(output, record.senderName);
                writeString(output, record.text);
                output.writeLong(record.deletedAt);
                output.writeLong(record.senderPeerId);
            }
            byte[] plain = bytes.toByteArray();
            if (plain.length > MAX_PLAINTEXT_BYTES || records.size() > MAX_RECORDS) throw new IOException("Too much data");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKey key = keys.getKey(ownerId, true);
            if (key == null) throw new IOException("Missing encryption key");
            // Let the provider generate the nonce: Android Keystore requires randomized encryption.
            cipher.init(Cipher.ENCRYPT_MODE, key);
            cipher.updateAAD(aad(ownerId));
            byte[] encrypted = cipher.doFinal(plain);
            if (cipher.getIV().length != IV_BYTES || 8 + IV_BYTES + encrypted.length > MAX_STORAGE_BYTES) {
                throw new IOException("Invalid encrypted size");
            }
            atomicWrite(ownerId, cipher.getIV(), encrypted);
        } catch (Exception error) {
            throw storageFailure("无法保存本地撤回记录，未替换原文件。", error);
        }
    }

    private void atomicWrite(long ownerId, byte[] iv, byte[] encrypted) throws IOException {
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) throw new IOException("Cannot create directory");
        File temporary = File.createTempFile(filename(ownerId) + ".", ".tmp", root);
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary);
                    DataOutputStream output = new DataOutputStream(stream)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.write(iv);
                output.write(encrypted);
                output.flush();
                stream.getFD().sync();
            }
            // Same-directory rename is atomic on Android/Linux; never delete the old file first.
            if (!temporary.renameTo(file(ownerId))) throw new IOException("Cannot atomically replace file");
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    private static int encodedSize(DeletedMessageRecord record) {
        return 64 + record.senderName.getBytes(StandardCharsets.UTF_8).length
                + record.text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(utf8.length);
        output.write(utf8);
    }

    private static String readString(DataInputStream input, int maxChars) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maxChars * 3 || length > input.available()) throw new IOException("Invalid string size");
        byte[] utf8 = new byte[length];
        input.readFully(utf8);
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString();
        if (value.length() > maxChars) throw new IOException("String too long");
        return value;
    }

    private File file(long ownerId) { return new File(root, filename(ownerId)); }
    private static String filename(long ownerId) { return "deleted-messages-" + ownerId + ".bin"; }
    private static String id(DeletedMessageRecord record) { return record.dialogId + ":" + record.messageId; }
    private static byte[] aad(long ownerId) {
        return ("telegram.local_deleted_messages.v1." + ownerId).getBytes(StandardCharsets.UTF_8);
    }
    private static void requireOwner(long ownerId) {
        if (ownerId <= 0) throw new IllegalArgumentException("账号身份无效。");
    }
    private static void requireScope(long dialogId, boolean allowAll) {
        if (dialogId > 0 || dialogId == Long.MIN_VALUE || !allowAll && dialogId == 0) {
            throw new IllegalArgumentException("群聊范围无效。");
        }
    }
    private static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove local record file");
    }
    private static IllegalStateException storageFailure(String message, Exception error) {
        return new IllegalStateException(message, error);
    }
}
