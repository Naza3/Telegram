package org.telegram.messenger.ai;

/** Deliberately does not pretend to test Android Keystore; its integration needs a device. */
public final class AiSummarySecretStore {
    static int failMigrationReads, failClears;
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
    static String loadForMigration(int account, long owner) {
        if (failMigrationReads-- > 0) throw new IllegalStateException("原 API Key 暂时无法解密，未覆盖旧配置。");
        return load(account, owner);
    }
    public static boolean clearOwner(int account, long owner) {
        if (failClears-- > 0) return false;
        VALUES.remove(account + ":" + owner);
        return true;
    }
    static void reset() { VALUES.clear(); failMigrationReads = failClears = 0; }
}
