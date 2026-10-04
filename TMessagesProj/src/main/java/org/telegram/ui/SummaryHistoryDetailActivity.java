/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.SummaryHistoryLoader;
import org.telegram.messenger.ai.SummaryHistoryStore;
import org.telegram.messenger.ai.SummaryMessage;
import org.telegram.messenger.ai.SummaryPublishStore;
import org.telegram.messenger.ai.SummarySourceReference;
import org.telegram.messenger.ai.SummarySourceVerifier;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.FeatureUi;
import org.telegram.ui.Components.SummaryPublishHelper;
import org.telegram.ui.Components.SummaryPublishStatusText;
import org.telegram.ui.Components.SummaryTextFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Displays a saved result, re-fetching and verifying originals only after a reference is tapped. */
public final class SummaryHistoryDetailActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int COPY = 1;
    private static final Pattern REFERENCE = Pattern.compile("\\[m([0-9]+)\\]");
    private final int account;
    private final long ownerId;
    private final String recordId;
    private LinearLayout content;
    private SummaryHistoryStore.Record record;
    private boolean resumed;
    private boolean destroyed;
    private boolean accountInvalidated;
    private int operation;
    private int sourceOperation;
    private AlertDialog sourceDialog;
    private LinearLayout sourceContent;
    private SummarySourceVerifier verifier;
    private SummarySourceReference previewReference;
    private String transientNotice;
    private boolean detailsExpanded;
    private final ArrayList<SummaryPublishStore.Attempt> publishAttempts = new ArrayList<>();
    private String publishNotice;

    public SummaryHistoryDetailActivity(int account, String recordId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || recordId == null || recordId.isEmpty()) {
            throw new IllegalArgumentException("总结历史标识无效。");
        }
        this.account = account;
        this.recordId = recordId;
        setCurrentAccount(account);
        ownerId = getUserConfig().getClientUserId();
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate() || !sameOwner()) return false;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().addObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.chatInfoDidLoad);
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("已保存的 AI 总结");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == COPY && active() && record != null) {
                    transientNotice = AndroidUtilities.addToClipboard(record.summary) ? "总结文本已复制。" : "复制失败，请重试。";
                    showRecord();
                }
            }
        });
        actionBar.createMenu().addItem(COPY, R.drawable.msg_copy).setContentDescription("复制总结文本");
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        showStatus("正在读取总结…", false);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (!sameOwner()) invalidateAccount();
        else loadRecord();
    }

    @Override
    public void onPause() {
        resumed = false;
        operation++;
        closePreview();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        resumed = false;
        operation++;
        closePreview();
        record = null;
        publishAttempts.clear();
        if (content != null) content.removeAllViews();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().removeObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.chatInfoDidLoad);
        super.onFragmentDestroy();
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
        closePreview();
        record = null;
        publishAttempts.clear();
        publishNotice = null;
        transientNotice = null;
        showStatus("账号已切换或退出，请从当前账号重新打开总结历史。", false);
    }

    private void loadRecord() {
        if (!active()) return;
        final int request = ++operation;
        closePreview();
        record = null;
        publishAttempts.clear();
        publishNotice = null;
        transientNotice = null;
        showStatus("正在读取总结…", false);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner()) return;
            try {
                SummaryHistoryStore.Record loaded = SummaryHistoryStore.get(account, ownerId, recordId);
                List<SummaryPublishStore.Attempt> attempts = new ArrayList<>();
                String notice = null;
                if (loaded != null) {
                    try { attempts = SummaryPublishStore.listForRecord(account, ownerId, recordId); }
                    catch (RuntimeException failure) { notice = "发送记录暂不可用，原总结仍可查看；请在聊天中核对发送结果。"; }
                }
                final List<SummaryPublishStore.Attempt> loadedAttempts = attempts;
                final String loadedNotice = notice;
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    record = loaded;
                    publishAttempts.addAll(loadedAttempts);
                    publishNotice = loadedNotice;
                    if (loaded == null) showStatus("这条总结已删除或已超过历史保留上限。请返回列表刷新。", false);
                    else showRecord();
                });
            } catch (RuntimeException failure) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    showStatus("无法读取保存的总结：" + SummaryHistoryActivity.failureMessage(failure), true);
                });
            }
        });
    }

    private void showRecord() {
        if (content == null || record == null || !active()) return;
        content.removeAllViews();
        LinearLayout body = section();
        TextView title = text(body, SummaryHistoryActivity.chatLabel(record), true);
        title.setTextSize(18);
        text(body, record.rangeLabel + " · " + record.sources.size() + " 条文字 · 已保存"
                + (record.partial ? " · 部分覆盖" : ""), false);
        text(body, SummaryHistoryActivity.formatTime(record.generatedAtMillis), false);
        if (record.partial && !record.coverageNote.isEmpty()) text(body, record.coverageNote, false);
        if (transientNotice != null) text(body, transientNotice, true);
        TextView summary = text(body, linkSources(record), false);
        summary.setTextSize(17);
        summary.setTag(Theme.key_windowBackgroundWhiteBlackText);
        summary.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        summary.setLineSpacing(dp(4), 1f);
        summary.setTextIsSelectable(true);
        if (record.sourceLinks) summary.setMovementMethod(LinkMovementMethod.getInstance());
        summary.setLinksClickable(record.sourceLinks);
        boolean hasPublishedAttempt = !publishAttempts.isEmpty();
        LinearLayout actions = section();
        TextView send = action(actions, hasPublishedAttempt ? "再次编辑并发回" : "编辑并发回本群", () -> {
            if (record != null) SummaryPublishHelper.open(this, account, ownerId, record);
        });
        send.setTag("primary");
        FeatureUi.stylePrimaryAction(send, getResourceProvider());
        if (publishNotice != null) text(actions, publishNotice, false);
        else if (hasPublishedAttempt) {
            SummaryPublishStore.Attempt latest = publishAttempts.get(0);
            text(actions, SummaryPublishStatusText.label(latest), false);
            if (latest.status == SummaryPublishStore.Status.FAILED || latest.status == SummaryPublishStore.Status.PARTIAL) {
                text(actions, "请在原群或原话题中重试失败的那条消息；再次编辑发送会创建新消息，已经成功的内容也可能重复。", false);
            } else {
                text(actions, "再次编辑发送会创建新消息，不会修改或重试已有消息。", false);
            }
        }
        action(actions, "复制总结", () -> {
            transientNotice = AndroidUtilities.addToClipboard(record.summary) ? "总结文本已复制。" : "复制失败，请重试。";
            showRecord();
        });
        TextView detailsAction = action(actions, detailsExpanded ? "收起范围和生成详情" : "查看范围和生成详情", () -> {
            detailsExpanded = !detailsExpanded;
            showRecord();
        });
        detailsAction.setSelected(detailsExpanded);
        if (detailsExpanded) {
            LinearLayout details = section();
            text(details, "范围和生成详情", true);
            text(details, "生成于 " + SummaryHistoryActivity.formatTime(record.generatedAtMillis), false);
            text(details, "这是生成当时保存的结果，不代表当前最新消息或模型。原消息可能已修改或删除。"
                    + (record.sourceLinks ? "点击旧记录中的引用后才联网核验。" : ""), false);
            if (!record.coverageNote.isEmpty()) text(details, record.coverageNote, false);
            if (!record.templateLabel.isEmpty()) text(details, "生成时的要求来源：" + record.templateLabel, false);
            if (!record.customInstructions.isEmpty()) text(details, "生成时填写的要求：\n" + record.customInstructions, false);
            text(details, "请求模型：" + (record.model.isEmpty() ? "服务当前模型（未指定名称）" : record.model)
                    + "。此名称不是实际模型版本的核验证明。", false);
            text(details, "本机只保存总结与来源校验信息，没有保存原消息正文或完整请求输入。", false);
            if (!publishAttempts.isEmpty()) {
                LinearLayout publications = section();
                text(publications, "发送记录", true);
                text(publications, "这里保留提交给原生发送队列时的文字，后续在聊天中编辑或删除消息不修改这份记录。失败请通过聊天中的原消息重试。", false);
                for (SummaryPublishStore.Attempt attempt : publishAttempts) {
                    text(publications, SummaryHistoryActivity.formatTime(attempt.createdAtMillis) + " · " + SummaryPublishStatusText.label(attempt), false);
                    TextView sentText = text(publications, "当时的发送稿：\n" + attempt.editedSummary, false);
                    sentText.setTextIsSelectable(true);
                    sentText.setTag(Theme.key_windowBackgroundWhiteBlackText);
                    sentText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                }
            }
        }
    }

    private LinearLayout section() {
        LinearLayout section = new LinearLayout(content.getContext());
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(21), dp(4), dp(21), dp(12));
        section.setTag("surface");
        section.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content.addView(section, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 8));
        return section;
    }

    private CharSequence linkSources(SummaryHistoryStore.Record snapshot) {
        final int request = operation;
        SpannableStringBuilder linked = SummaryTextFormatter.format(snapshot.summary);
        if (!snapshot.sourceLinks) return linked;
        Matcher references = REFERENCE.matcher(snapshot.summary);
        while (references.find()) {
            final int reference;
            try { reference = Integer.parseInt(references.group(1)); }
            catch (NumberFormatException ignored) { continue; }
            if (reference < 1 || reference > snapshot.sources.size()) continue;
            SummarySourceReference source = snapshot.sources.get(reference - 1);
            linked.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    if (request == operation && active() && record == snapshot) openSource(source, reference);
                }
                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(getThemedColor(Theme.key_windowBackgroundWhiteLinkText));
                    paint.setUnderlineText(true);
                }
            }, references.start(), references.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return linked;
    }

    private void openSource(SummarySourceReference reference, int number) {
        if (!active() || record == null || !record.sourceLinks || reference.dialogId != record.dialogId) return;
        closePreview();
        previewReference = reference;
        final int request = operation;
        final int sourceRequest = sourceOperation;
        sourceContent = new LinearLayout(getParentActivity());
        sourceContent.setOrientation(LinearLayout.VERTICAL);
        sourceContent.setPadding(dp(24), 0, dp(24), dp(12));
        text(sourceContent, "正在读取并核验原消息…", true);
        final AlertDialog preview = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle("原文引用 [m" + number + "]")
                .setView(sourceContent)
                .setNegativeButton("关闭", (dialog, which) -> closePreview())
                .create();
        sourceDialog = preview;
        if (showDialog(preview, false, ignored -> {
            if (sourceDialog == preview) closePreview();
        }) == null) {
            closePreview();
            return;
        }
        resolveChatAndVerify(reference, request, sourceRequest);
    }

    private void resolveChatAndVerify(SummarySourceReference reference, int request, int sourceRequest) {
        if (!previewActive(request, sourceRequest)) return;
        if (MessagesController.getInstance(account).getChat(-reference.dialogId) != null) {
            verify(reference, null, request, sourceRequest);
            return;
        }
        // Disk chat data restores the access hash for addressing. It never proves read permission.
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner()) return;
            try {
                TLRPC.Chat cached = MessagesStorage.getInstance(account).getChatSync(-reference.dialogId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!previewActive(request, sourceRequest)) return;
                    if (cached == null || cached.id != -reference.dialogId) {
                        sourceError("本机尚无此聊天的访问资料。请先打开原聊天，再返回此处核验引用。", request, sourceRequest, true);
                        return;
                    }
                    MessagesController.getInstance(account).putChat(cached, true);
                    verify(reference, null, request, sourceRequest);
                });
            } catch (RuntimeException failure) {
                AndroidUtilities.runOnUIThread(() -> sourceError("无法读取聊天访问资料，请打开原聊天后重试。", request, sourceRequest, true));
            }
        });
    }

    /** A jump re-fetches the just-previewed message, so edits between preview and navigation fail. */
    private void verify(SummarySourceReference reference, SummaryMessage jumpFrom, int request, int sourceRequest) {
        if (!previewActive(request, sourceRequest)) return;
        if (verifier != null) verifier.cancel();
        sourceContent.removeAllViews();
        text(sourceContent, jumpFrom == null ? "正在从 Telegram 核验原消息…" : "跳转前正在再次核验原消息…", true);
        verifier = new SummarySourceVerifier(account, ownerId, record.dialogId, record.topicId);
        SummarySourceVerifier.Callback callback = new SummarySourceVerifier.Callback() {
            @Override public void onVerified(SummaryMessage current) {
                if (!previewActive(request, sourceRequest)) return;
                verifier = null;
                if (jumpFrom != null) {
                    closePreview();
                    if (!active() || current.dialogId != record.dialogId) return;
                    Bundle args = new Bundle();
                    args.putLong("chat_id", -current.dialogId);
                    args.putInt("message_id", current.id);
                    ChatActivity chat = new ChatActivity(args);
                    chat.setCurrentAccount(account);
                    presentFragment(chat);
                    return;
                }
                sourceContent.removeAllViews();
                text(sourceContent, current.sender, true);
                text(sourceContent, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date(current.date * 1000L)), false);
                TextView original = text(sourceContent, current.text, false);
                original.setTextSize(16);
                original.setTag(Theme.key_dialogTextBlack);
                original.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
                original.setTextIsSelectable(true);
                action(sourceContent, "跳回原消息", () -> verify(reference, current, request, sourceRequest));
            }
            @Override public void onInvalidated(String reason) {
                sourceError(reason + "\n这条历史总结仍保留为生成时的记录，不能将该引用视为当前有效依据。", request, sourceRequest, false);
            }
            @Override public void onError(String message) {
                sourceError(message, request, sourceRequest, true);
            }
        };
        if (jumpFrom == null) verifier.verify(reference, callback);
        else verifier.verify(jumpFrom, callback);
    }

    private boolean previewActive(int request, int sourceRequest) {
        return request == operation && sourceRequest == sourceOperation && active() && record != null
                && sourceDialog != null && sourceDialog.isShowing() && sourceContent != null;
    }

    private void sourceError(String message, int request, int sourceRequest, boolean retry) {
        if (!previewActive(request, sourceRequest)) return;
        if (verifier != null) {
            verifier.cancel();
            verifier = null;
        }
        sourceContent.removeAllViews();
        text(sourceContent, "原文未通过核验", true);
        text(sourceContent, message, false);
        if (retry && previewReference != null) {
            action(sourceContent, "重新核验", () -> resolveChatAndVerify(previewReference, request, sourceRequest));
        }
    }

    private void closePreview() {
        sourceOperation++;
        if (verifier != null) {
            verifier.cancel();
            verifier = null;
        }
        AlertDialog previous = sourceDialog;
        sourceDialog = null;
        sourceContent = null;
        previewReference = null;
        if (previous != null) previous.dismiss();
    }

    @Override
    public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (id == NotificationCenter.activeAccountChanged && UserConfig.selectedAccount != account
                || id == NotificationCenter.appDidLogout && changedAccount == account) {
            invalidateAccount();
            return;
        }
        if (changedAccount != account || previewReference == null || sourceDialog == null || !active()) return;
        boolean invalidated = false;
        if (id == NotificationCenter.messagesDeleted && args.length >= 3 && !((Boolean) args[2])) {
            TLRPC.Chat chat = getMessagesController().getChat(-previewReference.dialogId);
            long channel = (Long) args[1];
            invalidated = chat != null && channel == (ChatObject.isChannel(chat) ? chat.id : 0)
                    && ((ArrayList<Integer>) args[0]).contains(previewReference.id);
        } else if (id == NotificationCenter.replaceMessagesObjects && args.length >= 2
                && (Long) args[0] == previewReference.dialogId) {
            for (MessageObject message : (ArrayList<MessageObject>) args[1]) {
                if (message != null && message.messageOwner != null && message.getId() == previewReference.id
                        && message.getDialogId() == previewReference.dialogId) {
                    TLRPC.Message source = message.messageOwner;
                    invalidated = !previewReference.matchesText(source.message)
                            || previewReference.editDate != source.edit_date || previewReference.date != source.date
                            || previewReference.senderId != 0 && previewReference.senderId != DialogObject.getPeerDialogId(source.from_id)
                            || !SummaryHistoryLoader.isUsableText(source, getConnectionsManager().getCurrentTime());
                    break;
                }
            }
        } else if (id == NotificationCenter.updateInterfaces || id == NotificationCenter.chatInfoDidLoad) {
            TLRPC.Chat chat = getMessagesController().getChat(-previewReference.dialogId);
            invalidated = chat != null && ChatObject.isKickedFromChat(chat);
        }
        if (invalidated) {
            closePreview();
            transientNotice = "原消息或读取权限已变化，刚才的原文预览已关闭。历史总结仍保留；再次点击引用会重新核验。";
            showRecord();
        }
    }

    private void showStatus(String message, boolean retry) {
        if (content == null || destroyed) return;
        content.removeAllViews();
        LinearLayout status = section();
        text(status, message, true);
        if (retry) action(status, "重新读取", this::loadRecord);
    }

    private TextView text(LinearLayout parent, CharSequence value, boolean bold) {
        TextView view = new TextView(parent.getContext());
        view.setText(value);
        view.setTextSize(bold ? 16 : 14);
        int key = parent == sourceContent ? (bold ? Theme.key_dialogTextBlack : Theme.key_dialogTextGray)
                : bold ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText;
        view.setTag(key);
        view.setTextColor(getThemedColor(key));
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        parent.addView(view, LayoutHelper.createLinear(-1, -2, 0, bold ? 12 : 6, 0, 6));
        return view;
    }

    private TextView action(LinearLayout parent, String label, Runnable action) {
        TextView button = new TextView(parent.getContext());
        button.setText(label);
        button.setTag("action");
        FeatureUi.styleAction(button, getResourceProvider());
        button.setPadding(0, dp(12), 0, dp(12));
        button.setOnClickListener(view -> {
            if (active()) action.run();
        });
        parent.addView(button, LayoutHelper.createLinear(-1, -2, 0, 4, 0, 0));
        return button;
    }

    private void updateColors(View view) {
        if (view == null) return;
        Object tag = view.getTag();
        if ("surface".equals(tag)) view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        if (view instanceof TextView && tag instanceof Integer) ((TextView) view).setTextColor(getThemedColor((Integer) tag));
        if (view instanceof TextView && "action".equals(tag)) {
            FeatureUi.styleAction((TextView) view, getResourceProvider());
            view.setPadding(0, dp(12), 0, dp(12));
        }
        if (view instanceof TextView && "primary".equals(tag)) FeatureUi.stylePrimaryAction((TextView) view, getResourceProvider());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) updateColors(group.getChildAt(i));
        }
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        ThemeDescription.ThemeDescriptionDelegate refresh = () -> { updateColors(content); updateColors(sourceContent); };
        int[] keys = { Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhiteBlackText,
                Theme.key_windowBackgroundWhiteGrayText, Theme.key_windowBackgroundWhiteBlueText,
                Theme.key_windowBackgroundWhiteLinkText, Theme.key_listSelector, Theme.key_dialogTextBlack,
                Theme.key_dialogTextGray, Theme.key_featuredStickers_addButton, Theme.key_featuredStickers_buttonText,
                Theme.key_featuredStickers_addButtonPressed };
        for (int key : keys) descriptions.add(new ThemeDescription(null, 0, null, null, null, refresh, key));
        return descriptions;
    }

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }
}
