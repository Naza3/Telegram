/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui.Components;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/** Shared presentation for the fork's screens; never changes text, listeners or input state. */
public final class FeatureUi {
    private FeatureUi() { }

    public static void stylePrimaryAction(TextView view, Theme.ResourcesProvider provider) {
        actionText(view);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, provider));
        view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(6),
                Theme.getColor(Theme.key_featuredStickers_addButton, provider),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed, provider)));
    }

    public static void styleAction(TextView view, Theme.ResourcesProvider provider) {
        actionText(view);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.DEFAULT);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, provider));
        view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, provider), 2));
    }

    public static void styleDangerAction(TextView view, Theme.ResourcesProvider provider) {
        styleAction(view, provider);
        view.setTextColor(Theme.getColor(Theme.key_text_RedRegular, provider));
    }

    private static void actionText(TextView view) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setMinHeight(AndroidUtilities.dp(48));
        view.setPaddingRelative(AndroidUtilities.dp(21), AndroidUtilities.dp(12),
                AndroidUtilities.dp(21), AndroidUtilities.dp(12));
        view.setFocusable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
    }

    public static void styleInput(EditTextBoldCursor view, Theme.ResourcesProvider provider, boolean dialogSurface) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setTextColor(Theme.getColor(dialogSurface ? Theme.key_dialogTextBlack : Theme.key_windowBackgroundWhiteBlackText, provider));
        view.setHintTextColor(Theme.getColor(dialogSurface ? Theme.key_dialogTextHint : Theme.key_windowBackgroundWhiteHintText, provider));
        view.setCursorColor(Theme.getColor(dialogSurface ? Theme.key_dialogTextLink : Theme.key_windowBackgroundWhiteBlueText, provider));
        view.setCursorWidth(1.5f);
        view.setBackground(Theme.createEditTextDrawable(view.getContext(),
                Theme.getColor(dialogSurface ? Theme.key_dialogInputField : Theme.key_windowBackgroundWhiteInputField, provider),
                Theme.getColor(dialogSurface ? Theme.key_dialogInputFieldActivated : Theme.key_windowBackgroundWhiteInputFieldActivated, provider)));
        view.setMinHeight(AndroidUtilities.dp(48));
    }

    /** Custom dialog views follow automatic theme changes only while attached, without rebuilding. */
    public static void bindThemeUpdates(View root, Runnable refresh) {
        class Binding implements View.OnAttachStateChangeListener, NotificationCenter.NotificationCenterDelegate {
            private boolean attached;
            @Override public void onViewAttachedToWindow(View view) {
                if (attached) return;
                attached = true;
                NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
                refresh.run();
            }
            @Override public void onViewDetachedFromWindow(View view) {
                attached = false;
                NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
            }
            @Override public void didReceivedNotification(int id, int account, Object... args) {
                if (attached && id == NotificationCenter.didSetNewTheme) refresh.run();
            }
        }
        Binding binding = new Binding();
        root.addOnAttachStateChangeListener(binding);
        if (root.isAttachedToWindow()) binding.onViewAttachedToWindow(root);
    }
}
