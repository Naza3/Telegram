/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Re-fetches a cited message before showing its source. Cached chat data is used only to address
 * the request; a successful preview requires the message and chat metadata from this response.
 * Callbacks run on the UI thread. A new verify/cancel invalidates all earlier callbacks.
 */
public final class SummarySourceVerifier {
    private static final long TIMEOUT_MS = 30_000;
    private static final String ACCOUNT_CHANGED = "当前 Telegram 账号已变化，请重新打开群聊并总结。";
    private static final String SOURCE_CHANGED = "原消息已修改，请重新总结。";

    public interface Callback {
        void onVerified(SummaryMessage current);
        void onError(String message);
        /** Fresh server evidence makes the existing summary/source snapshot unusable. */
        default void onInvalidated(String message) {
            onError(message);
        }
    }

    private final int account;
    private final long ownerId;
    private final long dialogId;
    private final long topicId;
    private final AtomicInteger generation = new AtomicInteger();
    private Run active;

    public SummarySourceVerifier(int account, long ownerId, long dialogId, long topicId) {
        this.account = account;
        this.ownerId = ownerId;
        this.dialogId = dialogId;
        this.topicId = topicId;
    }

    public void verify(SummaryMessage expected, Callback callback) {
        SummarySourceReference reference = null;
        if (expected != null) {
            try {
                reference = SummarySourceReference.from(expected);
            } catch (IllegalArgumentException ignored) {
                // Preserve the original API's asynchronous invalid-reference error path.
            }
        }
        verify(reference, callback);
    }

