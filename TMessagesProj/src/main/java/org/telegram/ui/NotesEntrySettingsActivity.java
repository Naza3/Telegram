/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotesEntryPreferences;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Components.FeatureUi;
import org.telegram.ui.Components.LayoutHelper;

/** Only reachable from the authenticated application's settings. */
public final class NotesEntrySettingsActivity extends BaseFragment {
    private LinearLayout content;
    private boolean saving;
    private boolean saveFailed;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(context.getString(R.string.ShiyeNotesEntrySettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        FeatureUi.bindThemeUpdates(scroll, this::refreshRows);
        refreshRows();
        return fragmentView;
    }

    private void refreshRows() {
        if (content == null) return;
        Context context = content.getContext();
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
        content.removeAllViews();
        HeaderCell header = new HeaderCell(context, getResourceProvider());
        header.setText(context.getString(R.string.ShiyeNotesEntryGesture));
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        content.addView(header, LayoutHelper.createLinear(-1, -2));
        addChoice(NotesEntryPreferences.LONG_PRESS_TITLE, R.string.ShiyeNotesEntryLongPress, true);
        addChoice(NotesEntryPreferences.FIVE_TAPS, R.string.ShiyeNotesEntryFiveTaps, false);
        TextView explanation = new TextView(context);
        explanation.setText(context.getString(R.string.ShiyeNotesEntryExplanation));
        explanation.setTextSize(14);
        explanation.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(14), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
        explanation.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, getResourceProvider()));
        content.addView(explanation, LayoutHelper.createLinear(-1, -2));
        if (saving || saveFailed) {
            TextView status = new TextView(context);
            status.setText(context.getString(saving ? R.string.ShiyeNotesSaving : R.string.ShiyeNotesEntrySaveFailed));
            status.setTextSize(14);
            status.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(14));
            status.setTextColor(Theme.getColor(saveFailed ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText2, getResourceProvider()));
            content.addView(status, LayoutHelper.createLinear(-1, -2));
        }
    }

    private void addChoice(int mode, int label, boolean divider) {
        RadioCell cell = new RadioCell(content.getContext(), getResourceProvider());
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        cell.setText(content.getContext().getString(label), NotesEntryPreferences.getTriggerMode() == mode, divider);
        cell.setEnabled(!saving);
        cell.setAlpha(saving ? 0.5f : 1f);
        cell.setOnClickListener(view -> saveMode(mode));
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
    }

    private void saveMode(int mode) {
        if (saving || NotesEntryPreferences.getTriggerMode() == mode && !saveFailed) return;
        saving = true;
        saveFailed = false;
        refreshRows();
        Utilities.globalQueue.postRunnable(() -> {
            boolean success;
            try {
                success = NotesEntryPreferences.setTriggerMode(mode);
            } catch (RuntimeException error) {
                success = false;
            }
            final boolean saved = success;
            AndroidUtilities.runOnUIThread(() -> {
                saving = false;
                saveFailed = !saved;
                if (!isFinished) refreshRows();
            });
        });
    }
}
