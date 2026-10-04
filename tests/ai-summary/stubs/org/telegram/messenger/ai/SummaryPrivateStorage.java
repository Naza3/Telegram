package org.telegram.messenger.ai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** JVM encryption/disk boundary. Android AtomicFile and Keystore require separate integration. */
final class SummaryPrivateStorage {
    static final Map<String, String> FILES = new HashMap<>();
    static final Map<String, SecretKey> KEYS = new HashMap<>();
    static int failReads, failWrites, failDeletes;
    static Runnable beforeWrite;
    static String key(String namespace, int account, long owner) { return namespace + ":" + account + ":" + owner; }
    static synchronized String read(String namespace, int account, long owner, int maxBytes) {
        if (failReads-- > 0) throw new IllegalStateException("读取失败");
        String id = key(namespace, account, owner), value = FILES.get(id);
        if (value == null) return null;
        try {
            if (value.length() > maxBytes) throw new IllegalStateException();
            String[] fields = value.split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, KEYS.get(id), new GCMParameterSpec(128, Base64.getDecoder().decode(fields[1])));
            cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(fields[2])), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("解密失败", error); }
    }
    static synchronized void write(String namespace, int account, long owner, String plaintext, int maxBytes) {
        if (beforeWrite != null) beforeWrite.run();
        if (failWrites-- > 0) throw new IllegalStateException("写入失败");
        try {
            String id = key(namespace, account, owner);
            SecretKey secret = KEYS.get(id);
            if (secret == null) { KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); secret = generator.generateKey(); KEYS.put(id, secret); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret); cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            String value = "v1:" + Base64.getEncoder().encodeToString(cipher.getIV()) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
            if (value.length() > maxBytes) throw new IllegalStateException();
            FILES.put(id, value);
        } catch (Exception error) { throw new IllegalStateException("加密失败", error); }
    }
    static synchronized void delete(String namespace, int account, long owner) {
        String id = key(namespace, account, owner);
        KEYS.remove(id);
        if (failDeletes-- > 0) throw new IllegalStateException("删除失败");
        FILES.remove(id);
    }
    static synchronized void reset() { FILES.clear(); KEYS.clear(); failReads = failWrites = failDeletes = 0; beforeWrite = null; }
}
