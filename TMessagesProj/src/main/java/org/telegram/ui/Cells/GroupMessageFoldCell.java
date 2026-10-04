/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** The hidden original is deliberately never bound to this view or its accessibility tree. */
public final class GroupMessageFoldCell extends FrameLayout {
    private final TextView label;
    private final Theme.ResourcesProvider resourcesProvider;
    private boolean hidden, colorsApplied;
    private int textColor, backgroundColor, pressedColor;

    public GroupMessageFoldCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        label = new TextView(context);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        setMinimumHeight(AndroidUtilities.dp(48));
        label.setDuplicateParentStateEnabled(true);
        label.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(7), AndroidUtilities.dp(12), AndroidUtilities.dp(7));
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 4, 20, 4));
    }

    public void bind(boolean hidden, boolean album, boolean uidMatch, Runnable reveal) {
        this.hidden = hidden;
        String text = hidden ? "" : (album ? "已折叠相册" : "已折叠消息")
                + (uidMatch ? " · UID 规则" : " · 关键词规则") + " · 点按展开";
        label.setText(text);
        updateColors();
        label.setVisibility(hidden ? GONE : VISIBLE);
        setContentDescription(hidden ? null : text);
        setImportantForAccessibility(hidden ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(!hidden);
        setOnClickListener(hidden ? null : view -> reveal.run());
        setClickable(!hidden);
        setLongClickable(false);
        requestLayout();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (hidden) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0);
            return;
        }
        // The service pill wraps its text; the parent keeps a full-width, 48 dp touch target.
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    private void updateColors() {
        int nextText = Theme.getColor(Theme.key_chat_serviceText, resourcesProvider);
        int nextBackground = Theme.getColor(Theme.key_chat_serviceBackground, resourcesProvider);
        int nextPressed = Theme.getColor(Theme.key_chat_serviceBackgroundSelected, resourcesProvider);
        if (!colorsApplied || textColor != nextText || backgroundColor != nextBackground || pressedColor != nextPressed) {
            colorsApplied = true;
            textColor = nextText; backgroundColor = nextBackground; pressedColor = nextPressed;
            label.setTextColor(textColor);
            label.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14), backgroundColor, pressedColor));
        }
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        updateColors();
        super.dispatchDraw(canvas);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
        if (hidden) info.setVisibleToUser(false);
    }
}
