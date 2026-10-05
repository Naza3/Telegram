/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.view.View;
import android.view.inputmethod.EditorInfo;
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
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FeatureUi;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;

/** Only reachable from the authenticated application's settings. */
public final class NotesEntrySettingsActivity extends BaseFragment {
    private static final int[] PRESET_DELAYS = {0, 30, 60, 300};
    private LinearLayout content;
    private HeaderCell gestureHeader;
    private HeaderCell relockHeader;
    private final RadioCell[] gestureChoices = new RadioCell[2];
    private final RadioCell[] delayChoices = new RadioCell[PRESET_DELAYS.length];
    private RadioCell customChoice;
    private LinearLayout customEditor;
    private EditTextBoldCursor customInput;
    private TextView customSave;
    private TextView gestureExplanation;
    private TextView relockExplanation;
    private TextView status;
    private boolean saving;
    private boolean saveFailed;
    private boolean validationFailed;
    private boolean customExpanded;

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
        gestureHeader = addHeader(R.string.ShiyeNotesEntryGesture);
        gestureChoices[0] = addChoice(R.string.ShiyeNotesEntryLongPress, true,
                () -> saveMode(NotesEntryPreferences.LONG_PRESS_TITLE));
        gestureChoices[1] = addChoice(R.string.ShiyeNotesEntryFiveTaps, false,
                () -> saveMode(NotesEntryPreferences.FIVE_TAPS));
        gestureExplanation = addExplanation(R.string.ShiyeNotesEntryExplanation);
        relockHeader = addHeader(R.string.ShiyeNotesRelockTitle);
        int[] labels = {R.string.ShiyeNotesRelockImmediate, R.string.ShiyeNotesRelock30Seconds,
                R.string.ShiyeNotesRelock60Seconds, R.string.ShiyeNotesRelock5Minutes};
        for (int i = 0; i < PRESET_DELAYS.length; i++) {
            final int seconds = PRESET_DELAYS[i];
            delayChoices[i] = addChoice(labels[i], true, () -> saveDelay(seconds, false));
        }
        customChoice = addChoice(R.string.ShiyeNotesRelockCustom, false, this::showCustomEditor);
        customEditor = new LinearLayout(context);
        customEditor.setOrientation(LinearLayout.VERTICAL);
        customEditor.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(16));
        content.addView(customEditor, LayoutHelper.createLinear(-1, -2));
        customInput = new EditTextBoldCursor(context);
        customInput.setSingleLine(true);
        // A numeric keyboard without filtering pasted text: "-1" must fail, not become "1".
        customInput.setKeyListener(TextKeyListener.getInstance());
        customInput.setRawInputType(InputType.TYPE_CLASS_NUMBER);
        customInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        customInput.setHint(context.getString(R.string.ShiyeNotesRelockSecondsHint));
        customInput.setContentDescription(context.getString(R.string.ShiyeNotesRelockSecondsHint));
        customInput.setText(Integer.toString(NotesEntryPreferences.getRelockDelaySeconds()));
        customEditor.addView(customInput, LayoutHelper.createLinear(-1, -2));
        customSave = new TextView(context);
        customSave.setText(context.getString(R.string.ShiyeNotesRelockSave));
        customSave.setOnClickListener(view -> saveCustomDelay());
        customEditor.addView(customSave, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
        customInput.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            saveCustomDelay();
            return true;
        });
        customInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (validationFailed || saveFailed) {
                    validationFailed = saveFailed = false;
                    updateStatus();
                }
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        relockExplanation = addExplanation(R.string.ShiyeNotesRelockExplanation);
        status = addExplanation(R.string.ShiyeNotesSaving);
        customExpanded = !isPreset(NotesEntryPreferences.getRelockDelaySeconds());
        FeatureUi.bindThemeUpdates(scroll, this::refreshRows);
        refreshRows();
        return fragmentView;
    }

    private HeaderCell addHeader(int label) {
        HeaderCell header = new HeaderCell(content.getContext(), getResourceProvider());
        header.setText(content.getContext().getString(label));
        content.addView(header, LayoutHelper.createLinear(-1, -2));
        return header;
    }

    private RadioCell addChoice(int label, boolean divider, Runnable action) {
        RadioCell cell = new RadioCell(content.getContext(), getResourceProvider());
        cell.setText(content.getContext().getString(label), false, divider);
        cell.setOnClickListener(view -> action.run());
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
        return cell;
    }

    private TextView addExplanation(int label) {
        TextView text = new TextView(content.getContext());
        text.setText(content.getContext().getString(label));
        text.setTextSize(14);
        text.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(14), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
        content.addView(text, LayoutHelper.createLinear(-1, -2));
        return text;
    }

    /** Recolor and update state in place; never recreate or replace the user's custom input. */
    private void refreshRows() {
        if (content == null) return;
        int surface = Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider());
        int grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, getResourceProvider());
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));
        for (HeaderCell header : new HeaderCell[] {gestureHeader, relockHeader}) {
            header.setBackgroundColor(surface);
            header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
        }
        int mode = NotesEntryPreferences.getTriggerMode();
        for (int i = 0; i < gestureChoices.length; i++) styleChoice(gestureChoices[i], mode == i);
        int delay = NotesEntryPreferences.getRelockDelaySeconds();
        for (int i = 0; i < delayChoices.length; i++) styleChoice(delayChoices[i], delay == PRESET_DELAYS[i]);
        customChoice.setText(isPreset(delay) ? content.getContext().getString(R.string.ShiyeNotesRelockCustom)
                : content.getContext().getString(R.string.ShiyeNotesRelockCustomValue, delay), !isPreset(delay), false);
        styleChoice(customChoice, !isPreset(delay));
        customEditor.setBackgroundColor(surface);
        customEditor.setVisibility(customExpanded ? View.VISIBLE : View.GONE);
        FeatureUi.styleInput(customInput, getResourceProvider(), false);
        FeatureUi.stylePrimaryAction(customSave, getResourceProvider());
        customInput.setEnabled(!saving);
        customSave.setEnabled(!saving);
        customSave.setAlpha(saving ? 0.5f : 1f);
        gestureExplanation.setTextColor(grayText);
        relockExplanation.setTextColor(grayText);
        updateStatus();
    }

    private void styleChoice(RadioCell cell, boolean checked) {
        cell.setBackground(Theme.createSelectorWithBackgroundDrawable(
                Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()),
                Theme.getColor(Theme.key_listSelector, getResourceProvider())));
        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        cell.setChecked(checked, false);
        cell.setEnabled(!saving);
        cell.setAlpha(saving ? 0.5f : 1f);
        for (int i = 0; i < cell.getChildCount(); i++) {
            if (cell.getChildAt(i) instanceof RadioButton) {
                ((RadioButton) cell.getChildAt(i)).setColor(Theme.getColor(Theme.key_radioBackground, getResourceProvider()),
                        Theme.getColor(Theme.key_radioBackgroundChecked, getResourceProvider()));
            }
        }
    }

    private void updateStatus() {
        if (status == null) return;
        status.setVisibility(saving || saveFailed || validationFailed ? View.VISIBLE : View.GONE);
        status.setText(content.getContext().getString(validationFailed ? R.string.ShiyeNotesRelockInvalid
                : saveFailed ? R.string.ShiyeNotesEntrySaveFailed : R.string.ShiyeNotesSaving));
        status.setTextColor(Theme.getColor(saveFailed || validationFailed ? Theme.key_text_RedRegular
                : Theme.key_windowBackgroundWhiteGrayText2, getResourceProvider()));
    }

    private static boolean isPreset(int seconds) {
        for (int preset : PRESET_DELAYS) if (preset == seconds) return true;
        return false;
    }

    private void showCustomEditor() {
        if (saving) return;
        customExpanded = true;
        refreshRows();
        customInput.requestFocus();
        customInput.setSelection(customInput.length());
        AndroidUtilities.showKeyboard(customInput);
    }

    private void saveCustomDelay() {
        if (saving) return;
        String input = customInput.getText().toString();
        int seconds = -1;
        boolean digitsOnly = !input.isEmpty();
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) < '0' || input.charAt(i) > '9') { digitsOnly = false; break; }
        }
        if (digitsOnly) {
            try { seconds = Integer.parseInt(input); }
            catch (NumberFormatException overflow) { /* Invalid below. */ }
        }
        if (seconds < 0 || seconds > NotesEntryPreferences.MAX_RELOCK_DELAY_SECONDS) {
            validationFailed = true;
            saveFailed = false;
            updateStatus();
            customInput.requestFocus();
            return;
        }
        saveDelay(seconds, true);
    }

    private void saveMode(int mode) {
        if (saving || NotesEntryPreferences.getTriggerMode() == mode && !saveFailed) return;
        saveSetting(true, mode);
    }

    private void saveDelay(int seconds, boolean custom) {
        if (saving) return;
        validationFailed = false;
        if (!custom) customExpanded = false;
        AndroidUtilities.hideKeyboard(customInput);
        if (NotesEntryPreferences.getRelockDelaySeconds() == seconds && !saveFailed) {
            refreshRows();
            return;
        }
        saveSetting(false, seconds);
    }

    private void saveSetting(boolean gesture, int value) {
        saving = true;
        saveFailed = validationFailed = false;
        refreshRows();
        Utilities.globalQueue.postRunnable(() -> {
            boolean success;
            try {
                success = gesture ? NotesEntryPreferences.setTriggerMode(value)
                        : NotesEntryPreferences.setRelockDelaySeconds(value);
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
