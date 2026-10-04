package android.content;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class Context {
    public static final int MODE_PRIVATE = 0;
    private final Map<String, MemoryPreferences> stores = new HashMap<>();
    public MemoryPreferences getSharedPreferences(String name, int mode) {
        return stores.computeIfAbsent(name, ignored -> new MemoryPreferences());
    }
    public static final class MemoryPreferences implements SharedPreferences {
        public final Map<String, String> values = new HashMap<>();
        public boolean failCommit;
        public Runnable duringCommit;
        public int commits;
        public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
        public Editor edit() {
            return new Editor() {
                final Map<String, String> puts = new HashMap<>();
                final Set<String> removals = new HashSet<>();
                boolean clear;
                public Editor putString(String key, String value) { puts.put(key, value); return this; }
                public Editor remove(String key) { removals.add(key); return this; }
                public Editor clear() { clear = true; return this; }
                private void memory() {
                    if (clear) values.clear();
                    for (String key : removals) values.remove(key);
                    values.putAll(puts);
                }
                public boolean commit() {
                    commits++; memory();
                    if (duringCommit != null) { Runnable action = duringCommit; duringCommit = null; action.run(); }
                    return !failCommit;
                }
                public void apply() { memory(); }
            };
        }
    }
}
