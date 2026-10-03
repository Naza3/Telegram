/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import android.annotation.TargetApi;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Per-owner Android Keystore keys. Failed encryption never falls back to plaintext storage. */
final class SummaryHistoryCipher {
    private static final String PREFIX = "telegram.ai_summary_history.v1.";
    private SummaryHistoryCipher() { }

    static String encrypt(int account, long ownerId, String plaintext) {
        requireAvailable();
        try { return Api23.encrypt(account, ownerId, plaintext); }
        catch (Exception error) {
            throw new IllegalStateException("无法加密总结历史，未保存记录。请检查设备安全存储后重试。", error);
        }
    }

    static String decrypt(int account, long ownerId, String encrypted) {
        requireAvailable();
        try { return Api23.decrypt(account, ownerId, encrypted); }
        catch (Exception error) {
            throw new IllegalStateException("无法解密总结历史，未修改原记录。设备密钥可能已失效，可清空历史后重试。", error);
        }
    }

    static void deleteKey(int account, long ownerId) {
        if (Build.VERSION.SDK_INT < 23) return;
        try { Api23.keyStore().deleteEntry(alias(account, ownerId)); }
        catch (Exception error) { throw new IllegalStateException("无法清除总结历史密钥。", error); }
    }

    private static void requireAvailable() {
        if (Build.VERSION.SDK_INT < 23) {
            throw new IllegalStateException("当前设备不支持安全保存总结历史（需要 Android 6.0 或以上）。");
        }
    }

    private static String alias(int account, long ownerId) { return PREFIX + account + "." + ownerId; }
    private static byte[] aad(int account, long ownerId) { return alias(account, ownerId).getBytes(StandardCharsets.UTF_8); }

    @TargetApi(23)
    private static final class Api23 {
        static KeyStore keyStore() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
        }

        private static SecretKey key(int account, long ownerId, boolean create) throws Exception {
            String alias = alias(account, ownerId);
            SecretKey key = (SecretKey) keyStore().getKey(alias, null);
            if (key == null && create) {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(alias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true).build());
                key = generator.generateKey();
            }
            if (key == null) throw new IllegalStateException("Missing summary history key");
            return key;
        }

        static String encrypt(int account, long ownerId, String plaintext) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(account, ownerId, true));
            cipher.updateAAD(aad(account, ownerId));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        }

        static String decrypt(int account, long ownerId, String encrypted) throws Exception {
            String[] parts = encrypted.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("Unknown history encryption format");
            byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
            if (iv.length != 12) throw new IllegalArgumentException("Invalid history nonce");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(account, ownerId, false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(account, ownerId));
            return new String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        }
    }
}
