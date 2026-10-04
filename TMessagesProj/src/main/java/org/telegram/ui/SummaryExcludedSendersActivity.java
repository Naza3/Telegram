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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.SummaryExcludedSendersStore;
import org.telegram.ui.ActionBar.ActionBar;
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
import java.util.Set;

/** Explicit per-group UID editing; never resolves usernames or requests contacts/member access. */
public final class SummaryExcludedSendersActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    public interface Listener { void onSaved(Set<Long> ids); }
    private final int account;
    private final long ownerId;
    private final long dialogId;
    private Listener listener;
    private EditText input;
    private TextInfoPrivacyCell status;
    private View saveButton;
    private TextCell retryButton;
    private final ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
    private boolean resumed;
    private boolean loading;
    private boolean saving;
    private boolean loaded;
    private volatile boolean destroyed;
    private volatile boolean invalidated;
    private volatile int operation;
    private SummaryExcludedSendersStore.Snapshot snapshot;
    private SummaryExcludedSendersStore.Snapshot saved;

    public SummaryExcludedSendersActivity(int account, long ownerId, long dialogId, Listener listener) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || dialogId >= 0 || dialogId == Long.MIN_VALUE) throw new IllegalArgumentException("排除名单的账号或来源群无效。");
        this.account = account; this.ownerId = ownerId; this.dialogId = dialogId; this.listener = listener;
        setCurrentAccount(account);
    }

    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate() || !sameOwner()) return false;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        return true;
    }

    @Override public View createView(Context context) {
        themeDescriptions.clear();
        actionBar.setTitle("本群排除 UID");
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == 1) save();
            }
        });
        saveButton = actionBar.createMenu().addItemWithWidth(1, R.drawable.ic_ab_done, dp(56), "保存本群名单");
        saveButton.setEnabled(false);
        saveButton.setAlpha(.5f);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(24));
        content.setFocusableInTouchMode(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        HeaderCell header = new HeaderCell(context, 21, getResourceProvider());
        header.setText("排除的 Telegram UID");
        header.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(header, LayoutHelper.createLinear(-1, -2));
        describe(header, ThemeDescription.FLAG_BACKGROUND, Theme.key_windowBackgroundWhite);
        themeDescriptions.add(new ThemeDescription(header, 0, new Class[]{HeaderCell.class}, new String[]{"textView"},
                null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        EditTextBoldCursor editText = new EditTextBoldCursor(context) {
            @Override protected Theme.ResourcesProvider getResourcesProvider() {
                return SummaryExcludedSendersActivity.this.getResourceProvider();
            }
        };
        input = editText;
        input.setTextSize(16);
        input.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(dp(20)); editText.setCursorWidth(1.5f);
        input.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        input.setHint("123456789\n987654321");
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinHeight(dp(180));
        input.setPadding(dp(21), dp(12), dp(21), dp(16));
        input.setEnabled(false);
        content.addView(input, LayoutHelper.createLinear(-1, -2));
        describe(input, ThemeDescription.FLAG_BACKGROUND, Theme.key_windowBackgroundWhite);
        describe(input, ThemeDescription.FLAG_TEXTCOLOR, Theme.key_windowBackgroundWhiteBlackText);
        describe(input, ThemeDescription.FLAG_HINTTEXTCOLOR, Theme.key_windowBackgroundWhiteHintText);
        themeDescriptions.add(new ThemeDescription(input, 0, null, null, null,
                () -> editText.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText)),
                Theme.key_windowBackgroundWhiteHintText));
        describe(input, ThemeDescription.FLAG_CURSORCOLOR, Theme.key_windowBackgroundWhiteBlackText);
        info(content, "每行一个 UID，也可用逗号或空格分隔，最多 " + SummaryExcludedSendersStore.MAX_UIDS + " 个。留空并保存即可清空。"
                + "\n在用户主页点按复制 UID，再粘贴到这里。改名或修改 @用户名不会改变 UID。");
        status = info(content, "正在读取…");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        retryButton = new TextCell(context, getResourceProvider());
        retryButton.setText("重新读取", false);
        retryButton.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        retryButton.setBackground(Theme.createSelectorWithBackgroundDrawable(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
        retryButton.setFocusable(true);
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(view -> load());
        content.addView(retryButton, LayoutHelper.createLinear(-1, -2));
        ThemeDescription.ThemeDescriptionDelegate updateRetry = () -> {
            retryButton.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
            retryButton.setBackground(Theme.createSelectorWithBackgroundDrawable(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
        };
        for (int color : new int[]{Theme.key_windowBackgroundWhiteBlueText, Theme.key_windowBackgroundWhite, Theme.key_listSelector}) {
            themeDescriptions.add(new ThemeDescription(retryButton, 0, null, null, null, updateRetry, color));
        }
        info(content, "名单用于本群全部话题的 AI 总结，不会删除消息或封禁用户。导出记录仍按原有范围和规则筛选。"
                + "\n仅匹配实际发言者 UID；匿名管理员、频道身份背后的个人 UID 无法识别。名单按账号和群在本机加密保存。");
        fragmentView = scroll;
        return scroll;
    }

    @Override public void onResume() {
        super.onResume(); resumed = true;
        if (!sameOwner()) invalidate();
        else if (saved != null) deliverSaved();
        else if (!loaded && !loading) load();
    }
    @Override public void onPause() { resumed = false; super.onPause(); }
    @Override public void onFragmentDestroy() {
        destroyed = true; operation++; resumed = false; listener = null; snapshot = null; saved = null;
        if (input != null) input.setText("");
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        super.onFragmentDestroy();
    }
    @Override public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (id == NotificationCenter.activeAccountChanged && UserConfig.selectedAccount != account
                || id == NotificationCenter.appDidLogout && changedAccount == account) invalidate();
    }

    private void load() {
        if (!active() || loading || saving) return;
        final int token = ++operation;
        loading = true; loaded = false;
        setEditable(false); status.setText("正在读取…"); retryButton.setVisibility(View.GONE);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner() || token != operation) return;
            try {
                SummaryExcludedSendersStore.Snapshot value = SummaryExcludedSendersStore.load(account, ownerId, dialogId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!sameOwner() || token != operation) return;
                    snapshot = value; loading = false; loaded = true;
                    input.setText(SummaryExcludedSendersStore.format(value.ids));
                    status.setText("已保存 " + value.ids.size() + " 个 UID。"); setEditable(true);
                });
            } catch (RuntimeException error) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!sameOwner() || token != operation) return;
                    loading = false; status.setText(SummaryHistoryActivity.failureMessage(error));
                    retryButton.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void save() {
        if (!active() || !loaded || loading || saving || snapshot == null) return;
        final Set<Long> ids;
        try { ids = SummaryExcludedSendersStore.parse(input.getText().toString()); }
        catch (RuntimeException error) { showSaveError(SummaryHistoryActivity.failureMessage(error)); return; }
        final long revision = snapshot.revision;
        final int token = ++operation;
        saving = true; setEditable(false); status.setText("正在保存…");
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner() || token != operation) return;
            try {
                SummaryExcludedSendersStore.Snapshot value = SummaryExcludedSendersStore.save(account, ownerId, dialogId, revision, ids);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!sameOwner() || token != operation) return;
                    saving = false; saved = value; snapshot = value;
                    status.setText("已保存 " + value.ids.size() + " 个 UID。");
                    if (resumed) deliverSaved();
                });
            } catch (RuntimeException error) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!sameOwner() || token != operation) return;
                    saving = false; setEditable(true); showSaveError(SummaryHistoryActivity.failureMessage(error));
                    retryButton.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void showSaveError(String message) {
        status.setText(message);
        final View errorView = status;
        errorView.post(() -> {
            if (sameOwner() && status == errorView && errorView.getParent() != null) {
                errorView.requestRectangleOnScreen(new Rect(0, 0, errorView.getWidth(), errorView.getHeight()), false);
            }
        });
    }

    private void deliverSaved() {
        if (!active() || saved == null) return;
        Set<Long> ids = saved.ids; saved = null;
        Listener callback = listener; listener = null;
        if (callback != null) callback.onSaved(ids);
        finishFragment();
    }
    private boolean sameOwner() {
        return !destroyed && !invalidated && UserConfig.selectedAccount == account
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }
    private boolean active() {
        if (!sameOwner()) { invalidate(); return false; }
        return resumed && getParentActivity() != null && !getParentActivity().isFinishing();
    }
    private void invalidate() {
        if (destroyed || invalidated) return;
        invalidated = true; operation++; listener = null; snapshot = null; saved = null;
        if (input != null) input.setText("");
        setEditable(false);
        if (status != null) status.setText("账号已切换或退出，请从当前账号重新打开排除名单。");
        if (retryButton != null) retryButton.setVisibility(View.GONE);
    }
    private void setEditable(boolean enabled) {
        if (input != null) input.setEnabled(enabled);
        if (saveButton != null) { saveButton.setEnabled(enabled); saveButton.setAlpha(enabled ? 1f : .5f); }
    }
    private TextInfoPrivacyCell info(LinearLayout content, String value) {
        TextInfoPrivacyCell cell = new TextInfoPrivacyCell(content.getContext(), getResourceProvider());
        cell.setText(value);
        content.addView(cell, LayoutHelper.createLinear(-1, -2));
        describe(cell.getTextView(), ThemeDescription.FLAG_TEXTCOLOR, Theme.key_windowBackgroundWhiteGrayText4);
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
    private int dp(float value) { return AndroidUtilities.dp(value); }
}
