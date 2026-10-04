/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android boundary for independent, owner-bound encrypted summary side stores. */
final class SummaryPrivateStorage {
    private SummaryPrivateStorage() { }

    static synchronized String read(String namespace, int account, long owner, int maxBytes) {
        requireOwner(account, owner);
        String alias = alias(namespace, account, owner);
        try {
            String encrypted = readFile(location(namespace, account, owner), maxBytes);
            if (encrypted == null) return null;
            available();
            String[] fields = encrypted.split(":", -1);
            if (fields.length != 3 || !"v1".equals(fields[0])) throw new IllegalArgumentException();
            byte[] iv = Base64.decode(fields[1], Base64.NO_WRAP);
            if (iv.length != 12) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, Api23.key(alias, false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(Base64.decode(fields[2], Base64.NO_WRAP));
            if (plaintext.length > maxBytes) throw new IllegalArgumentException();
            requireOwner(account, owner);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("无法读取加密的总结附属记录，原记录未修改。", error);
        }
    }

    static synchronized void write(String namespace, int account, long owner, String plaintext, int maxBytes) {
        requireOwner(account, owner);
        String alias = alias(namespace, account, owner);
        if (plaintext == null || plaintext.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalStateException("总结附属记录超过保存容量。");
        }
        AtomicFile file = new AtomicFile(location(namespace, account, owner));
        FileOutputStream output = null;
        try {
            available();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, Api23.key(alias, true));
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
            String encrypted = "v1:" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
            byte[] bytes = encrypted.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxBytes) throw new IllegalStateException("总结附属记录超过加密保存容量。");
            requireOwner(account, owner);
            File directory = file.getBaseFile().getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException();
            output = file.startWrite();
            output.write(bytes);
            output.getFD().sync();
            file.finishWrite(output);
            output = null;
            if (!encrypted.equals(readFile(file.getBaseFile(), maxBytes))) throw new IllegalStateException();
            requireOwner(account, owner);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("无法保存加密的总结附属记录，请重试。", error);
        }
    }

    /** Logout may clear an old owner after the account slot has already changed. */
    static synchronized void delete(String namespace, int account, long owner) {
        String alias = alias(namespace, account, owner);
        File base = location(namespace, account, owner);
        new AtomicFile(base).delete();
        boolean remaining = base.exists() || new File(base + ".bak").exists() || new File(base + ".new").exists();
        try {
            if (Build.VERSION.SDK_INT >= 23) Api23.store().deleteEntry(alias);
        } catch (Exception error) {
            throw new IllegalStateException("无法清除总结附属记录的密钥。", error);
        }
        if (remaining) throw new IllegalStateException("无法删除总结附属记录，已撤销原加密密钥。");
    }

    private static String readFile(File base, int maxBytes) throws Exception {
        if (!base.exists() && !new File(base + ".bak").exists() && !new File(base + ".new").exists()) return null;
        try (FileInputStream input = new AtomicFile(base).openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() > maxBytes - count) throw new IllegalStateException();
                output.write(bytes, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static File location(String namespace, int account, long owner) {
        alias(namespace, account, owner);
        Context context = ApplicationLoader.applicationContext;
        if (context == null || context.getNoBackupFilesDir() == null) throw new IllegalStateException("应用存储尚未就绪。");
        return new File(new File(context.getNoBackupFilesDir(), "ai_summary_private"), namespace + "_" + account + "_" + owner + ".encrypted");
    }

    private static String alias(String namespace, int account, long owner) {
        if (namespace == null || !namespace.matches("[a-z_]{1,40}") || account < 0
                || account >= UserConfig.MAX_ACCOUNT_COUNT || owner <= 0) throw new IllegalArgumentException("总结附属记录的身份无效。");
        return "telegram.ai_summary_private.v1." + namespace + "." + account + "." + owner;
    }

    private static void requireOwner(int account, long owner) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || owner <= 0
                || UserConfig.getInstance(account).getClientUserId() != owner) throw new IllegalStateException("账号身份已变化。");
    }

    private static void available() {
        if (Build.VERSION.SDK_INT < 23) throw new IllegalStateException("安全保存需要 Android 6.0 或以上。");
    }

    @TargetApi(23)
    private static final class Api23 {
        static KeyStore store() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
        }
        static SecretKey key(String alias, boolean create) throws Exception {
            SecretKey key = (SecretKey) store().getKey(alias, null);
            if (key == null && create) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build());
                key = generator.generateKey();
            }
            if (key == null) throw new IllegalStateException("Missing private summary key");
            return key;
        }
    }
}
