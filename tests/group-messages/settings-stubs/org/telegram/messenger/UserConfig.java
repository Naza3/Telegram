package org.telegram.messenger;
public final class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 4;
    private static final UserConfig[] configs = new UserConfig[MAX_ACCOUNT_COUNT];
    public long owner;
    public static UserConfig getInstance(int account) {
        if (configs[account] == null) configs[account] = new UserConfig();
        return configs[account];
    }
    public long getClientUserId() { return owner; }
}
