package android.content;

import java.util.HashMap;
import java.util.Map;

public final class MemoryPreferences implements SharedPreferences {
    public final Map<String, String> values = new HashMap<>();
    public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
    public int getInt(String key, int fallback) { return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback; }
    public Editor edit() {
        return new Editor() {
            public Editor putString(String key, String value) { values.put(key, value); return this; }
            public Editor putInt(String key, int value) { values.put(key, Integer.toString(value)); return this; }
            public Editor remove(String key) { values.remove(key); return this; }
            public void apply() {}
        };
    }
}
