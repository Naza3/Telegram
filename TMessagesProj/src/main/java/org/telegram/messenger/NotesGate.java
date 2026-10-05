/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.ui.BubbleActivity;
import org.telegram.ui.ChatsWidgetConfigActivity;
import org.telegram.ui.ContactsWidgetConfigActivity;
import org.telegram.ui.ExternalActionActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PopupNotificationActivity;
import org.telegram.ui.ShareActivity;
import org.telegram.ui.ShiyeNotesActivity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** The grant and deferred destination live only in this process, never in an Intent or backup. */
public final class NotesGate {
    private static final NotesGateState STATE = new NotesGateState();
    private static final Map<Activity, Curtain> curtains = new WeakHashMap<>();
    private static final Map<Activity, Boolean> protectedActivities = new WeakHashMap<>();
    private static final Map<Activity, Boolean> startedActivities = new WeakHashMap<>();
    private static final Map<Dialog, Boolean> contentDialogs = new WeakHashMap<>();
    private static WeakReference<ShiyeNotesActivity> notes = new WeakReference<>(null);
    private static WeakReference<Activity> resumedProtected = new WeakReference<>(null);
    private static PendingEntry pending;
    private static boolean installed;

    private NotesGate() { }

    public static NotesGateState state() { return STATE; }
    public static boolean isUnlocked() { return STATE.isUnlocked(); }

    public static boolean isProtected(Activity activity) {
        return activity instanceof LaunchActivity || activity instanceof ExternalActionActivity
                || activity instanceof ShareActivity || activity instanceof BubbleActivity
                || activity instanceof PopupNotificationActivity;
    }

    public static boolean canShowContent(Context context) {
        Activity activity = AndroidUtilities.getActivity(context);
        return activity == null || !isProtected(activity) || isUnlocked();
    }

    public static void onDialogShown(Dialog dialog) {
        Activity activity = AndroidUtilities.findActivity(dialog.getContext());
        if (isProtected(activity)) {
            if (isUnlocked()) {
                if (dialog.getWindow() != null) {
                    View decor = dialog.getWindow().getDecorView();
                    decor.setVisibility(View.VISIBLE);
                    decor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                }
                contentDialogs.put(dialog, Boolean.TRUE);
            }
            else dialog.dismiss();
        }
    }

    public static boolean isScreenInteractive(Context context) {
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isInteractive();
    }

