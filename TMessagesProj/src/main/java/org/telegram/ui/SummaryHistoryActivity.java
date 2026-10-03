/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.SummaryHistoryStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Account-bound, encrypted-on-disk summary history. All store work runs off the UI thread. */
public final class SummaryHistoryActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int REFRESH = 1;
    private static final int CLEAR = 2;
    private final int account;
    private final long ownerId;
    private final long dialogId;
    private final long topicId;
    private final ArrayList<SummaryHistoryStore.Record> records = new ArrayList<>();
    private LinearLayout content;
    private boolean resumed;
    private boolean destroyed;
    private boolean accountInvalidated;
    private int operation;
    private boolean loading = true;
    private String error;

    public SummaryHistoryActivity(int account) {
        this(account, 0, -1);
    }

    public SummaryHistoryActivity(int account, long dialogId, long topicId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || dialogId > 0
                || dialogId == Long.MIN_VALUE || topicId < -1 || topicId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("历史记录范围无效。");
        }
        this.account = account;
        this.dialogId = dialogId;
        this.topicId = dialogId == 0 ? -1 : topicId;
        setCurrentAccount(account);
        ownerId = getUserConfig().getClientUserId();
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate() || !sameOwner()) return false;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(dialogId == 0 ? "AI 总结历史" : topicId > 0 ? "话题总结历史" : "聊天总结历史");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == REFRESH && active()) reload(null);
                else if (id == CLEAR && active() && !loading) confirmClear();
            }
        });
        ActionBarMenuItem menu = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        menu.setContentDescription("更多选项");
        menu.addSubItem(REFRESH, R.drawable.msg_retry, "刷新");
        menu.addSubItem(CLEAR, R.drawable.msg_delete, "清空当前范围");

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        render();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (!sameOwner()) invalidateAccount();
        else reload(null);
    }

    @Override
    public void onPause() {
        resumed = false;
        operation++;
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        resumed = false;
        operation++;
        records.clear();
        if (content != null) content.removeAllViews();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (id == NotificationCenter.activeAccountChanged && UserConfig.selectedAccount != account
                || id == NotificationCenter.appDidLogout && changedAccount == account) {
            invalidateAccount();
        }
    }

    private boolean sameOwner() {
        return !destroyed && !accountInvalidated && !isFinished && currentAccount == account
                && ownerId != 0 && UserConfig.selectedAccount == account
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    private boolean active() {
        if (!sameOwner()) {
            invalidateAccount();
            return false;
        }
        return resumed && getParentActivity() != null && !getParentActivity().isFinishing();
    }

    private void invalidateAccount() {
        if (destroyed) return;
        accountInvalidated = true;
        operation++;
        loading = false;
        error = "账号已切换或退出，请从当前账号重新打开总结历史。";
        records.clear();
        dismissCurrentDialog();
        render();
    }

    private void reload(Runnable mutation) {
        if (!active()) return;
        final int request = ++operation;
        loading = true;
        error = null;
        records.clear();
        render();
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner()) return;
            try {
                if (mutation != null) mutation.run();
                List<SummaryHistoryStore.Record> loaded = SummaryHistoryStore.list(account, ownerId, dialogId, topicId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    loading = false;
                    records.addAll(loaded);
                    render();
                });
            } catch (RuntimeException failure) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    loading = false;
                    error = "无法读取或更新总结历史：" + failureMessage(failure);
                    render();
                });
            }
        });
    }

    private void confirmClear() {
        String scope = dialogId == 0 ? "当前账号的全部总结历史" : topicId == -1 ? "本聊天及所有话题的总结历史"
                : topicId == 0 ? "本聊天中不限定话题的总结历史" : "当前话题的总结历史";
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle("清空总结历史")
                .setMessage("确定删除" + scope + "？此操作不会删除 Telegram 消息，删除的历史记录无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    if (active()) reload(() -> SummaryHistoryStore.clear(account, ownerId, dialogId, topicId));
                }).create());
    }

    private void confirmDelete(SummaryHistoryStore.Record record) {
        if (!active() || loading) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle("删除这条总结")
                .setMessage(chatLabel(record) + "\n" + formatTime(record.generatedAtMillis)
                        + "\n\n仅删除本机保存的这条总结，Telegram 消息不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (active()) reload(() -> SummaryHistoryStore.delete(account, ownerId, record.id));
                }).create());
    }

    private void render() {
        if (content == null || destroyed) return;
        content.removeAllViews();
        text(content, "历史结果只保存在本机并加密，重启后可查看；最多保留 100 条、8 MiB。"
                + "记录不包含 API Key 或原消息正文。", 14, false);
        if (loading) {
            text(content, "正在读取总结历史…", 16, true);
            return;
        }
        if (error != null) {
            text(content, error, 16, true);
            if (!accountInvalidated) {
                TextView retry = text(content, "重试", 16, true);
                retry.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
                retry.setOnClickListener(view -> reload(null));
            }
            return;
        }
        if (records.isEmpty()) {
            text(content, "当前范围还没有保存的总结。完成一次总结后，会自动保存到这里。", 16, true);
            return;
        }
        text(content, records.size() + " 条记录 · 点击查看详情，长按删除", 14, false);
        for (SummaryHistoryStore.Record record : records) {
            LinearLayout row = new LinearLayout(content.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(8), dp(14), dp(12));
            row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8),
                    getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
            text(row, chatLabel(record), 16, true);
            text(row, formatTime(record.generatedAtMillis) + " · " + record.sources.size() + " 条原文", 13, false);
            text(row, record.rangeLabel + (record.partial ? " · 部分覆盖" : ""), 14, false);
            text(row, excerpt(record.summary, 160), 14, false);
            row.setFocusable(true);
            row.setOnClickListener(view -> {
                if (active() && !loading) presentFragment(new SummaryHistoryDetailActivity(account, record.id));
            });
            row.setOnLongClickListener(view -> {
                confirmDelete(record);
                return true;
            });
            content.addView(row, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
        }
    }

    private TextView text(LinearLayout parent, CharSequence value, int size, boolean bold) {
        TextView view = new TextView(parent.getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getThemedColor(bold ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
        view.setGravity(Gravity.START);
        view.setLineSpacing(dp(2), 1f);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        parent.addView(view, LayoutHelper.createLinear(-1, -2, 0, 6, 0, 4));
        return view;
    }

    static String chatLabel(SummaryHistoryStore.Record record) {
        String title = record.chatTitle == null || record.chatTitle.isEmpty() ? "聊天 " + record.dialogId : record.chatTitle;
        return title + (record.topicId == 0 ? " · 整个聊天" : " · 话题 " + record.topicId);
    }

    static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    static String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? "本机存储暂不可用，请稍后重试。" : message;
    }

    private static String excerpt(String text, int limit) {
        return text.codePointCount(0, text.length()) <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit)) + "…";
    }

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }
}
