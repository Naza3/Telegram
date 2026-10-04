/* Telegram for Android; GPL version 2 or later. */
package org.telegram.ui.Components;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.SummaryHistoryStore;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.TopicsFragment;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Explicit, owner-bound handoff to Telegram's editor. This class never sends messages. */
public final class SummaryPublishHelper {
    private static final WeakHashMap<BaseFragment, Object> opening = new WeakHashMap<>();
    private static final ArrayList<WeakReference<ChatActivity>> editors = new ArrayList<>();

    private SummaryPublishHelper() { }

    public static final class Draft {
        public final int account;
        public final long ownerId;
        public final SummaryHistoryStore.Record record;
        public final long topicId;

        private Draft(int account, long ownerId, SummaryHistoryStore.Record record, long topicId) {
            this.account = account;
            this.ownerId = ownerId;
            this.record = record;
            this.topicId = topicId;
        }
    }

    /** Call only from an explicit edit-and-send action, with the history page's captured owner. */
    public static void open(BaseFragment fragment, int account, long ownerId, SummaryHistoryStore.Record record) {
        if (!active(fragment, account, ownerId) || record == null || opening.containsKey(fragment)) return;
        final Object operation = new Object();
        opening.put(fragment, operation);
        Utilities.globalQueue.postRunnable(() -> {
            SummaryHistoryStore.Record saved = null;
            String error = null;
            try {
                saved = SummaryHistoryStore.get(account, ownerId, record.id);
                if (saved == null) error = "这份总结尚未保存或已被删除，请重新打开总结记录。";
                else if (saved.dialogId != record.dialogId || saved.topicId != record.topicId
                        || !saved.summary.equals(record.summary)) {
                    error = "这份总结记录已变化，请重新打开后再编辑并发回。";
                }
            } catch (RuntimeException e) {
                error = "无法读取这份总结，请重新打开总结记录。";
            }
            final SummaryHistoryStore.Record result = saved;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (opening.get(fragment) != operation) return;
                opening.remove(fragment);
                if (!active(fragment, account, ownerId)) return;
                if (failure != null) {
                    showError(fragment, failure);
                    return;
                }
                TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-result.dialogId);
                if (chat != null && ChatObject.isForum(chat) && result.topicId == 0) {
                    // A whole-forum result has no original topic. Never silently choose General.
                    Bundle args = new Bundle();
                    args.putLong("chat_id", -result.dialogId);
                    args.putBoolean("for_select", true);
                    TopicsFragment picker = new TopicsFragment(args);
                    picker.setCurrentAccount(account);
                    picker.setOnTopicSelectedListener(topic -> {
                        if (topic != null && active(picker, account, ownerId)) {
                            if (openEditor(picker, new Draft(account, ownerId, result, topic.id), true)) {
                                picker.setOnTopicSelectedListener(null);
                            }
                        }
                    });
                    fragment.presentFragment(picker);
                } else {
                    openEditor(fragment, new Draft(account, ownerId, result, result.topicId), false);
                }
            });
        });
    }

    private static boolean openEditor(BaseFragment fragment, Draft draft, boolean replacePicker) {
        if (!active(fragment, draft.account, draft.ownerId)) return false;
        String error = targetError(draft);
        if (error == null) error = savedDraftError(draft);
        if (error == null && fragment.getParentLayout() != null) {
            for (BaseFragment item : fragment.getParentLayout().getFragmentStack()) {
                if (!(item instanceof ChatActivity) || item.getCurrentAccount() != draft.account) continue;
                ChatActivity existing = (ChatActivity) item;
                if (existing.getDialogId() == draft.record.dialogId
                        && (existing.getTopicId() == draft.topicId || existing.getTopicId() == 0
                        || existing.getDraftThreadId() == draft.topicId)) {
                    error = existing.getSummaryPublishConflict();
                    if (error != null) break;
                }
            }
        }
        if (error != null) {
            showError(fragment, error);
            return false;
        }
        Bundle args = new Bundle();
        args.putLong("chat_id", -draft.record.dialogId);
        ChatActivity chat = new ChatActivity(args);
        // Account must be bound before setThreadMessages touches controller-dependent UI state.
        chat.setCurrentAccount(draft.account);
        if (draft.topicId != 0) {
            TLRPC.TL_forumTopic topic = MessagesController.getInstance(draft.account)
                    .getTopicsController().findTopic(-draft.record.dialogId, draft.topicId);
            if (topic == null || topic.topicStartMessage == null) {
                showError(fragment, "原话题信息尚未加载，请先打开该话题后重试。");
                return false;
            }
            ArrayList<MessageObject> thread = new ArrayList<>();
            thread.add(new MessageObject(draft.account, topic.topicStartMessage, false, false));
            chat.setThreadMessages(thread, MessagesController.getInstance(draft.account)
                    .getChat(-draft.record.dialogId), topic.id, topic.read_inbox_max_id,
                    topic.read_outbox_max_id, topic);
        }
        chat.setSummaryPublishDraft(draft);
        editors.removeIf(reference -> reference.get() == null);
        editors.add(new WeakReference<>(chat));
        return fragment.presentFragment(chat, replacePicker);
    }

    public static boolean sameOwner(Draft draft) {
        return draft != null && draft.account >= 0 && draft.account < UserConfig.MAX_ACCOUNT_COUNT
                && draft.ownerId > 0 && UserConfig.getInstance(draft.account).getClientUserId() == draft.ownerId;
    }

    /** Recheck before filling and again before the native send action. */
    public static String targetError(Draft draft) {
        if (!sameOwner(draft)) return "当前账号已变化，请重新打开总结记录。";
        TLRPC.Chat chat = MessagesController.getInstance(draft.account).getChat(-draft.record.dialogId);
        if (chat == null || chat instanceof TLRPC.TL_chatForbidden || chat instanceof TLRPC.TL_channelForbidden
                || ChatObject.isNotInChat(chat) || chat.deactivated || chat.migrated_to != null || ChatObject.isMonoForum(chat)) {
            return "来源群当前不可发送，请先打开原群确认状态。";
        }
        if (!ChatObject.canWriteToChat(chat) || !ChatObject.canSendPlain(chat)) {
            return "当前没有在来源群发送文字的权限。";
        }
        if (draft.record.topicId != 0 && draft.record.topicId != draft.topicId) {
            return "发送话题与原话题不一致，请重新打开总结记录。";
        }
        if (ChatObject.isForum(chat)) {
            if (draft.topicId <= 0) return "请先选择来源群中的发送话题。";
            TLRPC.TL_forumTopic topic = MessagesController.getInstance(draft.account)
                    .getTopicsController().findTopic(chat.id, draft.topicId);
            if (topic == null || topic.topicStartMessage == null) {
                return "原话题信息尚未加载，请先打开该话题后重试。";
            }
            if (topic.closed && !ChatObject.canManageTopic(draft.account, chat, topic)) {
                return "该话题已关闭，当前无法发送。";
            }
        } else if (draft.topicId != 0) {
            return "来源群的话题状态已变化，请重新打开原群。";
        }
        return null;
    }

    public static String savedDraftError(Draft draft) {
        if (!sameOwner(draft)) return "当前账号已变化，请重新打开总结记录。";
        MediaDataController media = MediaDataController.getInstance(draft.account);
        if (!UserConfig.getInstance(draft.account).draftsLoaded) {
            media.loadDraftsIfNeed();
            return "Telegram 草稿尚未同步完成，请稍后重新点击编辑并发回。";
        }
        TLRPC.DraftMessage existing = media.getDraft(draft.record.dialogId, draft.topicId);
        if (existing != null && (!TextUtils.isEmpty(existing.message) || existing.reply_to != null
                || existing.rich_message != null || existing.suggested_post != null || existing.effect != 0)
                || media.getDraftVoice(draft.record.dialogId, draft.topicId) != null) {
            return "原聊天已有草稿，请先处理原草稿后再填入总结。";
        }
        return null;
    }

    public static void showError(BaseFragment fragment, String error) {
        if (fragment != null && !fragment.isFinished && fragment.getParentActivity() != null) {
            AlertsCreator.showSimpleAlert(fragment, "编辑并发回", error);
        }
    }

    /** The displayed strings are the final native message bodies, never reparsed after confirmation. */
    public static AlertDialog showPartsPreview(BaseFragment fragment, Draft draft, List<String> parts,
                                               Runnable confirm, Runnable cancel) {
        if (!active(fragment, draft.account, draft.ownerId) || parts.isEmpty()) return null;
        LinearLayout body = new LinearLayout(fragment.getParentActivity());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        LinkedHashMap<TextView, Integer> textColorKeys = new LinkedHashMap<>();
        FeatureUi.bindThemeUpdates(body, () -> {
            for (Map.Entry<TextView, Integer> entry : textColorKeys.entrySet()) {
                entry.getKey().setTextColor(Theme.getColor(entry.getValue(), resourcesProvider));
            }
        });
        TLRPC.Chat chat = MessagesController.getInstance(draft.account).getChat(-draft.record.dialogId);
        String destination = chat == null ? "来源群" : chat.title;
        if (draft.topicId > 0) {
            TLRPC.TL_forumTopic topic = MessagesController.getInstance(draft.account).getTopicsController()
                    .findTopic(-draft.record.dialogId, draft.topicId);
            if (topic != null) destination += " · " + topic.title;
        }
        addPreviewNote(body, "发送到：" + destination + "\n共 " + parts.size()
                + " 条，将按下列顺序发送。取消会保留编辑器正文。", resourcesProvider, textColorKeys);
        for (int i = 0; i < parts.size(); i++) {
            addPreviewText(body, "第 " + (i + 1) + " / " + parts.size() + " 条", true, resourcesProvider, textColorKeys);
            addPreviewText(body, parts.get(i), false, resourcesProvider, textColorKeys);
        }
        addPreviewNote(body, "如部分消息失败，请在聊天中重试失败的原消息，避免整批重复发送。",
                resourcesProvider, textColorKeys);
        boolean[] confirmed = {false};
        AlertDialog dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle("确认发送总结")
                .setView(body)
                .setPositiveButton("确认发送 " + parts.size() + " 条", (ignored, which) -> {
                    confirmed[0] = true;
                    confirm.run();
                })
                .setNegativeButton("返回编辑", null)
                .create();
        return fragment.showDialog(dialog, false, ignored -> { if (!confirmed[0]) cancel.run(); }) == null ? null : dialog;
    }

    private static TextView addPreviewText(LinearLayout body, String value, boolean label,
                                           Theme.ResourcesProvider resourcesProvider,
                                           Map<TextView, Integer> textColorKeys) {
        TextView text = new TextView(body.getContext());
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, label ? 14 : 16);
        int colorKey = label ? Theme.key_dialogTextLink : Theme.key_dialogTextBlack;
        textColorKeys.put(text, colorKey);
        text.setTextColor(Theme.getColor(colorKey, resourcesProvider));
        text.setLineSpacing(AndroidUtilities.dp(3), 1f);
        text.setTextIsSelectable(!label);
        if (label) text.setTypeface(AndroidUtilities.bold());
        body.addView(text, LayoutHelper.createLinear(-1, -2, 0, label ? 16 : 8, 0, 8));
        return text;
    }

    private static void addPreviewNote(LinearLayout body, String value, Theme.ResourcesProvider resourcesProvider,
                                       Map<TextView, Integer> textColorKeys) {
        TextView text = addPreviewText(body, value, true, resourcesProvider, textColorKeys);
        text.setTypeface(Typeface.DEFAULT);
        textColorKeys.put(text, Theme.key_dialogTextGray);
        text.setTextColor(Theme.getColor(Theme.key_dialogTextGray, resourcesProvider));
    }

    /** Called by logout after the original owner has been captured. No native draft is sent here. */
    public static void clearOwner(int account, long ownerId) {
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = editors.size() - 1; i >= 0; i--) {
                ChatActivity chat = editors.get(i).get();
                if (chat == null) editors.remove(i);
                else chat.clearSummaryPublishDraft(account, ownerId);
            }
        });
    }

    private static boolean active(BaseFragment fragment, int account, long ownerId) {
        return fragment != null && !fragment.isFinished && !fragment.isPaused() && fragment.getParentActivity() != null
                && fragment.getCurrentAccount() == account && account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                && ownerId > 0 && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }
}
