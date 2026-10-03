package org.telegram.messenger.ai;

/** Deliberately does not pretend to test Android Keystore; its integration needs a device. */
public final class AiSummarySecretStore {
    private static final java.util.Map<String, String> VALUES = new java.util.HashMap<>();
    public static String load(int account) {
        return load(account, org.telegram.messenger.UserConfig.getInstance(account).getClientUserId());
    }
    public static String load(int account, long owner) {
        return VALUES.getOrDefault(account + ":" + owner, "");
    }
    public static boolean save(int account, String key) {
        return save(account, org.telegram.messenger.UserConfig.getInstance(account).getClientUserId(), key);
    }
    public static boolean save(int account, long owner, String key) {
        VALUES.put(account + ":" + owner, key);
        return false;
    }
}
