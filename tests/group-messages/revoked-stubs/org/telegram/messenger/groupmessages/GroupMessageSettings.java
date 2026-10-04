package org.telegram.messenger.groupmessages;
import java.util.HashSet;
import java.util.Set;
public final class GroupMessageSettings {
    public static final Set<String> enabled = new HashSet<>();
    public static boolean broken;
    public static final class Rule { public final boolean antiRevoke; Rule(boolean enabled) { antiRevoke = enabled; } }
    public static Rule get(int account, long dialog) {
        if (broken) throw new IllegalStateException("Fixture settings read failure");
        return new Rule(enabled.contains(account + ":" + dialog));
    }
}
