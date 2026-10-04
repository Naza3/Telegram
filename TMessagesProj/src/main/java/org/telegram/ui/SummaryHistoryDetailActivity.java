/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
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
import org.telegram.messenger.ai.SummarySourceReference;
import org.telegram.messenger.ai.SummarySourceVerifier;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Displays a saved result, re-fetching and verifying originals only after a reference is tapped. */
public final class SummaryHistoryDetailActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int COPY = 1;
    private static final Pattern REFERENCE = Pattern.compile("\\[m([0-9]+)\\]");
    private static final Pattern HEADING = Pattern.compile("(?m)^(?:#{1,6}\\s*)?(?:\\*\\*)?【?(话题|结论|待办)】?(?:\\*\\*)?[：:]?\\s*$");
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
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(24));
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
        transientNotice = null;
        showStatus("账号已切换或退出，请从当前账号重新打开总结历史。", false);
    }

    private void loadRecord() {
        if (!active()) return;
        final int request = ++operation;
        closePreview();
        record = null;
        transientNotice = null;
        showStatus("正在读取总结…", false);
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner()) return;
            try {
                SummaryHistoryStore.Record loaded = SummaryHistoryStore.get(account, ownerId, recordId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    record = loaded;
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
        text(content, SummaryHistoryActivity.chatLabel(record), true);
        text(content, "生成于 " + SummaryHistoryActivity.formatTime(record.generatedAtMillis)
                + " · " + record.sources.size() + " 条原文", false);
        text(content, "这是生成当时保存的结果，不代表当前最新消息或模型。原消息可能已修改或删除。"
                + (record.sourceLinks ? "点击引用后才联网重新核验。" : ""), false);
        text(content, record.rangeLabel + (record.partial ? " · 部分覆盖" : ""), true);
        if (!record.coverageNote.isEmpty()) text(content, record.coverageNote, false);
        if (!record.templateLabel.isEmpty()) text(content, "总结方向：" + record.templateLabel, false);
        if (!record.customInstructions.isEmpty()) text(content, "生成时补充要求：" + record.customInstructions, false);
        text(content, "请求模型：" + (record.model.isEmpty() ? "服务当前模型（未指定名称）" : record.model)
                + "。此名称不是实际模型版本的核验证明。", false);
        if (transientNotice != null) text(content, transientNotice, true);
        TextView summary = text(content, linkSources(record), false);
        summary.setTextSize(16);
        summary.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        if (record.sourceLinks) summary.setMovementMethod(LinkMovementMethod.getInstance());
        summary.setLinksClickable(record.sourceLinks);
        text(content, "本机仅保存摘要和来源校验信息，不保存原消息正文。", false);
    }

    private CharSequence linkSources(SummaryHistoryStore.Record snapshot) {
        final int request = operation;
        SpannableStringBuilder linked = new SpannableStringBuilder(snapshot.summary);
        Matcher headings = HEADING.matcher(snapshot.summary);
        while (headings.find()) linked.setSpan(new StyleSpan(Typeface.BOLD), headings.start(), headings.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
        text(content, message, true);
        if (retry) action(content, "重新读取", this::loadRecord);
    }

    private TextView text(LinearLayout parent, CharSequence value, boolean bold) {
        TextView view = new TextView(parent.getContext());
        view.setText(value);
        view.setTextSize(bold ? 16 : 14);
        view.setTextColor(getThemedColor(bold ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        parent.addView(view, LayoutHelper.createLinear(-1, -2, 0, bold ? 12 : 6, 0, 6));
        return view;
    }

    private void action(LinearLayout parent, String label, Runnable action) {
        TextView button = text(parent, label, true);
        button.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), dp(10), dp(8), dp(10));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(6),
                getThemedColor(Theme.key_windowBackgroundGray), getThemedColor(Theme.key_listSelector)));
        button.setFocusable(true);
        button.setOnClickListener(view -> {
            if (active()) action.run();
        });
    }

    private static int dp(float value) {
        return AndroidUtilities.dp(value);
    }
}
