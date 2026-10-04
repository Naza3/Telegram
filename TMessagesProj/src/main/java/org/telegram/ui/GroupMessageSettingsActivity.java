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
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.EditTextBoldCursor;

import java.util.ArrayList;

/** One group, two optional features; no permissions, account-wide rules or AI settings. */
public final class GroupMessageSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final int account;
    private final long ownerId, dialogId;
    private Runnable onSaved;
    private EditText uidInput, keywordInput;
    private TextInfoPrivacyCell groupTitle, status;
    private TextSettingsCell modeButton, saveButton, historyButton, clearButton;
    private final ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
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
        themeDescriptions.clear();
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        AndroidUtilities.setScrollViewEdgeEffectColor(scroll, getThemedColor(Theme.key_actionBarDefault));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(16));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        TLRPC.Chat chat = sameOwner() ? getMessagesController().getChat(-dialogId) : null;
        groupTitle = info(content, chat == null ? "本群设置" : chat.title);
        header(content, "屏蔽与折叠");
        modeButton = row(content, "屏蔽方式", Theme.key_windowBackgroundWhiteBlackText, false);
        modeButton.setTextAndValue("屏蔽方式", modeName(mode), false);
        modeButton.setOnClickListener(view -> chooseMode());
        info(content, "本群全部话题共用规则，匹配任一 UID 或关键词即可生效。仅改变主聊天列表显示，保留本人发言和系统消息；通知、搜索和 AI 总结排除名单保持各自设置。隐藏后可在群菜单点“临时显示全部消息”查看。");
        header(content, "用户 UID");
        uidInput = input(content, "每行一个 UID，例如 123456789", 88);
        info(content, "从用户主页复制 Telegram UID，按实际发言者匹配，不按昵称或转发原作者判断。最多 1000 个，可用换行、空格或逗号分隔；匿名管理员或频道身份发言无法对应到个人 UID。");
        header(content, "关键词");
        keywordInput = input(content, "每行一个关键词", 88);
        info(content, "最多 100 条，每条最多 100 个字符。匹配文字和媒体说明，忽略大小写，不支持正则表达式；相册整组处理。关闭屏蔽会恢复正常显示并保留规则。");
        header(content, "撤回记录");
        retainCell = new TextCheckCell(context, getResourceProvider());
        retainCell.setTextAndCheck("保留撤回文字", false, true);
        selectable(retainCell);
        content.addView(retainCell, LayoutHelper.createLinear(-1, -2));
        themeDescriptions.add(new ThemeDescription(retainCell, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        for (int key : new int[]{Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite}) {
            themeDescriptions.add(new ThemeDescription(retainCell, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, key));
        }
        retainCell.setOnClickListener(view -> {
            if (!editable() || Build.VERSION.SDK_INT < 23) return;
            retain = !retain; retainCell.setChecked(retain);
        });
        historyButton = row(content, "查看本群撤回记录", Theme.key_windowBackgroundWhiteBlackText, true);
        historyButton.setOnClickListener(view -> {
            if (active() && !saving && !clearing) presentFragment(new DeletedMessageHistoryActivity(account, ownerId, dialogId));
        });
        clearButton = row(content, "清理本群撤回记录", Theme.key_text_RedRegular, false);
        clearButton.setOnClickListener(view -> confirmClear());
        if (Build.VERSION.SDK_INT < 23) info(content, "保留撤回文字需要 Android 6.0 或更新版本；当前设备不支持。");
        info(content, "保存后生效。仅在本机保留已缓存、随后被撤回的普通群文字，在记录页查看；聊天中仍按 Telegram 的撤回结果更新。无法恢复未收到的内容，不保存秘密聊天、自毁或阅后即焚消息。\n\n关闭后停止新增记录。每个账号最多保留 2000 条或 8 MiB，超出时清理最旧记录；也可手动清理，退出账号时清除。规则和记录仅保存在本机。");
        saveButton = row(content, "保存本群设置", Theme.key_windowBackgroundWhiteBlueText, false);
        saveButton.setOnClickListener(view -> save());
        status = info(content, sameOwner() ? "正在读取…" : "账号已切换或退出，请重新打开消息管理。");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        themeDescriptions.add(new ThemeDescription(scroll, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(scroll, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(content, 0, new Class[]{TextSettingsCell.class, TextCheckCell.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        if (loaded && snapshot != null) {
            uidInput.setText(draftUids); keywordInput.setText(draftKeywords);
            modeButton.setTextAndValue("屏蔽方式", modeName(mode), false); retainCell.setChecked(retain);
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
        modeButton.setTextAndValue("屏蔽方式", modeName(mode), false); retainCell.setChecked(retain);
    }
    private void chooseMode() {
        if (!editable()) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("屏蔽方式")
                .setItems(new CharSequence[]{"关闭", "折叠（可点按展开）", "隐藏"}, (dialog, which) -> {
                    if (!editable()) return;
                    mode = which; modeButton.setTextAndValue("屏蔽方式", modeName(mode), false);
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
        LinearLayout container = new LinearLayout(parent.getContext());
        container.setPadding(dp(21), 0, dp(21), dp(12));
        container.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        parent.addView(container, LayoutHelper.createLinear(-1, -2));
        themeDescriptions.add(new ThemeDescription(container, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        EditTextBoldCursor view = new EditTextBoldCursor(parent.getContext());
        view.setTextSize(16); view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        view.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setHint(hint); view.setGravity(Gravity.TOP | Gravity.START);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setMinHeight(dp(height)); view.setPadding(0, dp(8), 0, dp(8));
        Runnable updateBackground = () -> view.setBackground(Theme.createEditTextDrawable(parent.getContext(),
                getThemedColor(Theme.key_windowBackgroundWhiteInputField), getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated)));
        updateBackground.run();
        container.addView(view, LayoutHelper.createLinear(-1, -2));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null,
                () -> view.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText)), Theme.key_windowBackgroundWhiteHintText));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_CURSORCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, updateBackground::run, Theme.key_windowBackgroundWhiteInputField));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, updateBackground::run, Theme.key_windowBackgroundWhiteInputFieldActivated));
        return view;
    }
    private void header(LinearLayout parent, String value) {
        HeaderCell view = new HeaderCell(parent.getContext(), 21, getResourceProvider());
        view.setText(value);
        view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        parent.addView(view, LayoutHelper.createLinear(-1, -2));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(view, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
    }
    private TextInfoPrivacyCell info(LinearLayout parent, String value) {
        TextInfoPrivacyCell view = new TextInfoPrivacyCell(parent.getContext(), getResourceProvider());
        view.setText(value);
        parent.addView(view, LayoutHelper.createLinear(-1, -2));
        themeDescriptions.add(new ThemeDescription(view, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        return view;
    }
    private TextSettingsCell row(LinearLayout parent, String value, int colorKey, boolean divider) {
        TextSettingsCell view = new TextSettingsCell(parent.getContext(), getResourceProvider());
        view.setText(value, divider);
        view.setTextColor(getThemedColor(colorKey));
        selectable(view);
        parent.addView(view, LayoutHelper.createLinear(-1, -2));
        themeDescriptions.add(new ThemeDescription(view, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, colorKey));
        themeDescriptions.add(new ThemeDescription(view, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        return view;
    }
    private void selectable(View view) {
        view.setBackground(Theme.createSelectorWithBackgroundDrawable(getThemedColor(Theme.key_windowBackgroundWhite), getThemedColor(Theme.key_listSelector)));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(view, ThemeDescription.FLAG_SELECTORWHITE, null, null, null, null, Theme.key_listSelector));
    }
    @Override public ArrayList<ThemeDescription> getThemeDescriptions() { return themeDescriptions; }
    private static String modeName(int value) { return value == GroupMessageSettings.FOLD ? "折叠" : value == GroupMessageSettings.HIDE ? "隐藏" : "关闭"; }
    private static int dp(float value) { return AndroidUtilities.dp(value); }
}