    public static void install(Application application) {
        if (installed) return;
        installed = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle state) {
                if (isProtected(a)) {
                    protectedActivities.put(a, Boolean.TRUE);
                    // Do not disable ordinary screenshots while the user is authenticated.
                    if (Build.VERSION.SDK_INT >= 33) a.setRecentsScreenshotEnabled(false);
                    if (!isUnlocked()) cover(a);
                }
            }
            @Override public void onActivityPaused(Activity a) {
                if (isProtected(a)) onProtectedPause(a);
            }
            @Override public void onActivityResumed(Activity a) {
                if (isProtected(a)) {
                    resumedProtected = new WeakReference<>(a);
                    if (isUnlocked()) onProtectedResume(a);
                    else cover(a);
                }
            }
            @Override public void onActivityDestroyed(Activity a) {
                curtains.remove(a);
                protectedActivities.remove(a);
                startedActivities.remove(a);
            }
            @Override public void onActivityStarted(Activity a) { startedActivities.put(a, Boolean.TRUE); }
            @Override public void onActivityStopped(Activity a) {
                startedActivities.remove(a);
                // Also covers Home/screen-off during the brief Notes-to-content handoff.
                if (startedActivities.isEmpty()) lock();
            }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        });
    }

    /** Called after super.onCreate, before any protected activity builds or processes content. */
    public static boolean guardActivity(Activity activity, Bundle ignoredState) {
        if (isUnlocked()) return false;
        remember(activity, activity.getIntent(), activity.getCallingActivity() != null);
        cover(activity);
        openNotes(activity, activity.getCallingActivity() != null);
        activity.finish();
        return true;
    }

    public static boolean guardResume(Activity activity) {
        if (isUnlocked()) return false;
        cover(activity);
        // Keep the original host and pending ActivityResult (camera, passport, file picker).
        // Its original Intent was already consumed and must not be replayed on every resume.
        if (pending == null) {
            remember(activity, activity.getIntent(), false);
            pending.resumeExisting = new WeakReference<>(activity);
        }
        if (resumedProtected.get() == activity) openNotes(activity, false);
        return true;
    }

    /** Must be checked before proxy links, account switching, sharing or any other intent effect. */
    public static boolean deferIntent(Activity activity, Intent intent) {
        if (isUnlocked()) return false;
        remember(activity, intent, false);
        pending.resumeExisting = new WeakReference<>(activity);
        pending.replayIntent = true;
        cover(activity);
        if (resumedProtected.get() == activity) openNotes(activity, false);
        return true;
    }

    private static void remember(Activity activity, Intent intent, boolean forwardResult) {
        Class<? extends Activity> target;
        if (activity instanceof ChatsWidgetConfigActivity) target = ChatsWidgetConfigActivity.class;
        else if (activity instanceof ContactsWidgetConfigActivity) target = ContactsWidgetConfigActivity.class;
        else if (activity instanceof ExternalActionActivity) target = ExternalActionActivity.class;
        else if (activity instanceof ShareActivity) target = ShareActivity.class;
        else if (activity instanceof BubbleActivity) target = BubbleActivity.class;
        else if (activity instanceof PopupNotificationActivity) target = PopupNotificationActivity.class;
        else target = LaunchActivity.class;
        pending = new PendingEntry(target, intent == null ? new Intent(Intent.ACTION_MAIN) : new Intent(intent), forwardResult);
    }

    private static void openNotes(Activity source, boolean forwardResult) {
        Intent intent = new Intent(source, ShiyeNotesActivity.class);
        // Carry URI grants across the redirect before finishing a share's original recipient.
        if (pending != null) {
            Intent original = pending.intent;
            intent.setClipData(original.getClipData());
            intent.setDataAndType(original.getData(), original.getType());
            intent.addFlags(original.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (forwardResult) intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        else intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        source.startActivity(intent);
        source.overridePendingTransition(0, 0);
    }

    public static void attachNotes(ShiyeNotesActivity activity) { notes = new WeakReference<>(activity); }
    public static void detachNotes(ShiyeNotesActivity activity) {
        if (notes.get() == activity) notes.clear();
    }

    /** Called only after the hardware proof and foreground/epoch checks both succeed. */
    public static boolean enterProtectedActivity(ShiyeNotesActivity activity) {
        if (!isUnlocked() || !isScreenInteractive(activity)) { lock(); return false; }
        PendingEntry entry = pending;
        if (entry == null) entry = new PendingEntry(LaunchActivity.class, new Intent(Intent.ACTION_MAIN), false);
        Activity existing = entry.resumeExisting == null ? null : entry.resumeExisting.get();
        boolean reuseExisting = existing != null && !existing.isFinishing() && !existing.isDestroyed()
                && existing.getTaskId() == activity.getTaskId();
        if (reuseExisting && !entry.replayIntent) {
            pending = null;
            return true; // finishing the notes activity resumes exactly the original host
        }
        Intent destination = new Intent(entry.intent);
        // The explicit destination is selected here, never from untrusted extras or a selector.
        destination.setSelector(null);
        destination.setComponent(new android.content.ComponentName(activity, entry.target));
        destination.setPackage(activity.getPackageName());
        int flags = destination.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        flags |= Intent.FLAG_ACTIVITY_NO_ANIMATION;
        if (reuseExisting) flags |= Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP;
        else if (entry.forwardResult) flags |= Intent.FLAG_ACTIVITY_FORWARD_RESULT;
        else if (entry.target == LaunchActivity.class) flags |= Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP;
        destination.setFlags(flags);
        try {
            activity.startActivity(destination);
            pending = null;
            activity.overridePendingTransition(0, 0);
            return true;
        } catch (RuntimeException e) {
            FileLog.e(e);
            lock();
            return false;
        }
    }

    public static void abandonPendingEntry() { pending = null; }

    /** Some devices blank the display without a complete Activity pause/resume pair. */
    public static void onScreenOn() {
        Activity activity = resumedProtected.get();
        if (!isUnlocked() && activity != null && !activity.isFinishing()
                && !activity.isDestroyed() && isScreenInteractive(activity)) guardResume(activity);
    }

    public static void onProtectedPause(Activity activity) {
        if (resumedProtected.get() == activity) resumedProtected.clear();
        cover(activity); // synchronous: hide input/accessibility before changing activities
        lock();
    }

    public static void onProtectedResume(Activity activity) {
        if (!isUnlocked()) { cover(activity); return; }
        Curtain curtain = curtains.remove(activity);
        if (curtain != null) curtain.remove(activity);
    }

    public static void lock() {
        STATE.lock(); // invalidate first; cancel() can itself deliver a late hardware callback
        ShiyeNotesActivity current = notes.get();
        if (current != null) current.onGateLocked();
        for (Dialog dialog : new ArrayList<>(contentDialogs.keySet())) {
            try {
                if (dialog != null && dialog.isShowing()) {
                    if (dialog.getWindow() != null) {
                        View decor = dialog.getWindow().getDecorView();
                        decor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                        decor.setVisibility(View.INVISIBLE);
                    }
                    dialog.dismiss();
                }
            }
            catch (RuntimeException e) { FileLog.e(e); }
        }
        contentDialogs.clear();
        for (Activity activity : new ArrayList<>(protectedActivities.keySet())) {
            if (activity != null && !activity.isDestroyed()) cover(activity);
        }
        // A bubble or an external activity can own these windows without any LaunchActivity.
        for (Runnable close : new Runnable[] {
                () -> { if (org.telegram.ui.PhotoViewer.hasInstance()) org.telegram.ui.PhotoViewer.getInstance().closePhoto(false, true); },
                () -> { if (org.telegram.ui.SecretMediaViewer.hasInstance()) org.telegram.ui.SecretMediaViewer.getInstance().closePhoto(false, false); },
                () -> { if (org.telegram.ui.ArticleViewer.hasInstance()) org.telegram.ui.ArticleViewer.getInstance().close(false, true); },
                org.telegram.ui.Components.PipVideoOverlay::dismissForNotesLock,
                org.telegram.ui.Components.voip.RTMPStreamPipOverlay::dismissForNotesLock,
                org.telegram.ui.Stories.LiveStoryPipOverlay::dismissForNotesLock,
                org.telegram.ui.Components.GroupCallPip::dismissForNotesLock,
                org.telegram.ui.Components.voip.VoIPPiPView::dismissForNotesLock,
                org.telegram.ui.VoIPFragment::dismissForNotesLock
        }) {
            try { close.run(); } catch (RuntimeException e) { FileLog.e(e); }
        }
        if (LaunchActivity.instance != null && !LaunchActivity.instance.isDestroyed()) {
            cover(LaunchActivity.instance);
            LaunchActivity.instance.onNotesGateLocked();
        }
    }

    private static void cover(Activity activity) {
        if (activity.isDestroyed()) return;
        Curtain existing = curtains.get(activity);
        if (existing != null) { existing.view.bringToFront(); return; }
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        Curtain curtain = new Curtain(activity, decor);
        curtains.put(activity, curtain);
    }

    private static final class PendingEntry {
        final Class<? extends Activity> target;
        final Intent intent;
        final boolean forwardResult;
        WeakReference<Activity> resumeExisting;
        boolean replayIntent;
        PendingEntry(Class<? extends Activity> target, Intent intent, boolean forwardResult) {
            this.target = target; this.intent = intent; this.forwardResult = forwardResult;
        }
    }

    private static final class Curtain {
        final FrameLayout view;
        final Map<View, Integer> accessibility = new WeakHashMap<>();
        final boolean addedSecure;
        Curtain(Activity activity, ViewGroup decor) {
            View focused = decor.findFocus();
            if (focused != null) { AndroidUtilities.hideKeyboard(focused); focused.clearFocus(); }
            for (int i = 0; i < decor.getChildCount(); i++) {
                View child = decor.getChildAt(i);
                accessibility.put(child, child.getImportantForAccessibility());
                child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            view = new FrameLayout(activity);
            view.setBackgroundColor(0xfff4f5f0);
            view.setClickable(true);
            view.setFocusableInTouchMode(true);
            TextView title = new TextView(activity);
            title.setText(R.string.AppDisplayName);
            title.setTextColor(0xff405a47);
            title.setTextSize(24);
            title.setGravity(Gravity.CENTER);
            view.addView(title, new FrameLayout.LayoutParams(-1, -1));
            decor.addView(view, new ViewGroup.LayoutParams(-1, -1));
            view.requestFocus();
            addedSecure = Build.VERSION.SDK_INT < 33
                    && (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0;
            if (addedSecure) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        void remove(Activity activity) {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            for (Map.Entry<View, Integer> entry : accessibility.entrySet()) {
                if (entry.getKey() != null) entry.getKey().setImportantForAccessibility(entry.getValue());
            }
            if (addedSecure) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
