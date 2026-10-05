/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotesEntryPreferences;
import org.telegram.messenger.NotesStore;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Local notes only. The host owns authentication and decides when this surface may be hidden. */
public final class NotesCoverView extends FrameLayout {
    public interface Delegate {
        void onUnlockRequested();
    }

    private final Delegate delegate;
    private final NotesStore store;
    private final LinearLayout header;
    private final TextView title;
    private final ImageView backButton;
    private final ImageView addButton;
    private final ImageView deleteButton;
    private final TextView doneButton;
    private final TextView status;
    private final FrameLayout pages;
    private final RecyclerListView list;
    private final NotesAdapter adapter;
    private final TextView emptyView;
    private final ScrollView editor;
    private final LinearLayout editorContent;
    private final EditTextBoldCursor noteTitle;
    private final EditTextBoldCursor noteBody;
    private final ArrayList<NotesStore.Note> notes = new ArrayList<>();
    private final int touchSlop;

    private NotesStore.Note currentNote;
    private AlertDialog noteDialog;
    private boolean loaded;
    private boolean loading;
    private boolean binding;
    private boolean dirty;
    private boolean saving;
    private boolean deleting;
    private boolean disposed;
    private boolean authenticationPending;
    private boolean leaveAfterSave;
    private boolean unlockAfterSave;
    private boolean saveFailed;
    private boolean notePersisted;
    private long editRevision;
    private long queuedRevision = -1;
    private int pendingWrites;
    private String statusError;
    private String savedTitle;
    private String savedBody;
    private final Runnable autoSave = this::saveDraft;

    private int touchMode;
    private boolean titlePressed;
    private boolean titleMoved;
    private float downX;
    private float downY;
    private long downAt;
    private long lastTapAt;
    private int taps;
    private final Runnable resetTaps = () -> { taps = 0; lastTapAt = 0; };
    private final Runnable heldTitle = () -> {
        if (titlePressed && !titleMoved && touchMode == NotesEntryPreferences.LONG_PRESS_TITLE) {
            titlePressed = false;
            requestUnlock();
        }
    };

