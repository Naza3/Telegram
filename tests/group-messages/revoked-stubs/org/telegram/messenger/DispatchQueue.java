package org.telegram.messenger;
import java.util.ArrayDeque;
public final class DispatchQueue {
    private static final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    public DispatchQueue(String name) { }
    public void postRunnable(Runnable task) { pending.add(task); }
    public static void drain() { while (!pending.isEmpty()) pending.remove().run(); }
}
