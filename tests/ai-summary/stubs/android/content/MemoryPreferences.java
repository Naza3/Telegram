package android.content;

import java.util.HashMap;
import java.util.Map;

public final class MemoryPreferences implements SharedPreferences {
    public final Map<String, String> values = new HashMap<>();
    public Map<String, ?> getAll() { return new HashMap<>(values); }
    public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
    public int getInt(String key, int fallback) { return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback; }
    public Editor edit() {
        return new Editor() {
            private final Map<String, String> pending = new HashMap<>();
            public Editor putString(String key, String value) { pending.put(key, value); return this; }
            public Editor putInt(String key, int value) { pending.put(key, Integer.toString(value)); return this; }
            public Editor remove(String key) { pending.put(key, null); return this; }
            public boolean commit() {
                for (Map.Entry<String, String> entry : pending.entrySet()) {
                    if (entry.getValue() == null) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
                pending.clear();
                return true;
            }
            public void apply() { commit(); }
        };
    }
}
