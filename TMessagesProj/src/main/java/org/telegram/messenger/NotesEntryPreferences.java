/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/** The entry gesture is local to this installation, independent of chat accounts. */
public final class NotesEntryPreferences {
    public static final int LONG_PRESS_TITLE = 0;
    public static final int FIVE_TAPS = 1;

    private NotesEntryPreferences() { }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("shiye_notes_entry", Context.MODE_PRIVATE);
    }

    public static int getTriggerMode() {
        int mode = preferences().getInt("trigger_mode", LONG_PRESS_TITLE);
        return mode == FIVE_TAPS ? FIVE_TAPS : LONG_PRESS_TITLE;
    }

    /** Call off the UI thread. A failed disk write must not be presented as a saved choice. */
    public static boolean setTriggerMode(int mode) {
        if (mode != LONG_PRESS_TITLE && mode != FIVE_TAPS) {
            throw new IllegalArgumentException("Unknown notes entry gesture");
        }
        return preferences().edit().putInt("trigger_mode", mode).commit();
    }
}
