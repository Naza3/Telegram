/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.groupmessages.DeletedGroupMessages;
import org.telegram.messenger.groupmessages.GroupMessageSettings;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

/** One group, two optional features; no permissions, account-wide rules or AI settings. */
public final class GroupMessageSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final int account;
    private final long ownerId, dialogId;
    private Runnable onSaved;
    private EditText uidInput, keywordInput;
    private TextView groupTitle, modeButton, status, saveButton, historyButton, clearButton;
    private TextCheckCell retainCell;
    private GroupMessageSettings.Rule snapshot;
    private int mode;
    private boolean retain, loading, saving, clearing, loaded, resumed;
    private volatile boolean destroyed, invalidated;
    private volatile int operation;
    private String initialUids = "", initialKeywords = "";

    public GroupMessageSettingsActivity(int account, long dialogId) {
        this(account, UserConfig.getInstance(account).getClientUserId(), dialogId, null);
    }

    public GroupMessageSettingsActivity(int account, long ownerId, long dialogId, Runnable onSaved) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0 || dialogId >= 0 || dialogId == Long.MIN_VALUE) {
            throw new IllegalArgumentException("请从有效的群打开消息管理。");
        }
        this.account = account; this.ownerId = ownerId; this.dialogId = dialogId; this.onSaved = onSaved;
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
        // Theme/configuration recreation must keep the editor draft and in-flight save state.
        String draftUids = uidInput == null ? initialUids : uidInput.getText().toString();
        String draftKeywords = keywordInput == null ? initialKeywords : keywordInput.getText().toString();
        CharSequence previousStatus = status == null ? null : status.getText();
        actionBar.setTitle("消息管理");
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) leave(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        TLRPC.Chat chat = sameOwner() ? getMessagesController().getChat(-dialogId) : null;
        groupTitle = text(content, chat == null ? "本群设置" : chat.title, false);
        text(content, "屏蔽与折叠", true).setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        modeButton = text(content, "屏蔽方式：关闭", true);
        modeButton.setOnClickListener(view -> chooseMode());
        text(content, "本群全部话题共用规则。匹配任一 UID 或关键词即可生效，仅改变主聊天列表显示。关键词匹配文字和媒体说明，相册整组处理；通知、搜索和 AI 总结排除名单保持各自设置。", false);
        text(content, "保留本人发言和系统消息。按实际发言者 UID 匹配，不按昵称或转发原作者判断。", false);
        text(content, "隐藏后可在群菜单点“临时显示全部消息”恢复查看。", false);
        text(content, "用户 UID", false);
        uidInput = input(content, "每行一个 UID，例如 123456789", 120);
        text(content, "从用户主页复制 Telegram UID。最多 1000 个，可用换行、空格或逗号分隔；匿名管理员或频道身份发言无法对应到个人 UID。", false);
        text(content, "关键词", false);
        keywordInput = input(content, "每行一个关键词", 120);
        text(content, "最多 100 条，每条最多 100 个字符。按原文字面包含匹配，忽略大小写，不支持正则表达式。关闭屏蔽会恢复正常显示并保留规则。", false);
        retainCell = new TextCheckCell(context, getResourceProvider());
        retainCell.setTextAndCheck("保留撤回文字", false, false);
        retainCell.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        content.addView(retainCell, LayoutHelper.createLinear(-1, -2));
        retainCell.setOnClickListener(view -> {
            if (!editable() || Build.VERSION.SDK_INT < 23) return;
            retain = !retain; retainCell.setChecked(retain);
        });
        if (Build.VERSION.SDK_INT < 23) text(content, "保留撤回文字需要 Android 6.0 或更新版本；当前设备不支持。", false);
        text(content, "保存后生效。仅在本机保留已缓存、随后被撤回的普通群文字，在下方记录页查看；聊天中仍按 Telegram 的撤回结果更新。无法恢复未收到的内容，不保存秘密聊天、自毁或阅后即焚消息。", false);
        text(content, "关闭后停止新增记录。每个账号最多保留 2000 条或 8 MiB，超出时清理最旧记录；也可手动清理，退出账号时清除。规则和记录仅保存在本机。", false);
        status = text(content, sameOwner() ? "正在读取…" : "账号已切换或退出，请重新打开消息管理。", false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        saveButton = text(content, "保存本群设置", true);
        saveButton.setOnClickListener(view -> save());
        historyButton = text(content, "查看本群撤回记录", true);
        historyButton.setOnClickListener(view -> {
            if (active() && !saving && !clearing) presentFragment(new DeletedMessageHistoryActivity(account, ownerId, dialogId));
        });
        clearButton = text(content, "清理本群撤回记录", true);
        clearButton.setTextColor(getThemedColor(Theme.key_text_RedRegular));
        clearButton.setOnClickListener(view -> confirmClear());
        if (loaded && snapshot != null) {
            uidInput.setText(draftUids); keywordInput.setText(draftKeywords);
            modeButton.setText("屏蔽方式：" + modeName(mode)); retainCell.setChecked(retain);
            if (previousStatus != null) status.setText(previousStatus);
        }
        setEditable(loaded && !saving && !clearing && !loading && !invalidated);
        fragmentView = scroll;
        return scroll;
    }

    @Override public void onResume() {
        super.onResume(); resumed = true;
        if (!sameOwner()) invalidate();
        else if (!loaded && !loading && !saving) load();
    }
    @Override public void onPause() { resumed = false; super.onPause(); }
    @Override public void onFragmentDestroy() {
        destroyed = true; resumed = false; operation++; onSaved = null; snapshot = null;
        initialUids = initialKeywords = "";
        if (uidInput != null) uidInput.setText("");
        if (keywordInput != null) keywordInput.setText("");
        if (groupTitle != null) groupTitle.setText("");
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        super.onFragmentDestroy();
    }
    @Override public boolean canBeginSlide() { return !saving && !clearing && !dirty() && super.canBeginSlide(); }
    @Override public boolean onBackPressed(boolean invoked) {
        if (sameOwner() && (saving || clearing || dirty())) { if (invoked) leave(); return false; }
        return super.onBackPressed(invoked);
    }
    @Override public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (id == NotificationCenter.activeAccountChanged && UserConfig.selectedAccount != account
                || id == NotificationCenter.appDidLogout && changedAccount == account) invalidate();
    }

    private void load() {
        if (!active()) return;
        loading = true;
        int token = ++operation;
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner() || token != operation) return;
            try {
                GroupMessageSettings.Rule value = GroupMessageSettings.load(account, ownerId, dialogId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!sameOwner() || token != operation) return;
                    loading = false; loaded = true; display(value);
                    status.setText("修改后点“保存本群设置”生效。"); setEditable(true);
                });
            } catch (RuntimeException error) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!sameOwner() || token != operation) return;
                    loading = false; status.setText(error.getMessage() == null ? "读取失败，请重新打开。" : error.getMessage());
                });
            }
        });
    }
    private void display(GroupMessageSettings.Rule value) {
        snapshot = value; mode = value.filterMode; retain = value.antiRevoke;
        initialUids = GroupMessageSettings.formatUids(value.uids);
        initialKeywords = GroupMessageSettings.formatKeywords(value.keywords);
        uidInput.setText(initialUids); keywordInput.setText(initialKeywords);
        modeButton.setText("屏蔽方式：" + modeName(mode)); retainCell.setChecked(retain);
    }
    private void chooseMode() {
        if (!editable()) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("屏蔽方式")
                .setItems(new CharSequence[]{"关闭", "折叠（可点按展开）", "隐藏"}, (dialog, which) -> {
                    if (!editable()) return;
                    mode = which; modeButton.setText("屏蔽方式：" + modeName(mode));
                }).create());
    }
    private void save() {
        if (!editable() || snapshot == null) return;
        final String uids = uidInput.getText().toString(), keywords = keywordInput.getText().toString();
        try { GroupMessageSettings.parseUids(uids); GroupMessageSettings.parseKeywords(keywords); }
        catch (RuntimeException error) { status.setText(error.getMessage()); return; }
        final int savedMode = mode, token = ++operation;
        final boolean savedRetain = retain;
        final long revision = snapshot.revision;
        saving = true; setEditable(false); status.setText("正在保存…");
        AndroidUtilities.hideKeyboard(uidInput);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner() || token != operation) return;
            try {
                GroupMessageSettings.Rule result = GroupMessageSettings.save(account, ownerId, dialogId,
                        revision, savedMode, uids, keywords, savedRetain);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!sameOwner() || token != operation) return;
                    saving = false; display(result); setEditable(true); status.setText("已保存，返回群聊即可生效。");
                    if (onSaved != null) onSaved.run();
                });
            } catch (RuntimeException error) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!sameOwner() || token != operation) return;
                    saving = false; setEditable(true);
                    status.setText(error.getMessage() == null ? "保存失败，请重试。" : error.getMessage());
                });
            }
        });
    }
    private void confirmClear() {
        if (!active() || saving || clearing) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("清理本群撤回记录？")
                .setMessage("删除本机已经保留的本群撤回文字。保留开关开启时，后续撤回仍可产生新记录。")
                .setNegativeButton("取消", null).setPositiveButton("清理", (dialog, which) -> {
                    if (!active() || saving || clearing) return;
                    clearing = true; setEditable(false); status.setText("正在清理…");
                    DeletedGroupMessages.clear(account, ownerId, dialogId, failed -> {
                        if (!sameOwner()) return;
                        clearing = false; setEditable(loaded); status.setText(failed ? "清理失败，请重试；也可在撤回记录页检查并重置记录。" : "本群撤回记录已清理。");
                    });
                }).create());
    }
    private void leave() {
        if (!sameOwner()) { finishFragment(); return; }
        if (saving || clearing) { status.setText("正在处理，请稍候。"); return; }
        if (!dirty()) { finishFragment(); return; }
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("放弃未保存的修改？")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃", (dialog, which) -> finishFragment()).create());
    }
    private boolean dirty() {
        return loaded && snapshot != null && uidInput != null && keywordInput != null
                && (mode != snapshot.filterMode || retain != snapshot.antiRevoke
                || !initialUids.equals(uidInput.getText().toString()) || !initialKeywords.equals(keywordInput.getText().toString()));
    }
    private boolean editable() { return active() && loaded && !loading && !saving && !clearing; }
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
        invalidated = true; operation++; onSaved = null; snapshot = null;
        initialUids = initialKeywords = "";
        if (uidInput != null) uidInput.setText("");
        if (keywordInput != null) keywordInput.setText("");
        setEditable(false);
        if (groupTitle != null) groupTitle.setText("");
        dismissCurrentDialog();
        if (historyButton != null) historyButton.setEnabled(false);
        if (clearButton != null) clearButton.setEnabled(false);
        if (status != null) status.setText("账号已切换或退出，请从当前账号重新打开消息管理。");
    }
    private void setEditable(boolean enabled) {
        for (View view : new View[]{uidInput, keywordInput, modeButton, retainCell, saveButton}) {
            if (view != null) { view.setEnabled(enabled); view.setAlpha(enabled ? 1f : .5f); }
        }
        if (retainCell != null && Build.VERSION.SDK_INT < 23) { retainCell.setEnabled(false); retainCell.setAlpha(.5f); }
        if (historyButton != null) historyButton.setEnabled(!saving && !clearing && !invalidated && Build.VERSION.SDK_INT >= 23);
        if (clearButton != null) clearButton.setEnabled(!saving && !clearing && !invalidated && Build.VERSION.SDK_INT >= 23);
    }
    private EditText input(LinearLayout parent, String hint, int height) {
        EditText view = new EditText(parent.getContext());
        view.setTextSize(16); view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        view.setHint(hint); view.setGravity(Gravity.TOP | Gravity.START);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setMinHeight(dp(height)); view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setBackground(Theme.createEditTextDrawable(parent.getContext(), false));
        parent.addView(view, LayoutHelper.createLinear(-1, -2));
        return view;
    }
    private TextView text(LinearLayout parent, String value, boolean action) {
        TextView view = new TextView(parent.getContext());
        view.setText(value); view.setTextSize(action ? 16 : 14);
        view.setTextColor(getThemedColor(action ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText));
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); view.setPadding(0, dp(10), 0, dp(10));
        if (action) { view.setMinHeight(dp(48)); view.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2)); }
        parent.addView(view, LayoutHelper.createLinear(-1, -2));
        return view;
    }
    private static String modeName(int value) { return value == GroupMessageSettings.FOLD ? "折叠" : value == GroupMessageSettings.HIDE ? "隐藏" : "关闭"; }
    private static int dp(float value) { return AndroidUtilities.dp(value); }
}
