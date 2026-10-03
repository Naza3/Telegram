package android.content;

public interface SharedPreferences {
    String getString(String key, String fallback);
    int getInt(String key, int fallback);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor remove(String key);
        void apply();
    }
}
