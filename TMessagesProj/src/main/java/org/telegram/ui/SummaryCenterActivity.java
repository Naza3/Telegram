/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.ai.SummaryHistoryLoader;
import org.telegram.messenger.ai.SummaryResultCache;
import java.util.ArrayList;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.SummaryTaskCheckpoint;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.GroupSummarySheet;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SummaryTaskController;

/** Two-tab account-scoped workspace. Navigation never opens or marks the source chat read. */
public final class SummaryCenterActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int SETTINGS = 1;
    private final int account;
    private final long ownerId;
    private long dialogId, topicId;
    private int unreadLower = -1, unreadUpper = -1;
    private boolean entryUnreadSnapshot;
    private boolean resumed, destroyed, invalidated, historyVisible;
    private LinearLayout root, newPage, sourceHeader;
    private FrameLayout pageHost;
    private TextView newTab, historyTab, taskCard, checkpointCard;
    private ScrollView newScroll;
    private View historyView;
    private SummaryHistoryPanel historyPanel;
    private GroupSummarySheet workbench;
    private SummaryTaskController controller;
    private SummaryTaskCheckpoint.Record checkpoint;
    private final SummaryTaskController.Listener taskListener = task -> updateTaskCard();

    public SummaryCenterActivity(int account) { this(account, 0, 0, -1, -1, false); }

    public SummaryCenterActivity(int account, long dialogId, long topicId, int unreadLower,
            int unreadUpper, boolean entryUnreadSnapshot) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || dialogId > 0 || topicId < 0) {
            throw new IllegalArgumentException("总结来源无效。");
        }
        this.account = account;
        setCurrentAccount(account);
        ownerId = UserConfig.getInstance(account).getClientUserId();
        this.dialogId = dialogId; this.topicId = topicId;
        this.unreadLower = unreadLower; this.unreadUpper = unreadUpper;
        this.entryUnreadSnapshot = entryUnreadSnapshot;
    }

    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate() || !sameOwner()) return false;
        controller = SummaryTaskController.get(account);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().addObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().addObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        return true;
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("AI 总结");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == SETTINGS && sameOwner()) openSettings();
            }
        });
        actionBar.createMenu().addItem(SETTINGS, R.drawable.msg_settings).setContentDescription("MNN API 设置");
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        LinearLayout tabs = new LinearLayout(context);
        newTab = button(context, "新建", () -> showTab(false));
        historyTab = button(context, "历史", () -> showTab(true));
        tabs.addView(newTab, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(historyTab, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(tabs, LayoutHelper.createLinear(-1, -2));
        taskCard = button(context, "", this::openCurrentTask);
        root.addView(taskCard, LayoutHelper.createLinear(-1, -2, 12, 4, 12, 4));
        checkpointCard = button(context, "", this::showInterruptedTask);
        checkpointCard.setVisibility(View.GONE);
        root.addView(checkpointCard, LayoutHelper.createLinear(-1, -2, 12, 4, 12, 4));
        pageHost = new FrameLayout(context);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        newScroll = new ScrollView(context);
        newScroll.setFillViewport(true);
        newPage = new LinearLayout(context);
        newPage.setOrientation(LinearLayout.VERTICAL);
        newScroll.addView(newPage, new ScrollView.LayoutParams(-1, -2));
        pageHost.addView(newScroll, LayoutHelper.createFrame(-1, -1));
        historyPanel = new SummaryHistoryPanel(this, account, ownerId);
        historyView = historyPanel.createView(context);
        pageHost.addView(historyView, LayoutHelper.createFrame(-1, -1));
        fragmentView = root;
        buildWorkbench();
        showTab(historyVisible);
        updateTaskCard();
        readCheckpoint();
        return root;
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        if (!sameOwner()) { invalidateAccount(); return; }
        controller.addListener(taskListener);
        if (workbench != null) workbench.setAttached(!historyVisible);
        if (historyVisible && historyPanel != null) historyPanel.onResume();
        updateTaskCard();
    }

    @Override public void onPause() {
        resumed = false;
        if (controller != null) controller.removeListener(taskListener);
        if (workbench != null) workbench.setAttached(false);
        if (historyPanel != null) historyPanel.onPause();
        super.onPause();
    }

    @Override public void onFragmentDestroy() {
        destroyed = true;
        if (controller != null) controller.removeListener(taskListener);
        if (workbench != null) { workbench.dismiss(); workbench = null; }
        if (historyPanel != null) { historyPanel.destroy(); historyPanel = null; }
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().removeObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().removeObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }

    private boolean sameOwner() {
        return !destroyed && !invalidated && ownerId > 0 && UserConfig.selectedAccount == account
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    private void invalidateAccount() {
        invalidated = true;
        if (workbench != null) { workbench.dismiss(); workbench = null; }
        if (historyPanel != null) historyPanel.onPause();
        if (root != null) {
            root.removeAllViews();
            TextView back = button(root.getContext(), "账号已切换或退出，请返回当前账号重新打开总结中心。", this::finishFragment);
            back.setOnClickListener(view -> finishFragment());
            root.addView(back, LayoutHelper.createLinear(-1, -2));
        }
    }

    private void showTab(boolean history) {
        if (!sameOwner() || pageHost == null) return;
        historyVisible = history;
        newScroll.setVisibility(history ? View.GONE : View.VISIBLE);
        historyView.setVisibility(history ? View.VISIBLE : View.GONE);
        newTab.setAlpha(history ? 0.55f : 1);
        historyTab.setAlpha(history ? 1 : 0.55f);
        if (workbench != null) workbench.setAttached(!history && resumed);
        if (historyPanel != null) {
            if (history && resumed) historyPanel.onResume(); else historyPanel.onPause();
        }
    }

    private void buildWorkbench() {
        if (newPage == null || !sameOwner()) return;
        if (workbench != null) { workbench.dismiss(); workbench = null; }
        newPage.removeAllViews();
        sourceHeader = new LinearLayout(newPage.getContext());
        sourceHeader.setOrientation(LinearLayout.VERTICAL);
        sourceHeader.setPadding(dp(16), dp(8), dp(16), dp(8));
        newPage.addView(sourceHeader, LayoutHelper.createLinear(-1, -2));
        TLRPC.Chat chat = dialogId == 0 ? null : getMessagesController().getChat(-dialogId);
        sourceHeader.addView(button(newPage.getContext(), chat == null ? "选择来源群／频道" : "来源：" + chat.title + " · 更换",
                this::chooseSource), LayoutHelper.createLinear(-1, -2));
        if (chat == null) {
            TextView guide = label(newPage.getContext(), "先选择来源，再设置消息范围与手动核心总结要求。选择聊天不会打开聊天页或标记已读。");
            sourceHeader.addView(guide, LayoutHelper.createLinear(-1, -2, 0, 12, 0, 8));
            return;
        }
        if (ChatObject.isForum(chat)) {
            TLRPC.TL_forumTopic topic = topicId == 0 ? null : getMessagesController().getTopicsController().findTopic(chat.id, topicId);
            sourceHeader.addView(button(newPage.getContext(), topicId == 0 ? "全部话题 · 选择具体话题"
                    : "话题：" + (topic == null ? topicId : topic.title) + " · 更换", this::chooseTopic), LayoutHelper.createLinear(-1, -2));
        }
        workbench = GroupSummarySheet.createEmbedded(this, account, dialogId, topicId, unreadLower, unreadUpper,
                entryUnreadSnapshot, (sourceDialog, messageId) -> {
                    if (!sameOwner() || sourceDialog != dialogId) return;
                    Bundle args = new Bundle(); args.putLong("chat_id", -sourceDialog); args.putInt("message_id", messageId);
                    ChatActivity chatActivity = new ChatActivity(args); chatActivity.setCurrentAccount(account); presentFragment(chatActivity);
                }, () -> showTab(true));
        newPage.addView(workbench.getContentView(), LayoutHelper.createLinear(-1, -2));
        workbench.setAttached(resumed && !historyVisible);
    }

    private void chooseSource() {
        if (!sameOwner()) return;
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putBoolean("allowUsers", false);
        args.putBoolean("allowBots", false);
        args.putBoolean("allowGroups", true);
        args.putBoolean("allowChannels", true);
        args.putBoolean("allowSwitchAccount", false);
        args.putBoolean("allowGlobalSearch", false);
        args.putBoolean("canSelectTopics", false);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setCurrentAccount(account);
        picker.setDelegate((selected, keys, message, param, notify, date, repeat, topics) -> {
            if (!sameOwner() || keys == null || keys.size() != 1) return false;
            long selectedDialog = keys.get(0).dialogId;
            TLRPC.Chat chat = getMessagesController().getChat(-selectedDialog);
            if (selectedDialog >= 0 || chat == null || ChatObject.isKickedFromChat(chat)
                    || ChatObject.isMonoForum(chat) || chat.migrated_to != null) {
                selected.showDialog(new AlertDialog.Builder(selected.getParentActivity()).setTitle("无法选择来源")
                        .setMessage("请选择当前账号可以读取的群或频道。").setPositiveButton("知道了", null).create());
                return false;
            }
            selectScope(selectedDialog, 0, false);
            selected.finishFragment();
            return true;
        });
        presentFragment(picker);
    }

    private void chooseTopic() {
        if (!sameOwner() || dialogId == 0) return;
        final long selectedDialog = dialogId;
        new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("选择话题范围")
                .setItems(new CharSequence[] {"全部话题", "选择具体话题"}, (dialog, which) -> {
                    if (!sameOwner() || dialogId != selectedDialog) return;
                    if (which == 0) { selectScope(selectedDialog, 0, false); return; }
                    Bundle args = new Bundle(); args.putLong("chat_id", -selectedDialog); args.putBoolean("for_select", true);
                    TopicsFragment picker = new TopicsFragment(args); picker.setCurrentAccount(account);
                    picker.setOnTopicSelectedListener(topic -> {
                        if (!sameOwner() || topic == null) return;
                        selectScope(selectedDialog, topic.id, false);
                        picker.finishFragment();
                    });
                    presentFragment(picker);
                }).show();
    }

    private void selectScope(long selectedDialog, long selectedTopic, boolean useEntry) {
        dialogId = selectedDialog; topicId = selectedTopic;
        entryUnreadSnapshot = useEntry;
        if (!useEntry) captureUnread();
        buildWorkbench();
        showTab(false);
    }

    private void captureUnread() {
        unreadLower = unreadUpper = -1;
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (chat == null) return;
        int lower, upper;
        if (topicId != 0) {
            TLRPC.TL_forumTopic topic = getMessagesController().getTopicsController().findTopic(chat.id, topicId);
            if (topic == null) return;
            lower = topic.read_inbox_max_id; upper = topic.top_message;
            if (topic.unread_count == 0) lower = Math.max(lower, upper);
        } else {
            if (ChatObject.isForum(chat)) return;
            TLRPC.Dialog value = getMessagesController().dialogs_dict.get(dialogId);
            if (value == null) return;
            lower = value.read_inbox_max_id; upper = value.top_message;
            Integer known = getMessagesController().dialogs_read_inbox_max.get(dialogId);
            if (known != null) lower = Math.max(lower, known);
            if (value.unread_count == 0) lower = Math.max(lower, upper);
        }
        if (lower >= 0 && upper >= lower && upper > 0) { unreadLower = lower; unreadUpper = upper; }
    }

    private void updateTaskCard() {
        if (taskCard == null || !sameOwner()) return;
        SummaryTaskController.Session task = controller.current();
        taskCard.setVisibility(task == null ? View.GONE : View.VISIBLE);
        if (task != null) taskCard.setText(task.title() + " · " + task.stage + "\n点击查看任务");
    }

    private void openCurrentTask() {
        if (!sameOwner()) return;
        SummaryTaskController.Session task = controller.current();
        if (task == null) return;
        selectScope(task.dialogId, task.topicId, false);
    }

    private void openSettings() {
        SummaryTaskController.Session task = controller.current();
        if (task != null && task.running()) {
            showDialog(new AlertDialog.Builder(getParentActivity()).setTitle("任务正在进行")
                    .setMessage("可在任务完成或停止后修改 API 设置。当前任务使用开始时的配置。").setPositiveButton("知道了", null).create());
            return;
        }
        showTab(false);
        if (workbench != null) { workbench.openSettings(); return; }
        workbench = GroupSummarySheet.createEmbedded(this, account, 0, 0, -1, -1, false,
                (source, message) -> {}, this::buildWorkbench);
        newPage.addView(workbench.getContentView(), LayoutHelper.createLinear(-1, -2));
    }

    private void readCheckpoint() {
        Utilities.globalQueue.postRunnable(() -> {
            SummaryTaskCheckpoint.Record value;
            try { value = SummaryTaskCheckpoint.read(account, ownerId); }
            catch (RuntimeException ignored) { value = null; }
            final SummaryTaskCheckpoint.Record loaded = value;
            AndroidUtilities.runOnUIThread(() -> {
                if (!sameOwner() || controller.current() != null || loaded == null || checkpointCard == null) return;
                checkpoint = loaded;
                checkpointCard.setText("上次任务未确认结束 · 查看历史或重新选择范围");
                checkpointCard.setVisibility(View.VISIBLE);
            });
        });
    }

    private void showInterruptedTask() {
        if (!sameOwner() || checkpoint == null) return;
        SummaryTaskCheckpoint.Record previous = checkpoint;
        showDialog(new AlertDialog.Builder(getParentActivity()).setTitle("上次任务未确认结束")
                .setMessage("可先检查已保存历史。系统中断后的模型任务不会自动恢复或重新请求。")
                .setPositiveButton("查看历史", (dialog, which) -> showTab(true))
                .setNeutralButton("忽略此记录", (dialog, which) -> {
                    checkpoint = null; checkpointCard.setVisibility(View.GONE);
                    Utilities.globalQueue.postRunnable(() -> {
                        try { SummaryTaskCheckpoint.finish(account, ownerId, previous.taskId); } catch (RuntimeException ignored) {}
                    });
                }).setNegativeButton("关闭", null).create());
    }

    @Override public boolean dismissDialogOnPause(Dialog dialog) {
        return workbench == null || !workbench.keepExportDialogOnPause(dialog) ? super.dismissDialogOnPause(dialog) : false;
    }

    @Override public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (workbench != null && workbench.onExportActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResultFragment(requestCode, resultCode, data);
    }

    @Override public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (!sameOwner()) { invalidateAccount(); return; }
        if (changedAccount != account || dialogId == 0 || workbench == null) return;
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        if (id == NotificationCenter.messagesDeleted && args.length >= 3 && !((Boolean) args[2])) {
            if (chat == null || (Long) args[1] != (ChatObject.isChannel(chat) ? chat.id : 0)) return;
            for (int messageId : (ArrayList<Integer>) args[0]) {
                SummaryResultCache.getInstance().invalidateMessage(account, ownerId, dialogId, messageId);
                workbench.onSourceDeleted(messageId);
            }
        } else if (id == NotificationCenter.replaceMessagesObjects && args.length >= 2 && (Long) args[0] == dialogId) {
            for (MessageObject message : (ArrayList<MessageObject>) args[1]) {
                if (message == null || message.messageOwner == null || message.getDialogId() != dialogId) continue;
                TLRPC.Message source = message.messageOwner;
                SummaryResultCache.getInstance().invalidateMessage(account, ownerId, dialogId, source.id);
                workbench.onSourceUpdated(source.id, source.message, source.edit_date,
                        SummaryHistoryLoader.isUsableText(source, getConnectionsManager().getCurrentTime()));
            }
        } else if (id == NotificationCenter.chatInfoDidLoad || id == NotificationCenter.updateInterfaces) {
            if (chat == null || ChatObject.isKickedFromChat(chat) || chat.migrated_to != null) {
                SummaryResultCache.getInstance().clearOwner(account, ownerId);
                workbench.onAccessRevoked();
            }
        }
    }

    private TextView button(Context context, String value, Runnable action) {
        TextView view = label(context, value);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        view.setTypeface(AndroidUtilities.bold()); view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48)); view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8),
                getThemedColor(Theme.key_windowBackgroundGray), getThemedColor(Theme.key_listSelector)));
        view.setFocusable(true); view.setOnClickListener(ignored -> { if (sameOwner()) action.run(); });
        return view;
    }
    private TextView label(Context context, String value) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(16);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        return view;
    }
    private static int dp(float value) { return AndroidUtilities.dp(value); }
}
