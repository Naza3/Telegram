package org.telegram.messenger.ai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Real JVM AES-GCM with memory keys; deliberately does not claim Android Keystore coverage. */
final class SummaryHistoryCipher {
    static final Map<String, SecretKey> KEYS = new HashMap<>();
    static int failEncrypts, failDecrypts, failDeletes;
    static Runnable afterEncrypt;
    private static String key(int account, long owner) { return "telegram.ai_summary_history.v1." + account + "." + owner; }
    static String encrypt(int account, long owner, String plaintext) {
        if (failEncrypts-- > 0) throw new IllegalStateException("无法加密");
        try {
            String alias = key(account, owner);
            SecretKey secret = KEYS.get(alias);
            if (secret == null) { KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(128);
                secret = generator.generateKey(); KEYS.put(alias, secret); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret);
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
            String result = "v1:" + Base64.getEncoder().encodeToString(cipher.getIV()) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
            if (afterEncrypt != null) afterEncrypt.run();
            return result;
        } catch (Exception error) { throw new IllegalStateException("无法加密", error); }
    }
    static String decrypt(int account, long owner, String encrypted) {
        if (failDecrypts-- > 0) throw new IllegalStateException("无法解密");
        try {
            String alias = key(account, owner); SecretKey secret = KEYS.get(alias);
            String[] parts = encrypted.split(":", -1);
            if (secret == null || parts.length != 3 || !parts[0].equals("v1")) throw new IllegalStateException();
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("无法解密", error); }
    }
    static void deleteKey(int account, long owner) {
        if (failDeletes-- > 0) throw new IllegalStateException("无法清理密钥");
        KEYS.remove(key(account, owner));
    }
    static void reset() { KEYS.clear(); failEncrypts = failDecrypts = failDeletes = 0; afterEncrypt = null; }
}
