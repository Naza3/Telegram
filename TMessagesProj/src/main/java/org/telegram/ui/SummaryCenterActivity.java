/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.DialogObject;
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
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.GroupSummarySheet;
import org.telegram.ui.Components.FeatureUi;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SummaryTaskController;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import org.telegram.ui.Cells.TextDetailSettingsCell;

/** Two-tab account-scoped workspace. Navigation never opens or marks the source chat read. */
public final class SummaryCenterActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int SETTINGS = 1;
    private final int account;
    private final long ownerId;
    private long dialogId, topicId;
    private int unreadLower = -1, unreadUpper = -1;
    private boolean entryUnreadSnapshot;
    private SummaryHistoryLoader.Result selectedSnapshot;
    private boolean selectedSnapshotInvalid;
    private boolean resumed, destroyed, invalidated, historyVisible;
    private LinearLayout root, newPage, sourceHeader;
    private FrameLayout pageHost;
    private ScrollSlidingTextTabStrip tabs;
    private TextDetailSettingsCell taskCard, checkpointCard;
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

    public static SummaryCenterActivity forSelected(int account, long ownerId, long dialogId, long topicId,
            SummaryHistoryLoader.Result snapshot) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || UserConfig.selectedAccount != account || UserConfig.getInstance(account).getClientUserId() != ownerId
                || snapshot == null || !snapshot.matchesSelectedScope(account, ownerId, dialogId, topicId)) {
            throw new IllegalArgumentException("所选消息已失效或账号已变化，请重新选择。");
        }
        SummaryCenterActivity center = new SummaryCenterActivity(account, dialogId, topicId, -1, -1, false);
        center.selectedSnapshot = snapshot;
        return center;
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
        actionBar.createMenu().addItem(SETTINGS, R.drawable.msg_settings).setContentDescription("模型 API 配置");
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        tabs = new ScrollSlidingTextTabStrip(context, getResourceProvider());
        tabs.setUseSameWidth(true);
        tabs.setColors(Theme.key_windowBackgroundWhiteBlueText, Theme.key_windowBackgroundWhiteBlueText,
                Theme.key_windowBackgroundWhiteGrayText, Theme.key_listSelector);
        tabs.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        tabs.addTextTab(0, "新建总结");
        tabs.addTextTab(1, "总结历史");
        tabs.finishAddingTabs();
        tabs.setInitialTabId(historyVisible ? 1 : 0);
        tabs.setDelegate(new ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate() {
            @Override public void onPageSelected(int id, boolean forward) { showTab(id == 1); }
            @Override public void onPageScrolled(float progress) { }
        });
        root.addView(tabs, LayoutHelper.createLinear(-1, 48));
        taskCard = sourceCell(context, "", "", false, this::openCurrentTask);
        root.addView(taskCard, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
        checkpointCard = sourceCell(context, "上次任务未确认结束", "查看历史或重新选择范围", false, this::showInterruptedTask);
        checkpointCard.setVisibility(View.GONE);
        root.addView(checkpointCard, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
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
        selectedSnapshot = null;
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
        selectedSnapshot = null;
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
        tabs.selectTabWithId(history ? 1 : 0, 1f);
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
        sourceHeader.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        newPage.addView(sourceHeader, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 8));
        TLRPC.Chat chat = dialogId == 0 ? null : getMessagesController().getChat(-dialogId);
        sourceHeader.addView(sourceCell(newPage.getContext(), chat == null ? "选择来源群／频道" : chat.title,
                chat == null ? "选择要总结的聊天" : "总结来源 · 点击更换", chat != null && ChatObject.isForum(chat),
                this::chooseSource), LayoutHelper.createLinear(-1, -2));
        if (chat == null) {
            TextView guide = label(newPage.getContext(), "先选择来源，再设置消息范围与手动核心总结要求。选择聊天不会打开聊天页或标记已读。");
            guide.setTag(Theme.key_windowBackgroundWhiteGrayText);
            guide.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            guide.setTextSize(14);
            sourceHeader.addView(guide, LayoutHelper.createLinear(-1, -2, 21, 0, 21, 16));
            return;
        }
        if (ChatObject.isForum(chat)) {
            TLRPC.TL_forumTopic topic = topicId == 0 ? null : getMessagesController().getTopicsController().findTopic(chat.id, topicId);
            sourceHeader.addView(sourceCell(newPage.getContext(), topicId == 0 ? "全部话题"
                    : topic == null ? "话题 " + topicId : topic.title, "话题范围 · 点击更换", false, this::chooseTopic),
                    LayoutHelper.createLinear(-1, -2));
        }
        if (selectedSnapshot != null) {
            TextView selectedNote = label(newPage.getContext(), "本次仅总结手动选择的文字；更换来源会放弃当前选择。");
            selectedNote.setTextSize(14);
            selectedNote.setTag(Theme.key_windowBackgroundWhiteGrayText);
            selectedNote.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            sourceHeader.addView(selectedNote, LayoutHelper.createLinear(-1, -2, 21, 0, 21, 12));
        }
        workbench = GroupSummarySheet.createEmbedded(this, account, dialogId, topicId, unreadLower, unreadUpper,
                entryUnreadSnapshot, (sourceDialog, messageId) -> {
                    if (!sameOwner() || sourceDialog != dialogId) return;
                    Bundle args = new Bundle(); args.putLong("chat_id", -sourceDialog); args.putInt("message_id", messageId);
                    ChatActivity chatActivity = new ChatActivity(args); chatActivity.setCurrentAccount(account); presentFragment(chatActivity);
                }, () -> showTab(true), selectedSnapshot);
        workbench.setSelectedSnapshotCallbacks(() -> {
            selectedSnapshot = null;
            selectedSnapshotInvalid = false;
        }, this::invalidateSelectedSnapshot);
        if (selectedSnapshotInvalid) workbench.invalidateSelectedSourceSnapshot();
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
        selectedSnapshot = null;
        selectedSnapshotInvalid = false;
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
        if (task != null) taskCard.setTextAndValue(task.title(), task.stage + " · 点击查看任务", false);
    }

    private void openCurrentTask() {
        if (!sameOwner()) return;
        SummaryTaskController.Session task = controller.current();
        if (task == null) return;
        selectScope(task.dialogId, task.topicId, false);
    }

    private void openSettings() {
        if (!sameOwner()) return;
        presentFragment(new ApiProfilesActivity(account, ownerId, () -> {
            if (sameOwner() && workbench != null) workbench.refreshApiSelection();
        }));
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
                checkpointCard.setTextAndValue("上次任务未确认结束", "查看历史或重新选择范围", false);
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
                if (selectedSourceChanged(messageId, null, 0, false)) invalidateSelectedSnapshot();
                workbench.onSourceDeleted(messageId);
            }
        } else if (id == NotificationCenter.replaceMessagesObjects && args.length >= 2 && (Long) args[0] == dialogId) {
            for (MessageObject message : (ArrayList<MessageObject>) args[1]) {
                if (message == null || message.messageOwner == null || message.getDialogId() != dialogId) continue;
                TLRPC.Message source = message.messageOwner;
                SummaryResultCache.getInstance().invalidateMessage(account, ownerId, dialogId, source.id);
                boolean usable = SummaryHistoryLoader.isUsableText(source, getConnectionsManager().getCurrentTime())
                        && !selectedMetadataChanged(source, chat);
                if (selectedSourceChanged(source.id, source.message, source.edit_date, usable)) invalidateSelectedSnapshot();
                workbench.onSourceUpdated(source.id, source.message, source.edit_date, usable);
            }
        } else if (id == NotificationCenter.chatInfoDidLoad || id == NotificationCenter.updateInterfaces) {
            if (chat == null || ChatObject.isKickedFromChat(chat) || chat.migrated_to != null) {
                SummaryResultCache.getInstance().clearOwner(account, ownerId);
                if (selectedSnapshot != null) invalidateSelectedSnapshot();
                workbench.onAccessRevoked();
            }
        }
    }

    private boolean selectedSourceChanged(int messageId, String text, int editDate, boolean usable) {
        if (selectedSnapshot == null) return false;
        for (org.telegram.messenger.ai.SummaryMessage source : selectedSnapshot.messages) {
            if (source.id == messageId && (!usable || source.editDate != editDate || !source.text.equals(text))) return true;
        }
        return false;
    }

    private boolean selectedMetadataChanged(TLRPC.Message message, TLRPC.Chat chat) {
        if (selectedSnapshot == null) return false;
        for (org.telegram.messenger.ai.SummaryMessage source : selectedSnapshot.messages) {
            if (source.id != message.id) continue;
            int replyId = 0;
            long replyPeer = 0;
            String quote = "";
            if (message.reply_to != null && !message.reply_to.reply_to_scheduled && !message.reply_to.reply_to_ephemeral) {
                if (message.reply_to.reply_to_msg_id > 0) {
                    replyId = message.reply_to.reply_to_msg_id;
                    replyPeer = message.reply_to.reply_to_peer_id == null ? dialogId
                            : DialogObject.getPeerDialogId(message.reply_to.reply_to_peer_id);
                }
                if ((message.reply_to.flags & (1 << 6)) != 0 && message.reply_to.quote_text != null) quote = message.reply_to.quote_text;
            }
            return source.senderId != DialogObject.getPeerDialogId(message.from_id)
                    || source.topicId != MessageObject.getTopicId(account, message, chat != null && chat.forum)
                    || source.replyToId != replyId || source.replyToDialogId != replyPeer || !source.quoteText.equals(quote)
                    || source.date != message.date || source.outgoing != message.out;
        }
        return false;
    }

    private void invalidateSelectedSnapshot() {
        selectedSnapshotInvalid = true;
        selectedSnapshot = null;
    }

    private TextDetailSettingsCell sourceCell(Context context, String title, String detail, boolean divider, Runnable action) {
        TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
        cell.getTextView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        cell.getValueTextView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        cell.setTextAndValue(title, detail, divider);
        cell.setBackground(Theme.getSelectorDrawable(true, getResourceProvider()));
        cell.setFocusable(true);
        cell.setOnClickListener(view -> { if (sameOwner()) action.run(); });
        return cell;
    }

    private TextView button(Context context, String value, Runnable action) {
        TextView view = label(context, value);
        view.setTag("action");
        FeatureUi.styleAction(view, getResourceProvider());
        view.setOnClickListener(ignored -> { if (sameOwner()) action.run(); });
        return view;
    }
    private TextView label(Context context, String value) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(16);
        view.setTag(Theme.key_windowBackgroundWhiteBlackText);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        return view;
    }
    private void updateSourceColors(View view) {
        if (view == null) return;
        if (view instanceof TextDetailSettingsCell) {
            TextDetailSettingsCell cell = (TextDetailSettingsCell) view;
            cell.getTextView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            cell.getValueTextView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            cell.setBackground(Theme.getSelectorDrawable(true, getResourceProvider()));
        }
        if (view instanceof TextView && view.getTag() instanceof Integer) {
            ((TextView) view).setTextColor(getThemedColor((Integer) view.getTag()));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) updateSourceColors(group.getChildAt(i));
        }
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(root, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        ThemeDescription.ThemeDescriptionDelegate refresh = () -> {
            if (tabs != null) {
                tabs.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                tabs.updateColors();
            }
            if (sourceHeader != null) sourceHeader.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            updateSourceColors(sourceHeader);
            updateSourceColors(taskCard);
            updateSourceColors(checkpointCard);
            if (historyPanel != null) historyPanel.updateColors();
            if (workbench != null) workbench.updateColors();
        };
        for (int key : SummaryHistoryPanel.THEME_KEYS) {
            descriptions.add(new ThemeDescription(null, 0, null, null, null, refresh, key));
        }
        // Additional colors used by the embedded workbench and its dialogs. Shared action and
        // input colors, including the primary button, are already registered by THEME_KEYS above.
        int[] workbenchKeys = { Theme.key_windowBackgroundWhiteGrayText4,
                Theme.key_radioBackground, Theme.key_radioBackgroundChecked,
                Theme.key_switchTrack, Theme.key_switchTrackChecked,
                Theme.key_dialogBackground, Theme.key_dialogRadioBackground, Theme.key_dialogRadioBackgroundChecked,
                Theme.key_dialogTextBlack, Theme.key_dialogTextGray, Theme.key_dialogTextLink, Theme.key_dialogTextHint,
                Theme.key_dialogInputField, Theme.key_dialogInputFieldActivated };
        for (int key : workbenchKeys) {
            descriptions.add(new ThemeDescription(null, 0, null, null, null, refresh, key));
        }
        return descriptions;
    }

}
