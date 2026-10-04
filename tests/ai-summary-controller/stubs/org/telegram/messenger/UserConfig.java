package org.telegram.messenger;
public final class UserConfig {
 public static final int MAX_ACCOUNT_COUNT=4; public static int selectedAccount;
 private static final UserConfig[] values={new UserConfig(),new UserConfig(),new UserConfig(),new UserConfig()};
 public long owner=1000; public static UserConfig getInstance(int a){return values[a];}
 public long getClientUserId(){return owner;}
}
