/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.groupmessages.DeletedGroupMessages;
import org.telegram.messenger.groupmessages.DeletedMessageRecord;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** A separate, owner-bound local archive; records never reappear as live Telegram messages. */
public final class DeletedMessageHistoryActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int CLEAR = 1;
    private final int account;
    private final long ownerId, dialogId;
    private final ArrayList<DeletedMessageRecord> records = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private TextView status, recoveryButton;
    private HistoryAdapter adapter;
    private boolean resumed, destroyed, invalidated, clearing;
    private int operation;

    public DeletedMessageHistoryActivity(int account, long ownerId, long dialogId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0 || dialogId >= 0 || dialogId == Long.MIN_VALUE) {
            throw new IllegalArgumentException("请从有效的群查看撤回记录。");
        }
        this.account = account; this.ownerId = ownerId; this.dialogId = dialogId;
        setCurrentAccount(account);
    }
    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate() || !sameOwner()) return false;
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (chat == null || ChatObject.isChannel(chat) && !chat.megagroup) return false;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        return true;
    }
    @Override public View createView(Context context) {
        actionBar.setTitle("本群撤回记录");
        TLRPC.Chat chat = sameOwner() ? getMessagesController().getChat(-dialogId) : null;
        actionBar.setSubtitle(chat == null ? null : chat.title);
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.createMenu().addItem(CLEAR, "清理");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == CLEAR) confirmClear();
            }
        });
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        status = new TextView(context);
        status.setTextSize(14); status.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        status.setPadding(dp(20), dp(12), dp(20), dp(12));
        status.setText(sameOwner() ? "正在读取…" : "账号已切换或退出，请重新打开撤回记录。");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, LayoutHelper.createLinear(-1, -2));
        recoveryButton = new TextView(context);
        recoveryButton.setText("重置本账号撤回记录"); recoveryButton.setTextSize(16);
        recoveryButton.setTextColor(getThemedColor(Theme.key_text_RedRegular));
        recoveryButton.setPadding(dp(20), dp(12), dp(20), dp(12)); recoveryButton.setMinHeight(dp(48));
        recoveryButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        recoveryButton.setVisibility(View.GONE); recoveryButton.setOnClickListener(view -> confirmResetOwner());
        root.addView(recoveryButton, LayoutHelper.createLinear(-1, -2));
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter = new HistoryAdapter());
        root.addView(list, LayoutHelper.createLinear(-1, 0, 1));
        fragmentView = root;
        return root;
    }
    @Override public void onResume() {
        super.onResume(); resumed = true;
        if (!sameOwner()) invalidate(); else if (!clearing) load();
    }
    @Override public void onPause() { resumed = false; super.onPause(); }
    @Override public void onFragmentDestroy() {
        destroyed = true; resumed = false; operation++; records.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        super.onFragmentDestroy();
    }
    @Override public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (id == NotificationCenter.activeAccountChanged && UserConfig.selectedAccount != account
                || id == NotificationCenter.appDidLogout && changedAccount == account) invalidate();
    }
    private void load() {
        if (!active()) return;
        int token = ++operation;
        status.setText("正在读取…");
        DeletedGroupMessages.getRecords(account, ownerId, dialogId, (values, failed) -> {
            if (!sameOwner() || token != operation) return;
            records.clear();
            if (!failed) for (DeletedMessageRecord value : values) {
                if (value.ownerId == ownerId && value.dialogId == dialogId) records.add(value);
            }
            adapter.notifyDataSetChanged();
            recoveryButton.setVisibility(failed ? View.VISIBLE : View.GONE);
            status.setText(failed ? "读取失败，请返回后重试。" : records.isEmpty()
                    ? "暂无记录。开启“保留撤回文字”并保存后，才会保留本机已缓存的普通群文字；未收到的内容无法恢复。"
                    : "本机保留 " + records.size() + " 条撤回文字。点按查看全文或复制。为控制占用，较旧记录可能自动清理。");
        });
    }
    private void confirmClear() {
        if (!active() || clearing) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("清理本群撤回记录？")
                .setMessage("本机保存的本群撤回文字将被删除。保留开关开启时，后续撤回仍可产生新记录。")
                .setNegativeButton("取消", null).setPositiveButton("清理", (dialog, which) -> {
                    if (!active() || clearing) return;
                    clearing = true; ++operation; status.setText("正在清理…");
                    DeletedGroupMessages.clear(account, ownerId, dialogId, failed -> {
                        if (!sameOwner()) return;
                        clearing = false;
                        if (failed) { status.setText("清理失败，请重试。"); recoveryButton.setVisibility(View.VISIBLE); return; }
                        recoveryButton.setVisibility(View.GONE);
                        records.clear(); adapter.notifyDataSetChanged(); status.setText("本群撤回记录已清理。");
                    });
                }).create());
    }
    private void confirmResetOwner() {
        if (!active() || clearing) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("重置本账号全部撤回记录？")
                .setMessage("这会删除当前账号在本机保存的所有群撤回记录，无法恢复。仅在记录无法读取或清理时使用；各群保留开关不会改变。")
                .setNegativeButton("取消", null).setPositiveButton("删除全部记录", (dialog, which) -> {
                    if (!active() || clearing) return;
                    clearing = true; ++operation; recoveryButton.setEnabled(false); status.setText("正在重置…");
                    DeletedGroupMessages.clear(account, ownerId, 0, failed -> {
                        if (!sameOwner()) return;
                        clearing = false; recoveryButton.setEnabled(true);
                        if (failed) { status.setText("重置失败，请检查手机存储空间后重试。"); return; }
                        records.clear(); adapter.notifyDataSetChanged(); recoveryButton.setVisibility(View.GONE);
                        status.setText("本账号所有群的本机撤回记录已删除。");
                    });
                }).create());
    }
    private void open(DeletedMessageRecord record) {
        if (!active() || clearing || record.ownerId != ownerId || record.dialogId != dialogId) return;
        ScrollView scroll = new ScrollView(getParentActivity());
        TextView value = new TextView(getParentActivity());
        value.setText(record.text); value.setTextSize(16); value.setTextIsSelectable(true);
        value.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        value.setPadding(dp(20), dp(12), dp(20), dp(12)); scroll.addView(value);
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(sender(record)).setMessage(metadata(record)).setView(scroll)
                .setNegativeButton("关闭", null).setPositiveButton("复制原文", (dialog, which) -> {
                    if (!active()) return;
                    status.setText(AndroidUtilities.addToClipboard(record.text) ? "原文已复制。" : "复制失败，请重试。");
                }).create());
    }
    private String sender(DeletedMessageRecord value) {
        String name = value.senderName == null ? "" : value.senderName;
        if (name.isEmpty() && value.senderUserId > 0) {
            TLRPC.User user = getMessagesController().getUser(value.senderUserId);
            if (user != null) name = UserObject.getUserName(user);
        }
        if (name.isEmpty()) name = "未知发言者";
        return value.senderUserId > 0 ? name + " · UID " + value.senderUserId : name;
    }
    private String metadata(DeletedMessageRecord value) {
        return "发送于 " + dateFormat.format(new Date(value.sentAt * 1000L))
                + "\n收到撤回通知 " + dateFormat.format(new Date(value.deletedAt * 1000L))
                + (value.topicId > 0 ? " · 话题 " + value.topicId : "");
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
        invalidated = true; operation++; records.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (status != null) status.setText("账号已切换或退出，请从当前账号重新打开撤回记录。");
        if (recoveryButton != null) recoveryButton.setVisibility(View.GONE);
        if (actionBar != null) actionBar.setSubtitle(null);
        dismissCurrentDialog();
    }
    private final class HistoryAdapter extends RecyclerView.Adapter<Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(20), dp(12), dp(20), dp(12));
            row.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            TextView title = new TextView(parent.getContext());
            title.setTextSize(14); title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            TextView body = new TextView(parent.getContext());
            body.setTextSize(16); body.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            body.setPadding(0, dp(8), 0, 0); body.setGravity(Gravity.START); body.setMaxLines(6);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(title, LayoutHelper.createLinear(-1, -2)); row.addView(body, LayoutHelper.createLinear(-1, -2));
            return new Holder(row, title, body);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            DeletedMessageRecord record = records.get(position);
            holder.title.setText(sender(record) + "\n" + metadata(record));
            holder.body.setText(record.text.length() > 800 ? record.text.substring(0, 800) + "…" : record.text);
            holder.itemView.setOnClickListener(view -> open(record));
        }
        @Override public int getItemCount() { return records.size(); }
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView title, body;
        Holder(View view, TextView title, TextView body) { super(view); this.title = title; this.body = body; }
    }
    private static int dp(float value) { return AndroidUtilities.dp(value); }
}
