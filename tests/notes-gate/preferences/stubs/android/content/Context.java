package android.content;

public final class Context {
    public static final int MODE_PRIVATE = 0;
    public final MemoryPreferences preferences = new MemoryPreferences();
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!"shiye_notes_entry".equals(name) || mode != MODE_PRIVATE) {
            throw new AssertionError("Notes preferences must stay in the installation-local private namespace");
        }
        return preferences;
    }
}
