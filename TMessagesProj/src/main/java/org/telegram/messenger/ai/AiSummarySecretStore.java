/*
 * This is part of the source code of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger.ai;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.HashSet;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Account-scoped API secrets; plaintext is never written to preferences. */
public final class AiSummarySecretStore {
    private static final String PREFERENCES = "local_ai_summary_secrets";
    private static final String ALIAS_PREFIX = "telegram.local_ai_summary.v1.account.";
    private static final HashMap<Integer, String> MEMORY = new HashMap<>();
    private static final HashMap<Integer, Long> MEMORY_OWNERS = new HashMap<>();
    // Includes cleared values so an unsuccessful disk write cannot revive a key this session.
    private static final HashSet<Integer> SESSION_OVERRIDES = new HashSet<>();
    private static boolean persistenceAvailable = Build.VERSION.SDK_INT >= 23;

    private AiSummarySecretStore() {
    }

    public static synchronized String load(int account) {
        return load(account, currentOwner(account));
    }

    /** Allows callers to carry one identity snapshot across settings and secret reads. */
    public static synchronized String load(int account, long owner) {
        if (owner != currentOwner(account)) {
            return "";
        }
        Long memoryOwner = MEMORY_OWNERS.get(account);
        if (owner == 0 || (memoryOwner != null && memoryOwner != owner)) {
            clearAccount(account);
            return "";
        }
        if (SESSION_OVERRIDES.contains(account)) {
            String value = MEMORY.get(account);
            if (!stillOwnedBy(account, owner)) {
                return "";
            }
            return value == null ? "" : value;
        }
        if (Build.VERSION.SDK_INT < 23) {
            return "";
        }
        try {
            SharedPreferences preferences = preferences();
            if (preferences == null) {
                return "";
            }
            String encoded = preferences.getString(entry(account), null);
            if (encoded == null) {
                return "";
            }
            // Owner is part of the same committed value as the ciphertext, and authenticated
            // as GCM AAD. Slot reuse and old unbound formats must not inherit an API key.
            if (!encoded.startsWith("v2:" + owner + ":")) {
                clearAccount(account);
                return "";
            }
            String value = Api23.decrypt(account, owner, encoded);
            if (!stillOwnedBy(account, owner)) {
                return "";
            }
            MEMORY.put(account, value);
            MEMORY_OWNERS.put(account, owner);
            SESSION_OVERRIDES.add(account);
            persistenceAvailable = true;
            return value;
        } catch (Exception ignored) {
            // Restored backups have no matching Keystore key; do not expose data or crash.
            persistenceAvailable = false;
            if (!stillOwnedBy(account, owner)) {
                return "";
            }
            String value = MEMORY.get(account);
            return value == null ? "" : value;
        }
    }

    /**
     * Returns true only if this operation was committed to encrypted storage (or cleared).
     * On failure, the supplied value remains available only for this process lifetime.
     * Performs Keystore and preference I/O; callers should use a worker thread.
     */
    public static synchronized boolean save(int account, String value) {
        return save(account, currentOwner(account), value);
    }

