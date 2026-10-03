package android.content;

public interface SharedPreferences {
    java.util.Map<String, ?> getAll();
    String getString(String key, String fallback);
    int getInt(String key, int fallback);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor remove(String key);
        boolean commit();
        void apply();
    }
}
