package android.content;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A failed Android commit still publishes to memory before its disk result is known. */
public final class MemoryPreferences implements SharedPreferences {
    private final Map<String, Object> memory = new HashMap<>();
    private final Map<String, Object> disk = new HashMap<>();
    public final List<Map<String, Object>> successfulDiskWrites = new ArrayList<>();
    public final List<String> commitThreads = new ArrayList<>();
    public int editCalls;
    public int commitCalls;
    public int applyCalls;
    public int failNextCommits;
    public CountDownLatch nextCommitEntered;
    public CountDownLatch releaseNextCommit;
    public CountDownLatch nextIntReadEntered;
    public CountDownLatch releaseNextIntRead;

    public synchronized void seed(String key, Object value) { memory.put(key, value); disk.put(key, value); }
    public synchronized Object memoryValue(String key) { return memory.get(key); }
    public synchronized Object diskValue(String key) { return disk.get(key); }
    public synchronized Map<String, Object> diskSnapshot() { return new HashMap<>(disk); }
    public synchronized Map<String, ?> getAll() { return new HashMap<>(memory); }
    public synchronized String getString(String key, String fallback) { return memory.containsKey(key) ? (String) memory.get(key) : fallback; }
    @SuppressWarnings("unchecked")
    public synchronized Set<String> getStringSet(String key, Set<String> fallback) { return memory.containsKey(key) ? (Set<String>) memory.get(key) : fallback; }
    public int getInt(String key, int fallback) {
        final CountDownLatch entered, release;
        synchronized (this) {
            entered = nextIntReadEntered;
            release = releaseNextIntRead;
            nextIntReadEntered = releaseNextIntRead = null;
        }
        if (entered != null) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Blocked test read not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
            }
        }
        synchronized (this) { return memory.containsKey(key) ? (Integer) memory.get(key) : fallback; }
    }
    public synchronized long getLong(String key, long fallback) { return memory.containsKey(key) ? (Long) memory.get(key) : fallback; }
    public synchronized float getFloat(String key, float fallback) { return memory.containsKey(key) ? (Float) memory.get(key) : fallback; }
    public synchronized boolean getBoolean(String key, boolean fallback) { return memory.containsKey(key) ? (Boolean) memory.get(key) : fallback; }
    public synchronized boolean contains(String key) { return memory.containsKey(key); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

    public synchronized Editor edit() {
        editCalls++;
        return new Editor() {
            private final Map<String, Object> pending = new HashMap<>();
            private boolean clear;
            public Editor putString(String key, String value) { pending.put(key, value); return this; }
            public Editor putStringSet(String key, Set<String> value) { pending.put(key, value); return this; }
            public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            public Editor remove(String key) { pending.put(key, null); return this; }
            public Editor clear() { clear = true; return this; }
            public boolean commit() {
                final boolean fail;
                final CountDownLatch entered, release;
                final Map<String, Object> proposedDisk;
                synchronized (MemoryPreferences.this) {
                    commitCalls++;
                    commitThreads.add(Thread.currentThread().getName());
                    if (clear) memory.clear();
                    for (Map.Entry<String, Object> entry : pending.entrySet()) {
                        if (entry.getValue() == null) memory.remove(entry.getKey());
                        else memory.put(entry.getKey(), entry.getValue());
                    }
                    proposedDisk = new HashMap<>(memory);
                    fail = failNextCommits > 0;
                    if (fail) failNextCommits--;
                    entered = nextCommitEntered;
                    release = releaseNextCommit;
                    nextCommitEntered = releaseNextCommit = null;
                }
                if (entered != null) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Blocked test commit not released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                    }
                }
                if (fail) return false;
                synchronized (MemoryPreferences.this) {
                    disk.clear(); disk.putAll(proposedDisk);
                    successfulDiskWrites.add(new HashMap<>(disk));
                }
                return true;
            }
            public void apply() {
                synchronized (MemoryPreferences.this) { applyCalls++; }
                commit();
            }
        };
    }
}