    /** A stale caller must never write its secret into a newly logged-in account's slot. */
    public static synchronized boolean save(int account, long owner, String value) {
        if (owner != currentOwner(account)) {
            return false;
        }
        if (owner == 0) {
            clearAccount(account);
            return false;
        }
        String secret = value == null ? "" : value;
        MEMORY_OWNERS.put(account, owner);
        SESSION_OVERRIDES.add(account);
        if (secret.isEmpty()) {
            MEMORY.remove(account);
        } else {
            MEMORY.put(account, secret);
        }

        SharedPreferences preferences = null;
        try {
            preferences = preferences();
            // Remove the previous value before attempting encryption, including fallback paths.
            boolean cleared = preferences != null && preferences.edit().remove(entry(account)).commit();
            if (Build.VERSION.SDK_INT < 23) {
                persistenceAvailable = false;
                stillOwnedBy(account, owner);
                return false;
            }
            // Rotate the per-account key on every save. Old ciphertext is unusable even if a
            // failed preferences commit leaves the previous disk contents behind.
            Api23.deleteKey(account);
            if (!cleared) {
                persistenceAvailable = false;
                stillOwnedBy(account, owner);
                return false;
            }
            if (!secret.isEmpty()) {
                String encoded = Api23.encrypt(account, owner, secret);
                if (!stillOwnedBy(account, owner)) {
                    return false;
                }
                if (!preferences.edit().putString(entry(account), encoded).commit()) {
                    throw new IllegalStateException("Unable to persist encrypted API key");
                }
            }
            if (!stillOwnedBy(account, owner)) {
                return false;
            }
            persistenceAvailable = true;
            return true;
        } catch (Exception ignored) {
            persistenceAvailable = false;
            // Never fall back to a previous persisted secret after the user changed/cleared it.
            if (preferences != null) {
                try {
                    preferences.edit().remove(entry(account)).commit();
                } catch (Exception ignoredCleanup) {
                }
            }
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    Api23.deleteKey(account);
                } catch (Exception ignoredCleanup) {
                }
            }
            stillOwnedBy(account, owner);
            return false;
        }
    }

    /** Strict read for one-time profile migration; failure leaves the old ciphertext untouched. */
    static synchronized String loadForMigration(int account, long owner) {
        if (owner <= 0 || owner != currentOwner(account)) {
            throw new IllegalStateException("当前账号已变化，未迁移 API 配置。");
        }
        if (Long.valueOf(owner).equals(MEMORY_OWNERS.get(account)) && SESSION_OVERRIDES.contains(account)) {
            return MEMORY.getOrDefault(account, "");
        }
        try {
            SharedPreferences preferences = preferences();
            if (preferences == null) throw new IllegalStateException();
            String encoded = preferences.getString(entry(account), null);
            if (encoded == null) return "";
            // A positively identified previous slot owner must not be inherited. Unknown/damaged
            // formats are different: refuse migration rather than committing an empty replacement.
            if (!encoded.startsWith("v2:" + owner + ":")) {
                String[] header = encoded.split(":", 3);
                if (header.length == 3 && "v2".equals(header[0])) {
                    long encodedOwner = Long.parseLong(header[1]);
                    if (encodedOwner > 0 && encodedOwner != owner) return "";
                }
                throw new IllegalStateException();
            }
            if (Build.VERSION.SDK_INT < 23) throw new IllegalStateException();
            String result = Api23.decrypt(account, owner, encoded);
            if (owner != currentOwner(account)) throw new IllegalStateException();
            return result;
        } catch (Exception ignored) {
            throw new IllegalStateException("原 API Key 暂时无法解密，未覆盖旧配置。请恢复设备密钥后重试。");
        }
    }

    /** Revokes only this owner; safe when a logout callback arrives after slot reuse. */
    public static synchronized boolean clearOwner(int account, long owner) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || owner <= 0) return true;
        if (Long.valueOf(owner).equals(MEMORY_OWNERS.get(account))) {
            MEMORY.remove(account); MEMORY_OWNERS.remove(account); SESSION_OVERRIDES.remove(account);
        }
        try {
            SharedPreferences preferences = preferences();
            if (preferences == null) return false;
            String encoded = preferences.getString(entry(account), null);
            if (encoded != null && !encoded.startsWith("v2:" + owner + ":")) return true;
            if (encoded != null) {
                if (!preferences.edit().remove(entry(account)).commit()) return false;
                if (Build.VERSION.SDK_INT >= 23) Api23.deleteKey(account);
            } else if (owner == currentOwner(account) && Build.VERSION.SDK_INT >= 23) {
                Api23.deleteKey(account);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Capability hint only: use save()'s result to confirm a particular write succeeded. */
    public static synchronized boolean isPersistent() {
        return Build.VERSION.SDK_INT >= 23
                && ApplicationLoader.applicationContext != null && persistenceAvailable;
    }

    private static SharedPreferences preferences() {
        Context context = ApplicationLoader.applicationContext;
        return context == null ? null : context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String entry(int account) {
        return "account_" + account;
    }

    private static long currentOwner(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static boolean stillOwnedBy(int account, long owner) {
        if (owner != 0 && currentOwner(account) == owner) {
            return true;
        }
        clearAccount(account);
        return false;
    }

    private static void clearAccount(int account) {
        MEMORY.remove(account);
        MEMORY_OWNERS.remove(account);
        SESSION_OVERRIDES.remove(account);
        try {
            SharedPreferences preferences = preferences();
            if (preferences != null) {
                preferences.edit().remove(entry(account)).commit();
            }
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Api23.deleteKey(account);
            } catch (Exception ignored) {
            }
        }
    }

    // Isolate API 23 types from the API 21/22 memory-only path.
    @TargetApi(23)
    private static final class Api23 {
        private static KeyStore keyStore() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            return store;
        }

        static void deleteKey(int account) throws Exception {
            keyStore().deleteEntry(ALIAS_PREFIX + account);
        }

        static String encrypt(int account, long owner, String value) throws Exception {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS_PREFIX + account,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            SecretKey key = generator.generateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            cipher.updateAAD((entry(account) + ":" + owner).getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v2:" + owner + ":" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
        }

        static String decrypt(int account, long owner, String encoded) throws Exception {
            String[] parts = encoded.split(":", -1);
            if (parts.length != 4 || !"v2".equals(parts[0]) || !Long.toString(owner).equals(parts[1])) {
                throw new IllegalArgumentException("Unknown encrypted API key format");
            }
            SecretKey key = (SecretKey) keyStore().getKey(ALIAS_PREFIX + account, null);
            if (key == null) {
                throw new IllegalStateException("Missing API key encryption key");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128,
                    Base64.decode(parts[2], Base64.NO_WRAP)));
            cipher.updateAAD((entry(account) + ":" + owner).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.decode(parts[3], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        }
    }
}
