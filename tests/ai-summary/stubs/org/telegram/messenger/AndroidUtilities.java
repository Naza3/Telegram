package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;

/** Deterministic stand-in for the Android main looper, only for the JVM harness. */
public final class AndroidUtilities {
    private static final List<Runnable> immediate = new ArrayList<>();
    private static final List<Runnable> delayed = new ArrayList<>();

    public static synchronized void runOnUIThread(Runnable action) {
        immediate.add(action);
    }

    public static synchronized void runOnUIThread(Runnable action, long delay) {
        (delay > 0 ? delayed : immediate).add(action);
    }

    public static synchronized void cancelRunOnUIThread(Runnable action) {
        immediate.remove(action);
        delayed.remove(action);
    }

    public static void drain() {
        int turns = 0;
        while (true) {
            Runnable action;
            synchronized (AndroidUtilities.class) {
                if (immediate.isEmpty()) return;
                action = immediate.remove(0);
            }
            if (++turns > 10000) throw new AssertionError("Main-looper queue did not settle");
            action.run();
        }
    }

    public static void fireTimers() {
        synchronized (AndroidUtilities.class) {
            immediate.addAll(delayed);
            delayed.clear();
        }
        drain();
    }

    public static synchronized void reset() {
        immediate.clear();
        delayed.clear();
    }

    public static synchronized int pendingTimers() {
        return delayed.size();
    }

    public static synchronized int pendingImmediate() {
        return immediate.size();
    }
}
