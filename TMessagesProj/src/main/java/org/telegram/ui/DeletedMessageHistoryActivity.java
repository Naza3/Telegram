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
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
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
    private TextInfoPrivacyCell status;
    private TextSettingsCell recoveryButton;
    private RecyclerView listView;
    private final ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
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
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        themeDescriptions.clear();
        status = new TextInfoPrivacyCell(context, getResourceProvider());
        status.setText(sameOwner() ? "正在读取…" : "账号已切换或退出，请重新打开撤回记录。");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, LayoutHelper.createLinear(-1, -2));
        recoveryButton = new TextSettingsCell(context, getResourceProvider());
        recoveryButton.setText("重置本账号撤回记录", false);
        recoveryButton.setTextColor(getThemedColor(Theme.key_text_RedRegular));
        recoveryButton.setBackground(Theme.createSelectorWithBackgroundDrawable(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
        recoveryButton.setVisibility(View.GONE); recoveryButton.setOnClickListener(view -> confirmResetOwner());
        root.addView(recoveryButton, LayoutHelper.createLinear(-1, -2));
        listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new HistoryAdapter());
        listView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(View view) {
                RecyclerView.ViewHolder holder = listView.getChildViewHolder(view);
                if (holder instanceof Holder) ((Holder) holder).updateColors();
            }
            @Override public void onChildViewDetachedFromWindow(View view) { }
        });
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(16));
        root.addView(listView, LayoutHelper.createLinear(-1, 0, 1));
        themeDescriptions.add(new ThemeDescription(root, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SUBTITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultSubtitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(status, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        themeDescriptions.add(new ThemeDescription(recoveryButton, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_text_RedRegular));
        themeDescriptions.add(new ThemeDescription(recoveryButton, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(recoveryButton, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
        for (int key : new int[]{Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhiteBlackText,
                Theme.key_windowBackgroundWhiteGrayText, Theme.key_listSelector, Theme.key_divider}) {
            themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, this::updateRows, key));
        }
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
    private void updateRows() {
        if (listView == null) return;
        for (int i = 0; i < listView.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = listView.getChildViewHolder(listView.getChildAt(i));
            if (holder instanceof Holder) ((Holder) holder).updateColors();
        }
    }
    @Override public ArrayList<ThemeDescription> getThemeDescriptions() { return themeDescriptions; }
    private final class HistoryAdapter extends RecyclerView.Adapter<Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            row.setFocusable(true);
            LinearLayout content = new LinearLayout(parent.getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(21), dp(14), dp(21), dp(14));
            TextView title = new TextView(parent.getContext());
            title.setTextSize(16); title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.START); title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView body = new TextView(parent.getContext());
            body.setTextSize(16); body.setGravity(Gravity.START); body.setMaxLines(6);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView time = new TextView(parent.getContext());
            time.setTextSize(13); time.setGravity(Gravity.START);
            content.addView(title, LayoutHelper.createLinear(-1, -2));
            content.addView(body, LayoutHelper.createLinear(-1, -2, 0, 6, 0, 0));
            content.addView(time, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
            row.addView(content, LayoutHelper.createLinear(-1, -2));
            View divider = new View(parent.getContext());
            row.addView(divider, LayoutHelper.createLinear(-1, 1, 21, 0, 21, 0));
            return new Holder(row, title, body, time, divider);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            DeletedMessageRecord record = records.get(position);
            holder.title.setText(sender(record));
            holder.body.setText(record.text.length() > 800 ? record.text.substring(0, 800) + "…" : record.text);
            holder.time.setText(metadata(record));
            holder.divider.setVisibility(position == records.size() - 1 ? View.GONE : View.VISIBLE);
            holder.updateColors();
            holder.itemView.setOnClickListener(view -> open(record));
        }
        @Override public int getItemCount() { return records.size(); }
    }
    private final class Holder extends RecyclerView.ViewHolder {
        final TextView title, body, time;
        final View divider;
        Holder(View view, TextView title, TextView body, TextView time, View divider) {
            super(view); this.title = title; this.body = body; this.time = time; this.divider = divider;
        }
        void updateColors() {
            itemView.setBackground(Theme.createSelectorWithBackgroundDrawable(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
            title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            body.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            time.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            divider.setBackgroundColor(getThemedColor(Theme.key_divider));
        }
    }
    private static int dp(float value) { return AndroidUtilities.dp(value); }
}