    /** Verifies a saved citation against fresh Telegram data without retaining cached original text. */
    public void verify(SummarySourceReference expected, Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback is required");
        }
        int version = generation.incrementAndGet();
        AndroidUtilities.runOnUIThread(() -> {
            if (generation.get() != version) {
                return;
            }
            cancelActive();
            if (!sameOwner()) {
                callback.onError(ACCOUNT_CHANGED);
                return;
            }
            if (!DialogObject.isChatDialog(dialogId) || topicId < 0 || topicId > Integer.MAX_VALUE
                    || expected == null || expected.dialogId != dialogId || expected.id <= 0) {
                callback.onError("这条引用不属于当前群聊范围，请重新总结。");
                return;
            }
            MessagesController controller = MessagesController.getInstance(account);
            if (controller.getChat(-dialogId) == null) {
                callback.onError("群信息尚未加载，请返回群聊后重新打开引用。");
                return;
            }
            Run run = new Run(version, expected, callback, ConnectionsManager.getInstance(account));
            active = run;
            try {
                TLRPC.InputPeer peer = controller.getInputPeer(dialogId);
                TLObject request;
                if (peer instanceof TLRPC.TL_inputPeerChannel
                        || peer instanceof TLRPC.TL_inputPeerChannelFromMessage) {
                    run.channel = true;
                    // getInputChannel(Chat) internally uses selectedAccount for FromMessage;
                    // convert this account's already-resolved peer to avoid that global state.
                    TLRPC.InputChannel channel;
                    if (peer instanceof TLRPC.TL_inputPeerChannelFromMessage) {
                        TLRPC.TL_inputChannelFromMessage fromMessage = new TLRPC.TL_inputChannelFromMessage();
                        fromMessage.peer = peer.peer;
                        fromMessage.msg_id = peer.msg_id;
                        channel = fromMessage;
                    } else {
                        channel = new TLRPC.TL_inputChannel();
                        channel.access_hash = peer.access_hash;
                    }
                    channel.channel_id = peer.channel_id;
                    TLRPC.TL_channels_getMessages get = new TLRPC.TL_channels_getMessages();
                    get.channel = channel;
                    get.id.add(expected.id);
                    request = get;
                } else if (peer instanceof TLRPC.TL_inputPeerChat) {
                    TLRPC.TL_messages_getMessages get = new TLRPC.TL_messages_getMessages();
                    get.id.add(expected.id);
                    request = get;
                } else {
                    fail(run, "无法确认当前群的消息访问方式，请返回群聊后重试。");
                    return;
                }
                run.timeout = () -> fail(run, "核验原消息超时，未显示旧正文。请检查 Telegram 连接后重试。");
                AndroidUtilities.runOnUIThread(run.timeout, TIMEOUT_MS);
                int requestId = run.connections.sendRequest(request, (response, error) ->
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!isActive(run)) {
                                return;
                            }
                            if (!sameOwner()) {
                                fail(run, ACCOUNT_CHANGED);
                            } else if (error != null) {
                                fail(run, "无法重新读取原消息，可能已删除或群权限已变化。请返回群聊确认后重试。");
                            } else if (!(response instanceof TLRPC.messages_Messages)
                                    || response instanceof TLRPC.TL_messages_messagesNotModified) {
                                fail(run, "Telegram 未返回可核验的原消息，请稍后重试。");
                            } else {
                                consume(run, (TLRPC.messages_Messages) response);
                            }
                        }), ConnectionsManager.RequestFlagFailOnServerErrors);
                run.connections.bindRequestToGuid(requestId, run.guid);
            } catch (RuntimeException ignored) {
                fail(run, "无法发起原消息核验，请检查 Telegram 连接后重试。");
            }
        });
    }

    public void cancel() {
        int version = generation.incrementAndGet();
        AndroidUtilities.runOnUIThread(() -> {
            if (generation.get() == version) {
                cancelActive();
            }
        });
    }

    private void consume(Run run, TLRPC.messages_Messages response) {
        if (!isActive(run)) {
            return;
        }
        if (!sameOwner()) {
            fail(run, ACCOUNT_CHANGED);
            return;
        }
        TLRPC.Chat chat = null;
        for (TLRPC.Chat item : response.chats) {
            if (item.id == -dialogId) {
                chat = item;
                break;
            }
        }
        // Never substitute a cached chat for missing/partial server permission metadata.
        if (chat == null || chat.min) {
            fail(run, "无法确认原消息的当前群权限，Telegram 未返回完整群信息。请返回群聊后重试。");
            return;
        }
        if (ChatObject.isKickedFromChat(chat)
                || chat.monoforum || chat.migrated_to != null
                || ChatObject.isChannel(chat) != run.channel
                || (topicId != 0 && !chat.forum)) {
            invalidate(run, "原消息的当前群权限已变化或不可访问。请返回群聊确认后重新总结。");
            return;
        }
        TLRPC.Message current = null;
        for (TLRPC.Message message : response.messages) {
            if (message.id == run.expected.id) {
                if (current != null) {
                    fail(run, "原消息核验结果不唯一，请重新总结。");
                    return;
                }
                current = message;
            }
        }
        if (current == null || current instanceof TLRPC.TL_messageEmpty) {
            invalidate(run, "原消息已删除或当前账号无法访问，请重新总结。");
            return;
        }
        if (current.peer_id == null || DialogObject.getPeerDialogId(current.peer_id) != dialogId
                || (current.peer_id.channel_id != 0) != run.channel
                || (topicId != 0 && MessageObject.getTopicId(account, current, true) != topicId)) {
            invalidate(run, "原消息已不在本次群聊/话题范围内，请重新总结。");
            return;
        }
        if (!SummaryHistoryLoader.isUsableText(current, run.connections.getCurrentTime())) {
            invalidate(run, "原消息现已过期、限时或不再是可用文字，未显示旧正文。请重新总结。");
            return;
        }
        long senderId = DialogObject.getPeerDialogId(current.from_id);
        if (!run.expected.matchesText(current.message) || run.expected.editDate != current.edit_date
                || run.expected.date != current.date
                || (run.expected.senderId != 0 && run.expected.senderId != senderId)) {
            invalidate(run, SOURCE_CHANGED);
            return;
        }
        int replyToId = 0;
        long replyToDialogId = 0;
        if (current.reply_to != null && !current.reply_to.reply_to_scheduled
                && !current.reply_to.reply_to_ephemeral && current.reply_to.reply_to_msg_id > 0) {
            replyToId = current.reply_to.reply_to_msg_id;
            replyToDialogId = current.reply_to.reply_to_peer_id == null ? dialogId
                    : DialogObject.getPeerDialogId(current.reply_to.reply_to_peer_id);
        }
        SummaryMessage verified = new SummaryMessage(dialogId, current.id, current.date,
                senderName(current, response), current.message, senderId, replyToId,
                replyToDialogId, false, current.out, current.edit_date, false, false);
        if (!sameOwner()) {
            fail(run, ACCOUNT_CHANGED);
            return;
        }
        cancelActive();
        run.callback.onVerified(verified);
    }

    private static String senderName(TLRPC.Message message, TLRPC.messages_Messages response) {
        long senderId = DialogObject.getPeerDialogId(message.from_id);
        if (senderId > 0) {
            for (TLRPC.User user : response.users) {
                if (user.id != senderId) {
                    continue;
                }
                String name = ((user.first_name == null ? "" : user.first_name) + " "
                        + (user.last_name == null ? "" : user.last_name)).trim();
                if (!name.isEmpty()) {
                    return name;
                }
                if (user.username != null && !user.username.isEmpty()) {
                    return "@" + user.username;
                }
                break;
            }
            return "用户 " + senderId;
        }
        if (senderId < 0) {
            for (TLRPC.Chat chat : response.chats) {
                if (chat.id == -senderId && chat.title != null && !chat.title.trim().isEmpty()) {
                    return chat.title;
                }
            }
        }
        return message.post_author == null || message.post_author.trim().isEmpty()
                ? "匿名发送者" : message.post_author;
    }

    private boolean sameOwner() {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && ownerId != 0
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    private boolean isActive(Run run) {
        return active == run && generation.get() == run.generation;
    }

    private void fail(Run run, String message) {
        fail(run, message, false);
    }

    private void invalidate(Run run, String message) {
        fail(run, message, true);
    }

    private void fail(Run run, String message, boolean invalidated) {
        if (!isActive(run)) {
            return;
        }
        boolean ownerMatches = sameOwner();
        String error = ownerMatches ? message : ACCOUNT_CHANGED;
        cancelActive();
        if (invalidated && ownerMatches) {
            run.callback.onInvalidated(error);
        } else {
            run.callback.onError(error);
        }
    }

    private void cancelActive() {
        if (active == null) {
            return;
        }
        if (active.timeout != null) {
            AndroidUtilities.cancelRunOnUIThread(active.timeout);
        }
        active.connections.cancelRequestsForGuid(active.guid);
        active = null;
    }

    private static final class Run {
        final int generation;
        final int guid = ConnectionsManager.generateClassGuid();
        final SummarySourceReference expected;
        final Callback callback;
        final ConnectionsManager connections;
        boolean channel;
        Runnable timeout;

        Run(int generation, SummarySourceReference expected, Callback callback, ConnectionsManager connections) {
            this.generation = generation;
            this.expected = expected;
            this.callback = callback;
            this.connections = connections;
        }
    }
}
