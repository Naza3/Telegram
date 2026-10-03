package org.telegram.messenger;
public final class UserConfig {
    public static final int MAX_ACCOUNT_COUNT = 4;
    private static final UserConfig[] INSTANCES = new UserConfig[MAX_ACCOUNT_COUNT];
    public long clientUserId;
    private int account;

    public static UserConfig getInstance(int account) {
        if (INSTANCES[account] == null) {
            INSTANCES[account] = new UserConfig();
            INSTANCES[account].account = account;
            INSTANCES[account].clientUserId = 1000L + account;
        }
        return INSTANCES[account];
    }

    public long getClientUserId() { return clientUserId; }
    public org.telegram.tgnet.TLRPC.User getCurrentUser() {
        return org.telegram.messenger.MessagesController.getInstance(account).getUser(clientUserId);
    }
    public void setClientUserId(long value) { clientUserId = value; }
    public boolean isClientActivated() { return clientUserId != 0; }
}