    public NotesCoverView(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        store = NotesStore.getInstance(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        addView(layout, LayoutHelper.createFrame(-1, -1));
        header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), 0);
        layout.addView(header, LayoutHelper.createLinear(-1, 56));
        backButton = icon(R.drawable.ic_ab_back, R.string.ShiyeNotesBack, () -> onBackPressed());
        header.addView(backButton, LayoutHelper.createLinear(48, 48));
        title = new TextView(context);
        title.setText(context.getString(R.string.AppDisplayName));
        title.setTextSize(22);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(12), 0, dp(12), 0);
        title.setClickable(true);
        title.setOnTouchListener(this::onTitleTouch);
        header.addView(title, LayoutHelper.createLinear(0, -1, 1f));
        addButton = icon(R.drawable.msg_add, R.string.ShiyeNotesAdd, this::newNote);
        header.addView(addButton, LayoutHelper.createLinear(48, 48));
        deleteButton = icon(R.drawable.msg_delete, R.string.ShiyeNotesDelete, this::confirmDelete);
        header.addView(deleteButton, LayoutHelper.createLinear(48, 48));
        doneButton = new TextView(context);
        doneButton.setText(context.getString(R.string.ShiyeNotesDone));
        doneButton.setTextSize(16);
        doneButton.setGravity(Gravity.CENTER);
        doneButton.setFocusable(true);
        doneButton.setOnClickListener(view -> onBackPressed());
        header.addView(doneButton, LayoutHelper.createLinear(60, 48));

        status = new TextView(context);
        status.setTextSize(13);
        status.setPadding(dp(24), dp(10), dp(24), dp(10));
        status.setOnClickListener(view -> {
            if (statusError == null) return;
            if (currentNote == null) loadNotes();
            else if (saveFailed) saveDraft();
        });
        layout.addView(status, LayoutHelper.createLinear(-1, -2));
        pages = new FrameLayout(context);
        layout.addView(pages, LayoutHelper.createLinear(-1, 0, 1f));
        list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(adapter = new NotesAdapter());
        list.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < notes.size() && !loading) openNote(notes.get(position));
        });
        pages.addView(list, LayoutHelper.createFrame(-1, -1));
        emptyView = new TextView(context);
        emptyView.setText(context.getString(R.string.ShiyeNotesEmpty));
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(28), dp(28), dp(28), dp(28));
        pages.addView(emptyView, LayoutHelper.createFrame(-1, -1));
        editor = new ScrollView(context);
        editor.setFillViewport(true);
        editorContent = new LinearLayout(context);
        editorContent.setOrientation(LinearLayout.VERTICAL);
        editorContent.setPadding(dp(24), dp(8), dp(24), dp(24));
        editor.addView(editorContent, new ScrollView.LayoutParams(-1, -2));
        noteTitle = new EditTextBoldCursor(context);
        noteTitle.setHint(context.getString(R.string.ShiyeNotesTitleHint));
        noteTitle.setSingleLine(true);
        noteTitle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editorContent.addView(noteTitle, LayoutHelper.createLinear(-1, -2));
        noteBody = new EditTextBoldCursor(context);
        noteBody.setHint(context.getString(R.string.ShiyeNotesBodyHint));
        noteBody.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        noteBody.setGravity(Gravity.TOP | Gravity.START);
        noteBody.setMinLines(12);
        editorContent.addView(noteBody, LayoutHelper.createLinear(-1, -2, 0, 12, 0, 0));
        pages.addView(editor, LayoutHelper.createFrame(-1, -1));
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (binding || disposed || currentNote == null) return;
                editRevision++;
                dirty = !noteTitle.getText().toString().equals(savedTitle) || !noteBody.getText().toString().equals(savedBody);
                saveFailed = false;
                statusError = null;
                removeCallbacks(autoSave);
                if (dirty || saving) postDelayed(autoSave, 450);
                renderStatus();
            }
            @Override public void afterTextChanged(Editable s) { }
        };
        noteTitle.addTextChangedListener(watcher);
        noteBody.addTextChangedListener(watcher);
        setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // The host uses ADJUST_RESIZE for the keyboard; apply only system-bar insets here.
            setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        FeatureUi.bindThemeUpdates(this, this::refreshTheme);
        refreshTheme();
        showList();
        loadNotes();
    }

    private ImageView icon(int resource, int description, Runnable action) {
        ImageView view = new ImageView(getContext());
        view.setImageResource(resource);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setContentDescription(getContext().getString(description));
        view.setFocusable(true);
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }

    public void refreshTheme() {
        int background = Theme.getColor(Theme.key_windowBackgroundGray) | 0xff000000;
        int surface = Theme.getColor(Theme.key_windowBackgroundWhite) | 0xff000000;
        int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        setBackgroundColor(background);
        header.setBackgroundColor(surface);
        editor.setBackgroundColor(surface);
        editorContent.setBackgroundColor(surface);
        title.setTextColor(text);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        doneButton.setTextColor(accent);
        doneButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        for (ImageView button : new ImageView[] {backButton, addButton, deleteButton}) {
            button.setColorFilter(text, PorterDuff.Mode.SRC_IN);
            button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        }
        FeatureUi.styleInput(noteTitle, null, false);
        noteTitle.setTextSize(23);
        noteTitle.setTypeface(AndroidUtilities.bold());
        noteTitle.setBackground(null);
        FeatureUi.styleInput(noteBody, null, false);
        noteBody.setBackground(null);
        adapter.notifyDataSetChanged();
        renderStatus();
    }

    private void loadNotes() {
        if (disposed || loading) return;
        loading = true;
        statusError = null;
        updateControls();
        renderStatus();
        store.load(new NotesStore.Callback<List<NotesStore.Note>>() {
            @Override public void onSuccess(List<NotesStore.Note> result) {
                if (disposed) return;
                loading = false;
                loaded = true;
                notes.clear();
                notes.addAll(result);
                sortNotes();
                showList();
            }
            @Override public void onError(String error) {
                if (disposed) return;
                loading = false;
                statusError = error;
                updateControls();
                renderStatus();
            }
        });
    }

    private void sortNotes() {
        Collections.sort(notes, (left, right) -> Long.compare(right.updatedAt, left.updatedAt));
        adapter.notifyDataSetChanged();
    }

    private void newNote() {
        if (!loaded || loading || disposed) return;
        long now = System.currentTimeMillis();
        openNote(new NotesStore.Note(UUID.randomUUID().toString(), "", "", now, now));
        noteTitle.requestFocus();
        AndroidUtilities.showKeyboard(noteTitle);
    }

    private void openNote(NotesStore.Note note) {
        if (disposed) return;
        cancelGesture();
        currentNote = note;
        savedTitle = note.title;
        savedBody = note.body;
        editRevision = 0;
        queuedRevision = -1;
        notePersisted = false;
        for (NotesStore.Note existing : notes) if (existing.id.equals(note.id)) { notePersisted = true; break; }
        dirty = saveFailed = false;
        leaveAfterSave = unlockAfterSave = false;
        statusError = null;
        binding = true;
        noteTitle.setText(note.title);
        noteBody.setText(note.body);
        binding = false;
        list.setVisibility(GONE);
        emptyView.setVisibility(GONE);
        editor.setVisibility(VISIBLE);
        editor.scrollTo(0, 0);
        updateControls();
        renderStatus();
    }

    private void showList() {
        removeCallbacks(autoSave);
        AndroidUtilities.hideKeyboard(this);
        currentNote = null;
        dirty = saveFailed = false;
        leaveAfterSave = unlockAfterSave = false;
        statusError = null;
        editor.setVisibility(GONE);
        list.setVisibility(notes.isEmpty() ? GONE : VISIBLE);
        emptyView.setVisibility(loaded && notes.isEmpty() ? VISIBLE : GONE);
        updateControls();
        renderStatus();
    }

    private void updateControls() {
        boolean editing = currentNote != null;
        backButton.setVisibility(editing ? VISIBLE : GONE);
        addButton.setVisibility(editing ? GONE : VISIBLE);
        deleteButton.setVisibility(editing ? VISIBLE : GONE);
        doneButton.setVisibility(editing ? VISIBLE : GONE);
        addButton.setEnabled(loaded && !loading);
        addButton.setAlpha(loaded && !loading ? 1f : 0.4f);
        deleteButton.setEnabled(!saving && !deleting);
        deleteButton.setAlpha(saving || deleting ? 0.4f : 1f);
        doneButton.setEnabled(!deleting);
        noteTitle.setEnabled(!deleting);
        noteBody.setEnabled(!deleting);
    }

    private void renderStatus() {
        if (statusError != null) {
            status.setText(currentNote == null || saveFailed
                    ? getContext().getString(R.string.ShiyeNotesRetry, statusError) : statusError);
            status.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        } else {
            int resource = loading ? R.string.ShiyeNotesLoading : saving || deleting ? R.string.ShiyeNotesSaving
                    : currentNote == null ? R.string.ShiyeNotesLocalOnly : dirty || !notePersisted ? R.string.ShiyeNotesDraft : R.string.ShiyeNotesSaved;
            status.setText(getContext().getString(resource));
            status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        }
        status.setClickable(statusError != null && (currentNote == null || saveFailed));
    }

    private void saveDraft() {
        removeCallbacks(autoSave);
        if (currentNote == null || deleting) return;
        if (!dirty && (!saving || queuedRevision == editRevision)) {
            if (!saving) finishPendingAction();
            return;
        }
        if (saving && queuedRevision == editRevision) return;
        final NotesStore.Note draft = new NotesStore.Note(currentNote.id, noteTitle.getText().toString(),
                noteBody.getText().toString(), currentNote.createdAt, System.currentTimeMillis());
        final long revision = editRevision;
        queuedRevision = revision;
        pendingWrites++;
        saving = true;
        saveFailed = false;
        statusError = null;
        updateControls();
        renderStatus();
        store.save(draft, new NotesStore.Callback<NotesStore.Note>() {
            @Override public void onSuccess(NotesStore.Note saved) {
                saving = --pendingWrites > 0;
                if (disposed) return;
                for (int i = notes.size() - 1; i >= 0; i--) if (notes.get(i).id.equals(saved.id)) notes.remove(i);
                notes.add(saved);
                sortNotes();
                if (currentNote == null || !currentNote.id.equals(saved.id)) return;
                currentNote = saved;
                notePersisted = true;
                savedTitle = saved.title;
                savedBody = saved.body;
                dirty = revision != editRevision && (!noteTitle.getText().toString().equals(savedTitle)
                        || !noteBody.getText().toString().equals(savedBody));
                updateControls();
                renderStatus();
                if (dirty) {
                    if (leaveAfterSave || unlockAfterSave) saveDraft();
                    else postDelayed(autoSave, 450);
                } else finishPendingAction();
            }
            @Override public void onError(String error) {
                saving = --pendingWrites > 0;
                if (disposed) return;
                if (saving) return;
                dirty = true;
                saveFailed = true;
                leaveAfterSave = unlockAfterSave = false;
                statusError = getContext().getString(R.string.ShiyeNotesSaveFailed, error);
                updateControls();
                renderStatus();
            }
        });
    }

    private void finishPendingAction() {
        if (disposed || saving || dirty) return;
        if (unlockAfterSave) {
            unlockAfterSave = false;
            if (!authenticationPending && isShown() && hasWindowFocus()) {
                AndroidUtilities.hideKeyboard(this);
                delegate.onUnlockRequested();
            }
        } else if (leaveAfterSave) {
            showList();
        }
    }

    private void confirmDelete() {
        if (currentNote == null || saving || deleting || disposed) return;
        cancelGesture();
        final String id = currentNote.id;
        noteDialog = new AlertDialog.Builder(getContext())
                .setTitle(getContext().getString(R.string.ShiyeNotesDelete))
                .setMessage(getContext().getString(R.string.ShiyeNotesDeleteConfirm))
                .setPositiveButton(getContext().getString(R.string.ShiyeNotesDelete), (dialog, which) -> deleteNote(id))
                .setNegativeButton(getContext().getString(R.string.ShiyeNotesCancel), null)
                .create();
        noteDialog.show();
    }

    private void deleteNote(String id) {
        if (disposed || currentNote == null || !currentNote.id.equals(id)) return;
        removeCallbacks(autoSave);
        deleting = true;
        leaveAfterSave = unlockAfterSave = false;
        updateControls();
        renderStatus();
        store.delete(id, new NotesStore.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                deleting = false;
                if (disposed) return;
                for (int i = notes.size() - 1; i >= 0; i--) if (notes.get(i).id.equals(id)) notes.remove(i);
                adapter.notifyDataSetChanged();
                showList();
            }
            @Override public void onError(String error) {
                deleting = false;
                if (disposed) return;
                statusError = getContext().getString(R.string.ShiyeNotesDeleteFailed, error);
                updateControls();
                renderStatus();
            }
        });
    }

    public boolean onBackPressed() {
        cancelGesture();
        if (currentNote == null) return false;
        if (deleting) return true;
        AndroidUtilities.hideKeyboard(this);
        leaveAfterSave = true;
        unlockAfterSave = false;
        saveDraft();
        return true;
    }

    public void setAuthenticationPending(boolean pending) {
        authenticationPending = pending;
        if (pending) cancelGesture();
    }

    private void requestUnlock() {
        cancelGesture();
        if (disposed || authenticationPending || deleting || !isShown() || !hasWindowFocus()) return;
        unlockAfterSave = true;
        leaveAfterSave = false;
        if (currentNote != null) saveDraft();
        else finishPendingAction();
    }

    private boolean onTitleTouch(View view, MotionEvent event) {
        if (disposed || authenticationPending) { cancelGesture(); return true; }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                int mode = NotesEntryPreferences.getTriggerMode();
                if (mode != touchMode) cancelGesture();
                touchMode = mode;
                titlePressed = true;
                titleMoved = false;
                downX = event.getX();
                downY = event.getY();
                downAt = event.getEventTime();
                removeCallbacks(heldTitle);
                if (touchMode == NotesEntryPreferences.LONG_PRESS_TITLE) postDelayed(heldTitle, 2000);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - downX) > touchSlop || Math.abs(event.getY() - downY) > touchSlop
                        || event.getX() < 0 || event.getY() < 0 || event.getX() >= view.getWidth() || event.getY() >= view.getHeight()) cancelGesture();
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(heldTitle);
                if (titlePressed && !titleMoved && touchMode == NotesEntryPreferences.FIVE_TAPS && event.getEventTime() - downAt <= 400) {
                    long now = event.getEventTime();
                    taps = lastTapAt > 0 && now - lastTapAt <= 500 ? taps + 1 : 1;
                    lastTapAt = now;
                    removeCallbacks(resetTaps);
                    if (taps == 5) requestUnlock();
                    else postDelayed(resetTaps, 500);
                } else {
                    taps = 0;
                    lastTapAt = 0;
                }
                titlePressed = false;
                view.performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelGesture();
                return true;
        }
        return true;
    }

    private void cancelGesture() {
        titlePressed = false;
        titleMoved = true;
        taps = 0;
        lastTapAt = 0;
        removeCallbacks(heldTitle);
        removeCallbacks(resetTaps);
    }

    public void onHostPause() {
        cancelGesture();
        unlockAfterSave = false;
        if (noteDialog != null) { noteDialog.dismiss(); noteDialog = null; }
        saveDraft();
    }

    public void dispose() {
        if (disposed) return;
        onHostPause();
        disposed = true;
        removeCallbacks(autoSave);
    }

    @Override protected void onDetachedFromWindow() {
        cancelGesture();
        unlockAfterSave = false;
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            cancelGesture();
            unlockAfterSave = false;
        }
    }

    private final class NotesAdapter extends RecyclerListView.SelectionAdapter {
        @Override public int getItemCount() { return notes.size(); }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return true; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextDetailCell cell = new TextDetailCell(getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new RecyclerListView.Holder(cell);
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            NotesStore.Note note = notes.get(position);
            TextDetailCell cell = (TextDetailCell) holder.itemView;
            String preview = note.body.replace('\n', ' ').trim();
            if (preview.length() > 100) preview = preview.substring(0, 100) + "…";
            String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(note.updatedAt));
            cell.setTextAndValue(note.title.trim().isEmpty() ? getContext().getString(R.string.ShiyeNotesUntitled) : note.title,
                    preview.isEmpty() ? date : preview + " · " + date, position < notes.size() - 1);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite) | 0xff000000);
            cell.updateColors();
        }
    }
}
