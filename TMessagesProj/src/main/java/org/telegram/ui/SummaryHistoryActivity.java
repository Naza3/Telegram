/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ai.SummaryHistoryStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Standalone scoped entry; the center embeds the same lifecycle-aware history panel. */
public final class SummaryHistoryActivity extends BaseFragment {
    private final int account;
    private final long ownerId;
    private final long dialogId;
    private final long topicId;
    private SummaryHistoryPanel panel;

    public SummaryHistoryActivity(int account) { this(account, 0, -1); }

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

    @Override public boolean onFragmentCreate() {
        return super.onFragmentCreate() && ownerId > 0 && UserConfig.selectedAccount == account
                && getUserConfig().getClientUserId() == ownerId;
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(dialogId == 0 ? "AI 总结历史" : topicId > 0 ? "话题总结历史" : "聊天总结历史");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (panel != null && id == 1) panel.refresh();
                else if (panel != null && id == 2) panel.confirmClear();
            }
        });
        ActionBarMenuItem menu = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        menu.setContentDescription("更多选项");
        menu.addSubItem(1, R.drawable.msg_retry, "刷新");
        menu.addSubItem(2, R.drawable.msg_delete, "清空当前范围");
        if (panel != null) panel.destroy();
        panel = new SummaryHistoryPanel(this, account, ownerId, dialogId, topicId);
        return fragmentView = panel.createView(context);
    }

    @Override public void onResume() { super.onResume(); if (panel != null) panel.onResume(); }
    @Override public void onPause() { if (panel != null) panel.onPause(); super.onPause(); }
    @Override public void onFragmentDestroy() { if (panel != null) panel.destroy(); super.onFragmentDestroy(); }

    static String chatLabel(SummaryHistoryStore.Record record) {
        String title = record.chatTitle.isEmpty() ? "聊天 " + record.dialogId : record.chatTitle;
        return title + (record.topicId == 0 ? " · 整个聊天" : " · 话题 " + record.topicId);
    }

    static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    static String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? "本机存储暂不可用，请稍后重试。" : message;
    }
}
