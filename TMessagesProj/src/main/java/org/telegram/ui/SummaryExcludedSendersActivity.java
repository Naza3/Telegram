/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.SummaryExcludedSendersStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Set;

/** Explicit per-group UID editing; never resolves usernames or requests contacts/member access. */
public final class SummaryExcludedSendersActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    public interface Listener { void onSaved(Set<Long> ids); }
    private final int account;
    private final long ownerId;
    private final long dialogId;
    private Listener listener;
    private EditText input;
    private TextView status;
    private TextView saveButton;
    private TextView retryButton;
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
        actionBar.setTitle("本群排除 UID");
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        text(content, "在用户主页点按复制 Telegram UID，再粘贴到这里。UID 是 Telegram 官方用户标识，改名或修改 @用户名不会改变它。", false);
        text(content, "仅从本群的总结输入中排除这些 UID 的发言，适用于本群全部话题；不会删除聊天消息，也不会封禁用户。导出记录保持原有范围和筛选规则。", false);
        text(content, "每行一个 UID，也可用逗号或空格分隔；最多 " + SummaryExcludedSendersStore.MAX_UIDS + " 个。留空并保存即可清空。", false);
        input = new EditText(context);
        input.setTextSize(16);
        input.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        input.setHint("123456789\n987654321");
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinHeight(dp(180));
        input.setPadding(dp(8), dp(12), dp(8), dp(12));
        input.setEnabled(false);
        content.addView(input, LayoutHelper.createLinear(-1, -2));
        status = text(content, "正在读取…", false);
        saveButton = text(content, "保存本群名单", true);
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(view -> save());
        retryButton = text(content, "重新读取", true);
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(view -> load());
        TextView cancel = text(content, "取消", true);
        cancel.setOnClickListener(view -> finishFragment());
        text(content, "按实际发言者 UID 精确匹配，不按昵称判断；匿名管理员或以频道身份发言时，无法据此识别背后的个人 UID。名单按账号和群在本机加密保存。", false);
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
        catch (RuntimeException error) { status.setText(SummaryHistoryActivity.failureMessage(error)); return; }
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
                    saving = false; setEditable(true); status.setText(SummaryHistoryActivity.failureMessage(error));
                    retryButton.setVisibility(View.VISIBLE);
                });
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
    private TextView text(LinearLayout content, String value, boolean action) {
        TextView view = new TextView(content.getContext());
        view.setText(value); view.setTextSize(action ? 16 : 14);
        view.setTextColor(getThemedColor(action ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText));
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setPadding(0, dp(12), 0, dp(12));
        if (action) view.setMinHeight(dp(48));
        content.addView(view, LayoutHelper.createLinear(-1, -2));
        return view;
    }
    private int dp(float value) { return AndroidUtilities.dp(value); }
}
