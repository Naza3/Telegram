package org.telegram.messenger;
public final class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 4;
    private static final UserConfig[] instances = {new UserConfig(), new UserConfig(), new UserConfig(), new UserConfig()};
    public long owner;
    public static UserConfig getInstance(int account) { return instances[account]; }
    public long getClientUserId() { return owner; }
}
