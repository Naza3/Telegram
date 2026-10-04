/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.graphics.Rect;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.SavedSummaryPrompts;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

/** Explicitly selected, owner-bound, user-authored instructions. No builtin templates or default selection. */
public final class SummaryPromptsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    public interface SelectionListener { void onSelected(String text); }
    private final int account;
    private final long ownerId;
    private final SelectionListener listener;
    private String initialText;
    private final ArrayList<SavedSummaryPrompts.Entry> entries = new ArrayList<>();
    private LinearLayout content;
    private EditText nameInput;
    private EditText textInput;
    private TextInfoPrivacyCell editorError;
    private View addButton;
    private View saveButton;
    private final ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
    private SavedSummaryPrompts.Entry editingEntry;
    private boolean editing;
    private boolean resumed;
    private boolean destroyed;
    private boolean invalidated;
    private boolean loading;
    private boolean saving;
    private int operation;
    private String error;

    public SummaryPromptsActivity(int account, SelectionListener listener) { this(account, listener, ""); }

    /** initialText is used only after tapping New; never silently saved or selected. */
    public SummaryPromptsActivity(int account, SelectionListener listener, String initialText) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw new IllegalArgumentException("账号无效。");
        this.account = account;
        this.listener = listener;
        this.initialText = initialText == null ? "" : initialText;
        setCurrentAccount(account);
        ownerId = getUserConfig().getClientUserId();
    }

    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate() || !sameOwner()) return false;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        return true;
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("我的要求");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) { if (editing && active()) leaveEditor(); else finishFragment(); }
                else if (id == 1 && active() && !loading && !editing) showEditor(null);
                else if (id == 2) save();
            }
        });
        addButton = actionBar.createMenu().addItem(1, R.drawable.msg_add);
        addButton.setContentDescription("新增我的要求");
        saveButton = actionBar.createMenu().addItemWithWidth(2, R.drawable.ic_ab_done, dp(56), "保存要求");
        saveButton.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(24));
        content.setFocusableInTouchMode(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        showList();
        return fragmentView;
    }

    @Override public void onResume() {
        super.onResume(); resumed = true;
        if (!sameOwner()) invalidate(); else if (!editing) load();
    }
    @Override public void onPause() {
        resumed = false;
        // An explicit save may finish while another app covers us; keep its result and editor error.
        if (!saving) { operation++; loading = false; }
        super.onPause();
    }
    @Override public void onFragmentDestroy() {
        destroyed = true; resumed = false; operation++; entries.clear(); initialText = "";
        if (nameInput != null) nameInput.setText("");
        if (textInput != null) textInput.setText("");
        if (content != null) content.removeAllViews();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        super.onFragmentDestroy();
    }
    @Override public boolean onBackPressed(boolean invoked) {
        if (editing && sameOwner()) {
            if (invoked) leaveEditor();
            return false;
        }
        return super.onBackPressed(invoked);
    }
    @Override public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (id == NotificationCenter.activeAccountChanged && UserConfig.selectedAccount != account
                || id == NotificationCenter.appDidLogout && changedAccount == account) invalidate();
    }
    private boolean sameOwner() {
        return !destroyed && !invalidated && ownerId > 0 && UserConfig.selectedAccount == account
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }
    private boolean active() {
        if (!sameOwner()) { invalidate(); return false; }
        return resumed && getParentActivity() != null && !getParentActivity().isFinishing();
    }
    private void invalidate() {
        if (destroyed || invalidated) return;
        invalidated = true; operation++; entries.clear(); initialText = ""; editing = false; loading = false;
        if (nameInput != null) nameInput.setText("");
        if (textInput != null) textInput.setText("");
        dismissCurrentDialog();
        error = "账号已切换或退出，请从当前账号重新打开我的要求。";
        showList();
    }

    private void load() {
        if (!active()) return;
        final int request = ++operation;
        loading = true; error = null; entries.clear(); showList();
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner()) return;
            try {
                List<SavedSummaryPrompts.Entry> loaded = SavedSummaryPrompts.list(account, ownerId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    loading = false; entries.addAll(loaded); showList();
                });
            } catch (RuntimeException failure) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    loading = false; error = SummaryHistoryActivity.failureMessage(failure); showList();
                });
            }
        });
    }

    private void showList() {
        if (content == null || destroyed) return;
        content.removeAllViews();
        themeDescriptions.clear();
        actionBar.setTitle("我的要求");
        addButton.setVisibility(invalidated ? View.GONE : View.VISIBLE);
        saveButton.setVisibility(View.GONE);
        if (loading) { info("正在读取…"); return; }
        if (error != null) {
            info(error);
            if (!invalidated) action("重试", this::load);
            return;
        }
        if (entries.isEmpty()) {
            header("保存常用要求");
            info("还没有保存的要求。点右上角 ＋，为自己编写的要求添加名称，方便下次使用。");
        }
        for (SavedSummaryPrompts.Entry entry : entries) {
            TextView name = body(entry.name, true);
            name.setPadding(dp(21), dp(16), dp(21), dp(8));
            TextView preview = body(entry.text, false);
            preview.setPadding(dp(21), 0, dp(21), dp(12));
            preview.setMaxLines(3);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (listener != null) action("使用这条要求", () -> select(entry));
            LinearLayout actions = new LinearLayout(content.getContext());
            addRowAction(actions, "编辑", false, () -> showEditor(entry));
            addRowAction(actions, "删除", true, () -> confirmDelete(entry));
            content.addView(actions, LayoutHelper.createLinear(-1, -2));
            View gap = new View(content.getContext());
            content.addView(gap, LayoutHelper.createLinear(-1, 12));
        }
        info(entries.size() + " / " + SavedSummaryPrompts.MAX_PROMPTS + " 条 · 仅当前账号可见，本机加密保存。"
                + "\n使用后填入本次任务，不会修改群默认要求或已经生成的总结。");
    }

    private void select(SavedSummaryPrompts.Entry entry) {
        if (!active() || loading || listener == null) return;
        // Re-read before selection so a deleted or edited record is never applied from an old list.
        final int request = ++operation;
        loading = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SavedSummaryPrompts.Entry current = null;
                for (SavedSummaryPrompts.Entry value : SavedSummaryPrompts.list(account, ownerId)) {
                    if (entry.id.equals(value.id)) { current = value; break; }
                }
                final SavedSummaryPrompts.Entry found = current;
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    loading = false;
                    if (found == null || found.revision != entry.revision) {
                        error = "这条要求已修改或删除，请刷新列表后选择。"; showList(); return;
                    }
                    listener.onSelected(found.text);
                    finishFragment();
                });
            } catch (RuntimeException failure) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    loading = false; error = SummaryHistoryActivity.failureMessage(failure); showList();
                });
            }
        });
    }

    private void showEditor(SavedSummaryPrompts.Entry entry) {
        if (!active() || loading) return;
        editing = true; editingEntry = entry; content.removeAllViews();
        themeDescriptions.clear();
        actionBar.setTitle(entry == null ? "新增要求" : "编辑要求");
        addButton.setVisibility(View.GONE);
        saveButton.setVisibility(View.VISIBLE);
        saveButton.setEnabled(true);
        saveButton.setAlpha(1f);
        header("名称");
        nameInput = input(entry == null ? "" : entry.name, "给这条要求起个名字", false);
        info("最多 " + SavedSummaryPrompts.MAX_NAME_CODE_POINTS + " 个字符。");
        header("核心总结要求");
        textInput = input(entry == null ? initialText : entry.text, "手动填写希望模型如何总结", true);
        info("最多 " + SavedSummaryPrompts.MAX_TEXT_CODE_POINTS + " 个字符。保存后可手动选择使用，不会自动应用到任何群。");
        editorError = info("");
        editorError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private EditText input(String value, String hint, boolean multiline) {
        EditTextBoldCursor input = new EditTextBoldCursor(content.getContext()) {
            @Override protected Theme.ResourcesProvider getResourcesProvider() {
                return SummaryPromptsActivity.this.getResourceProvider();
            }
        };
        input.setTextSize(16); input.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        input.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        input.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setCursorSize(dp(20)); input.setCursorWidth(1.5f);
        input.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        input.setHint(hint); input.setText(value); input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.setSingleLine(!multiline);
        input.setMinHeight(dp(multiline ? 200 : 48));
        input.setPadding(dp(21), dp(12), dp(21), dp(16));
        content.addView(input, LayoutHelper.createLinear(-1, -2));
        describe(input, ThemeDescription.FLAG_BACKGROUND, Theme.key_windowBackgroundWhite);
        describe(input, ThemeDescription.FLAG_TEXTCOLOR, Theme.key_windowBackgroundWhiteBlackText);
        describe(input, ThemeDescription.FLAG_HINTTEXTCOLOR, Theme.key_windowBackgroundWhiteHintText);
        themeDescriptions.add(new ThemeDescription(input, 0, null, null, null,
                () -> input.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText)),
                Theme.key_windowBackgroundWhiteHintText));
        describe(input, ThemeDescription.FLAG_CURSORCOLOR, Theme.key_windowBackgroundWhiteBlackText);
        return input;
    }

    private void save() {
        if (!active() || loading || !editing) return;
        String name = nameInput.getText().toString();
        String body = textInput.getText().toString();
        try { SavedSummaryPrompts.validate(name, body); }
        catch (RuntimeException failure) { showSaveError(SummaryHistoryActivity.failureMessage(failure)); return; }
        final SavedSummaryPrompts.Entry existing = editingEntry;
        final int request = ++operation;
        loading = true; saving = true; editorError.setText("正在保存…");
        saveButton.setEnabled(false); saveButton.setAlpha(.5f);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (existing == null) SavedSummaryPrompts.create(account, ownerId, name, body);
                else SavedSummaryPrompts.update(account, ownerId, existing.id, existing.revision, name, body);
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !sameOwner()) return;
                    loading = false; saving = false; editing = false; editingEntry = null; initialText = "";
                    AndroidUtilities.hideKeyboard(textInput);
                    if (resumed) load();
                });
            } catch (RuntimeException failure) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !sameOwner()) return;
                    loading = false; saving = false;
                    saveButton.setEnabled(true); saveButton.setAlpha(1f);
                    showSaveError(SummaryHistoryActivity.failureMessage(failure));
                });
            }
        });
    }

    private void showSaveError(String message) {
        editorError.setText(message);
        final View errorView = editorError;
        errorView.post(() -> {
            if (sameOwner() && editing && editorError == errorView && errorView.getParent() != null) {
                errorView.requestRectangleOnScreen(new Rect(0, 0, errorView.getWidth(), errorView.getHeight()), false);
            }
        });
    }

    private void leaveEditor() {
        if (!active() || loading) return;
        String previousName = editingEntry == null ? "" : editingEntry.name;
        String previousText = editingEntry == null ? initialText : editingEntry.text;
        if (nameInput.getText().toString().equals(previousName) && textInput.getText().toString().equals(previousText)) {
            closeEditor(); return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("放弃未保存的修改？")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃", (dialog, which) -> {
                    if (active()) closeEditor();
                }).create());
    }
    private void closeEditor() {
        AndroidUtilities.hideKeyboard(textInput); editing = false; editingEntry = null; load();
    }
    private void confirmDelete(SavedSummaryPrompts.Entry entry) {
        if (!active() || loading) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("删除我的要求")
                .setMessage("删除「" + entry.name + "」？已经生成的总结和已填入的任务要求不受影响。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    if (!active()) return;
                    final int request = ++operation; loading = true; showList();
                    Utilities.globalQueue.postRunnable(() -> {
                        try {
                            SavedSummaryPrompts.delete(account, ownerId, entry.id);
                            AndroidUtilities.runOnUIThread(() -> { if (request == operation && active()) { loading = false; load(); } });
                        } catch (RuntimeException failure) {
                            AndroidUtilities.runOnUIThread(() -> {
                                if (request != operation || !active()) return;
                                loading = false; error = SummaryHistoryActivity.failureMessage(failure); showList();
                            });
                        }
                    });
                }).create());
    }

    private TextView body(String value, boolean bold) {
        TextView view = new TextView(content.getContext());
        view.setText(value); view.setTextSize(bold ? 16 : 14); view.setLineSpacing(dp(3), 1f);
        int color = bold ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText;
        view.setTextColor(getThemedColor(color));
        view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        if (bold) view.setTypeface(AndroidUtilities.bold());
        content.addView(view, LayoutHelper.createLinear(-1, -2));
        describe(view, ThemeDescription.FLAG_TEXTCOLOR, color);
        describe(view, ThemeDescription.FLAG_BACKGROUND, Theme.key_windowBackgroundWhite);
        return view;
    }
    private void header(String value) {
        HeaderCell cell = new HeaderCell(content.getContext(), 21, getResourceProvider());
        cell.setText(value);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
        describe(cell, ThemeDescription.FLAG_BACKGROUND, Theme.key_windowBackgroundWhite);
        themeDescriptions.add(new ThemeDescription(cell, 0, new Class[]{HeaderCell.class}, new String[]{"textView"},
                null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
    }
    private TextInfoPrivacyCell info(String value) {
        TextInfoPrivacyCell cell = new TextInfoPrivacyCell(content.getContext(), getResourceProvider());
        cell.setText(value);
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
        describe(cell.getTextView(), ThemeDescription.FLAG_TEXTCOLOR, Theme.key_windowBackgroundWhiteGrayText4);
        return cell;
    }
    private void action(String label, Runnable run) {
        TextCell cell = actionCell(label, false, run);
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
    }
    private void addRowAction(LinearLayout row, String label, boolean destructive, Runnable run) {
        row.addView(actionCell(label, destructive, run), LayoutHelper.createLinear(0, -2, 1f));
    }
    private TextCell actionCell(String label, boolean destructive, Runnable run) {
        TextCell cell = new TextCell(content.getContext(), getResourceProvider());
        int color = destructive ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlueText;
        cell.setText(label, false);
        cell.setTextColor(getThemedColor(color));
        cell.setBackground(Theme.createSelectorWithBackgroundDrawable(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
        cell.setFocusable(true); cell.setOnClickListener(v -> { if (active()) run.run(); });
        ThemeDescription.ThemeDescriptionDelegate updateColors = () -> {
            cell.setTextColor(getThemedColor(color));
            cell.setBackground(Theme.createSelectorWithBackgroundDrawable(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
        };
        for (int key : new int[]{color, Theme.key_windowBackgroundWhite, Theme.key_listSelector}) {
            themeDescriptions.add(new ThemeDescription(cell, 0, null, null, null, updateColors, key));
        }
        return cell;
    }
    private void describe(View view, int flag, int color) {
        themeDescriptions.add(new ThemeDescription(view, flag, null, null, null, null, color));
    }
    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> result = new ArrayList<>(themeDescriptions);
        result.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        result.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        result.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        result.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        result.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        return result;
    }
    private static int dp(float value) { return AndroidUtilities.dp(value); }
}
