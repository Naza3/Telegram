/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/** The entry gesture is local to this installation, independent of chat accounts. */
public final class NotesEntryPreferences {
    public static final int LONG_PRESS_TITLE = 0;
    public static final int FIVE_TAPS = 1;
    public static final int DEFAULT_RELOCK_DELAY_SECONDS = 30;
    public static final int MAX_RELOCK_DELAY_SECONDS = 86400;

    // SharedPreferences changes its in-memory map before commit() reports disk success.
    // Keep readers on the last confirmed value while a background write is in progress.
    private static volatile Integer confirmedTriggerMode;
    private static volatile Integer confirmedRelockDelay;

    private NotesEntryPreferences() { }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("shiye_notes_entry", Context.MODE_PRIVATE);
    }

    public static int getTriggerMode() {
        Integer confirmed = confirmedTriggerMode;
        if (confirmed != null) return confirmed;
        int mode = readInt("trigger_mode", LONG_PRESS_TITLE);
        confirmed = confirmedTriggerMode;
        if (confirmed != null) return confirmed;
        return mode == FIVE_TAPS ? FIVE_TAPS : LONG_PRESS_TITLE;
    }

    /** Call off the UI thread. A failed disk write must not be presented as a saved choice. */
    public static synchronized boolean setTriggerMode(int mode) {
        if (mode != LONG_PRESS_TITLE && mode != FIVE_TAPS) {
            throw new IllegalArgumentException("Unknown notes entry gesture");
        }
        int previous = getTriggerMode();
        confirmedTriggerMode = previous;
        boolean saved = commitInt("trigger_mode", mode, previous);
        if (saved) confirmedTriggerMode = mode;
        return saved;
    }

    public static int getRelockDelaySeconds() {
        Integer confirmed = confirmedRelockDelay;
        if (confirmed != null) return confirmed;
        int delay = readInt("relock_delay_seconds", DEFAULT_RELOCK_DELAY_SECONDS);
        confirmed = confirmedRelockDelay;
        if (confirmed != null) return confirmed;
        return delay >= 0 && delay <= MAX_RELOCK_DELAY_SECONDS ? delay : DEFAULT_RELOCK_DELAY_SECONDS;
    }

    /** Call off the UI thread. Zero means immediate locking; no value disables locking. */
    public static synchronized boolean setRelockDelaySeconds(int seconds) {
        if (seconds < 0 || seconds > MAX_RELOCK_DELAY_SECONDS) {
            throw new IllegalArgumentException("Relock delay must be between 0 and 86400 seconds");
        }
        int previous = getRelockDelaySeconds();
        confirmedRelockDelay = previous;
        boolean saved = commitInt("relock_delay_seconds", seconds, previous);
        if (saved) confirmedRelockDelay = seconds;
        return saved;
    }

    private static int readInt(String key, int fallback) {
        try {
            return preferences().getInt(key, fallback);
        } catch (ClassCastException malformedPreference) {
            return fallback;
        }
    }

    private static boolean commitInt(String key, int value, int previous) {
        SharedPreferences preferences = preferences();
        try {
            if (preferences.edit().putInt(key, value).commit()) return true;
        } catch (RuntimeException writeFailure) {
            // Restore the previous value below, including commit() implementations that throw.
        }
        try {
            preferences.edit().putInt(key, previous).commit();
        } catch (RuntimeException rollbackFailure) {
            // The confirmed-value cache still prevents readers from observing a failed choice.
        }
        return false;
    }
}
