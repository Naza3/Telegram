package org.telegram.messenger;
import java.util.ArrayDeque;
public final class AndroidUtilities {
    private static final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    public static void runOnUIThread(Runnable task) { pending.add(task); }
    public static void drain() { while (!pending.isEmpty()) pending.remove().run(); }
}
