/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.function.Consumer;

/** Real verifier logic with explicit server replies; this is not live MTProto/device validation. */
public final class SummarySourceVerifierTest {
    private static final long DIALOG = -100;
    private static final long OWNER = 1000;
    private static final int MESSAGE = 321;
    private static int assertions;
    private static int passed;
    private static boolean channel;
    private static final ConnectionsManager network = ConnectionsManager.getInstance(0);
    private static final MessagesController controller = MessagesController.getInstance(0);

    private static final class Result implements SummarySourceVerifier.Callback {
        SummaryMessage source;
        String error;
        int calls;
        int invalidations;
        public void onVerified(SummaryMessage value) { source = value; calls++; }
        public void onError(String value) { error = value; calls++; }
        public void onInvalidated(String value) { invalidations++; onError(value); }
    }

    public static void main(String[] args) {
        test("ordinary group uses getMessages and fresh sender metadata", SummarySourceVerifierTest::ordinary);
        test("channel uses account-specific input channel", SummarySourceVerifierTest::channel);
        test("read-only broadcast sources use channels.getMessages", SummarySourceVerifierTest::broadcast);
        test("from-message channel preserves the configured account's peer", SummarySourceVerifierTest::fromMessage);
        test("topic and General match the current message", SummarySourceVerifierTest::topics);
        test("original text and edit date must both match", SummarySourceVerifierTest::changed);
        test("same message ID in another peer cannot be substituted", SummarySourceVerifierTest::wrongPeer);
        test("deleted, empty and duplicate results fail closed", SummarySourceVerifierTest::missing);
        test("fresh chat permission metadata is required", SummarySourceVerifierTest::permissionMetadata);
        test("TTL and media filtering is shared", SummarySourceVerifierTest::messageEligibility);
        test("readable no-forwards source metadata does not revoke access", SummarySourceVerifierTest::noForwards);
        test("ordinary auto-delete text is verified until its server-time deadline", SummarySourceVerifierTest::autoDeleteDeadline);
        test("RPC errors and malformed replies never show cached text", SummarySourceVerifierTest::rpcFailure);
        test("invalid references do not call Telegram", SummarySourceVerifierTest::invalidScope);
        test("timeout releases requests and suppresses a late reply", SummarySourceVerifierTest::timeout);
        test("cancel and reuse invalidate old callbacks", SummarySourceVerifierTest::cancel);
        test("a new verification replaces an in-flight verification", SummarySourceVerifierTest::replace);
        test("separate verifier GUIDs do not cancel each other", SummarySourceVerifierTest::independent);
        test("owner changes before send, during request and before delivery", SummarySourceVerifierTest::ownerChange);
        test("invalidated callback remains compatible with older callers", SummarySourceVerifierTest::defaultInvalidation);
        System.out.println("SummarySourceVerifierTest: " + passed + " passed, " + assertions + " assertions");
    }

    private static void ordinary() {
        reset(false);
        Result result = start(0);
        check(network.next().request instanceof TLRPC.TL_messages_getMessages, "ordinary group RPC");
        TLRPC.TL_messages_getMessages request = (TLRPC.TL_messages_getMessages) network.next().request;
        check(request.id.size() == 1 && request.id.get(0) == MESSAGE, "only requested source ID");
        check(network.next().guid != 0 && AndroidUtilities.pendingTimers() == 1, "GUID and timeout missing");
        TLRPC.TL_messages_messages page = page(message());
        TLRPC.User user = new TLRPC.User(); user.id = 55; user.first_name = "新名字";
        page.users.add(user);
        network.reply(page, null);
        check(result.calls == 0, "network callback must be marshalled to UI thread");
        AndroidUtilities.drain();
        success(result);
        check(result.source.sender.equals("新名字"), "fresh sender name was not used");
        check(result.source.text.equals(expected().text) && result.source.editDate == 20, "source changed");
        check(result.source.replyToId == 111 && result.source.replyToDialogId == DIALOG, "reply metadata");
        check(!result.source.replyToSelfKnown, "unfetched reply provenance must remain unknown");
    }

    private static void channel() {
        reset(true);
        controller.getChat(-DIALOG).access_hash = 87654321;
        Result result = start(0);
        check(network.next().request instanceof TLRPC.TL_channels_getMessages, "channel RPC");
        TLRPC.TL_channels_getMessages request = (TLRPC.TL_channels_getMessages) network.next().request;
        check(request.channel.channel_id == -DIALOG && request.channel.access_hash == 87654321,
                "channel ID/access hash");
        check(request.id.size() == 1 && request.id.get(0) == MESSAGE, "channel message ID");
        deliver(page(message())); success(result);
    }

    private static void broadcast() {
        reset(true);
        TLRPC.Chat cached = controller.getChat(-DIALOG);
        cached.megagroup = false; cached.forum = false; cached.left = true; cached.access_hash = 76543;
        cached.banned_rights = new TLRPC.TL_chatBannedRights(); cached.banned_rights.send_messages = true;
        Result result = start(0);
        check(network.next().request instanceof TLRPC.TL_channels_getMessages, "broadcast verification RPC");
        TLRPC.TL_channels_getMessages request = (TLRPC.TL_channels_getMessages) network.next().request;
        check(request.channel.channel_id == -DIALOG && request.channel.access_hash == 76543,
                "broadcast verification lost channel access information");
        TLRPC.TL_messages_messages response = page(message());
        TLRPC.Chat fresh = response.chats.get(0);
        fresh.megagroup = false; fresh.forum = false; fresh.left = true;
        fresh.banned_rights = new TLRPC.TL_chatBannedRights(); fresh.banned_rights.send_messages = true;
        deliver(response); success(result);
        check(result.source.dialogId == DIALOG && result.source.id == MESSAGE,
                "broadcast source identity was changed");
    }

    private static void fromMessage() {
        reset(true);
        TLRPC.TL_inputPeerChannelFromMessage own = new TLRPC.TL_inputPeerChannelFromMessage();
        own.channel_id = -DIALOG; own.msg_id = 17;
        own.peer = new TLRPC.TL_inputPeerChat(); own.peer.chat_id = 444;
        controller.inputPeerOverrides.put(DIALOG, own);
        TLRPC.TL_inputPeerChannelFromMessage other = new TLRPC.TL_inputPeerChannelFromMessage();
        other.channel_id = -DIALOG; other.msg_id = 888;
        other.peer = new TLRPC.TL_inputPeerChat(); other.peer.chat_id = 999;
        MessagesController.getInstance(1).inputPeerOverrides.put(DIALOG, other);
        Result result = start(0);
        TLRPC.InputChannel requested = ((TLRPC.TL_channels_getMessages) network.next().request).channel;
        check(requested instanceof TLRPC.TL_inputChannelFromMessage, "from-message channel type");
        TLRPC.TL_inputChannelFromMessage fromMessage = (TLRPC.TL_inputChannelFromMessage) requested;
        check(fromMessage.peer == own.peer && fromMessage.msg_id == 17 && requested.channel_id == -DIALOG,
                "another account's access peer was used");
        deliver(page(message())); success(result);
        check(ConnectionsManager.getInstance(1).sent.isEmpty(), "sent on wrong account");
    }

    private static void topics() {
        reset(true);
        Result topic = start(42);
        TLRPC.Message matching = message(); matching.reply_to.forum_topic = true;
        matching.reply_to.reply_to_top_id = 42;
        deliver(page(matching)); success(topic);
        reset(true);
        Result wrong = start(42);
        TLRPC.Message another = message(); another.reply_to.forum_topic = true;
        another.reply_to.reply_to_top_id = 43;
        deliver(page(another)); error(wrong, "话题范围");
        invalidated(wrong);
        reset(true);
        Result general = start(1);
        deliver(page(message())); success(general);
        reset(true);
        Result noForum = start(42);
        TLRPC.TL_messages_messages page = page(matching); page.chats.get(0).forum = false;
        deliver(page); error(noForum, "群权限");
        invalidated(noForum);
    }

    private static void changed() {
        rejectMessage(m -> m.message = "编辑后的新正文", "原消息已修改");
        rejectMessage(m -> m.edit_date++, "原消息已修改");
        rejectMessage(m -> m.date++, "原消息已修改");
        rejectMessage(m -> m.from_id.user_id = 56, "原消息已修改");
        // Editing back to identical text is still a new version; edit_date catches it.
        rejectMessage(m -> m.edit_date = 0, "原消息已修改");
    }

    private static void wrongPeer() {
        rejectMessage(m -> m.peer_id.chat_id = 200, "群聊/话题范围");
        rejectMessage(m -> { m.peer_id = new TLRPC.TL_peerChannel(); m.peer_id.channel_id = -DIALOG; },
                "群聊/话题范围");
    }

    private static void missing() {
        reset(false); Result missing = start(0);
        deliver(page()); error(missing, "已删除");
        invalidated(missing);
        reset(false); Result empty = start(0);
        TLRPC.Message deleted = new TLRPC.TL_messageEmpty(); deleted.id = MESSAGE;
        deliver(page(deleted)); error(empty, "已删除");
        invalidated(empty);
        reset(false); Result duplicate = start(0);
        deliver(page(message(), message())); error(duplicate, "不唯一");
        temporary(duplicate);
        reset(false); Result unrelated = start(0);
        TLRPC.Message another = message(); another.id++;
        deliver(page(another)); error(unrelated, "已删除");
        invalidated(unrelated);
    }

    private static void permissionMetadata() {
        reset(false); Result missing = start(0);
        TLRPC.TL_messages_messages page = page(message()); page.chats.clear();
        deliver(page); error(missing, "群权限");
        temporary(missing);
        rejectChat(c -> c.min = true);
        rejectChat(c -> c.kicked = true);
        rejectChat(c -> c.deactivated = true);
        rejectChat(c -> c.monoforum = true);
        rejectChat(c -> c.migrated_to = new TLRPC.TL_inputChannel());
        rejectChat(c -> { c.banned_rights = new TLRPC.TL_chatBannedRights(); c.banned_rights.view_messages = true; });
        reset(false); Result forbidden = start(0);
        page = page(message()); page.chats.clear();
        TLRPC.Chat denied = new TLRPC.TL_chatForbidden(); denied.id = -DIALOG; page.chats.add(denied);
        deliver(page); error(forbidden, "群权限");
        invalidated(forbidden);
        reset(true); Result forbiddenChannel = start(0);
        page = page(message()); page.chats.clear();
        TLRPC.Chat deniedChannel = new TLRPC.TL_channelForbidden(); deniedChannel.id = -DIALOG;
        page.chats.add(deniedChannel);
        deliver(page); error(forbiddenChannel, "群权限");
        invalidated(forbiddenChannel);
        // Cached protected flags cannot override a fresh, readable server response either.
        reset(false); controller.getChat(-DIALOG).noforwards = true;
        Result fresh = start(0); deliver(page(message())); success(fresh);
    }

    private static void messageEligibility() {
        rejectMessage(m -> m.ttl_period = 1, "限时");
        rejectMessage(m -> m.ttl = 1, "限时");
        rejectMessage(m -> m.destroyTimeMillis = 1, "限时");
        rejectMessage(m -> m.ephemeralReceiverBotId = 1, "限时");
        rejectMessage(m -> m.quick_reply_shortcut_id = 1, "不再是可用文字");
        rejectMessage(m -> m.media = new TLRPC.TL_messageMediaPhoto(), "不再是可用文字");
        rejectMessage(m -> { m.media = new TLRPC.TL_messageMediaWebPage(); m.media.ttl_seconds = 1; }, "限时");
        rejectMessage(m -> m.action = new TLRPC.TL_messageActionEmpty(), "不再是可用文字");
        reset(false); Result web = start(0);
        TLRPC.Message link = message(); link.media = new TLRPC.TL_messageMediaWebPage();
        deliver(page(link)); success(web);
    }

    private static void autoDeleteDeadline() {
        reset(false);
        network.currentTimeOverride = 10030;
        ConnectionsManager.getInstance(1).currentTimeOverride = 999999;
        Result live = start(0);
        TLRPC.Message current = message(); current.ttl_period = 60;
        deliver(page(current)); success(live);
        check(live.source.text.equals(expected().text), "live auto-delete text was replaced");

        reset(false); network.currentTimeOverride = 10060;
        Result expired = start(0);
        current = message(); current.ttl_period = 60;
        deliver(page(current)); error(expired, "过期"); invalidated(expired);
    }

    private static void noForwards() {
        reset(false); controller.getChat(-DIALOG).noforwards = true;
        Result readable = start(0);
        TLRPC.Message current = message(); current.noforwards = true;
        TLRPC.TL_messages_messages response = page(current); response.chats.get(0).noforwards = true;
        deliver(response); success(readable);
        check(readable.source.text.equals(expected().text), "readable no-forwards text was lost");
    }

    private static void rpcFailure() {
        reset(false); Result error = start(0);
        TLRPC.TL_error denied = new TLRPC.TL_error(); denied.code = 400; denied.text = "CHANNEL_PRIVATE";
        network.reply(null, denied); AndroidUtilities.drain(); error(error, "权限已变化");
        temporary(error);
        reset(false); Result malformed = start(0);
        deliver(new TLObject()); error(malformed, "未返回可核验");
        temporary(malformed);
        reset(false); Result notModified = start(0);
        deliver(new TLRPC.TL_messages_messagesNotModified()); error(notModified, "未返回可核验");
        temporary(notModified);
    }

    private static void invalidScope() {
        reset(false);
        Result mismatch = new Result();
        new SummarySourceVerifier(0, OWNER, DIALOG, 0).verify(
                new SummaryMessage(-200, MESSAGE, 10000, "", "original"), mismatch);
        AndroidUtilities.drain(); error(mismatch, "群聊范围");
        check(network.sent.isEmpty(), "invalid dialog made a network request");
        Result negativeTopic = start(-1); error(negativeTopic, "群聊范围");
        check(network.sent.isEmpty(), "invalid topic made a network request");
        Result secret = new Result();
        long secretDialog = 0x400000000000002aL;
        new SummarySourceVerifier(0, OWNER, secretDialog, 0).verify(
                new SummaryMessage(secretDialog, MESSAGE, 10000, "", "original"), secret);
        AndroidUtilities.drain(); error(secret, "群聊范围");
        check(network.sent.isEmpty(), "secret chat made a source-verification request");
        controller.chats.clear(); Result noChat = start(0); error(noChat, "群信息尚未加载");
        check(network.sent.isEmpty(), "unknown chat made a network request");
    }

    private static void timeout() {
        reset(false); Result result = start(0);
        ConnectionsManager.Pending late = network.next();
        AndroidUtilities.fireTimers(); error(result, "超时");
        temporary(result);
        late.delegate.run(page(message()), null); AndroidUtilities.drain();
        check(result.calls == 1, "late reply after timeout invoked callback");
    }

    private static void cancel() {
        reset(false); SummarySourceVerifier verifier = verifier(0);
        Result old = new Result(); verifier.verify(expected(), old); AndroidUtilities.drain();
        ConnectionsManager.Pending late = network.next();
        verifier.cancel(); late.delegate.run(page(message()), null); AndroidUtilities.drain();
        check(old.calls == 0 && network.pending.isEmpty() && AndroidUtilities.pendingTimers() == 0,
                "cancel did not suppress or clean up");
        Result fresh = new Result(); verifier.verify(expected(), fresh); AndroidUtilities.drain();
        deliver(page(message())); success(fresh);
        Result neverSent = new Result(); verifier.verify(expected(), neverSent); verifier.cancel(); AndroidUtilities.drain();
        check(neverSent.calls == 0 && network.pending.isEmpty(), "cancel before UI send raced");
    }

    private static void replace() {
        reset(false); SummarySourceVerifier verifier = verifier(0);
        Result old = new Result(); verifier.verify(expected(), old); AndroidUtilities.drain();
        ConnectionsManager.Pending late = network.next();
        Result fresh = new Result(); verifier.verify(expected(), fresh);
        late.delegate.run(page(message()), null); AndroidUtilities.drain();
        check(old.calls == 0 && network.pending.size() == 1 && AndroidUtilities.pendingTimers() == 1,
                "replace leaked old run or callback");
        check(network.next().guid != late.guid, "new verification reused GUID");
        deliver(page(message())); success(fresh);
    }

    private static void independent() {
        reset(false); SummarySourceVerifier first = verifier(0), second = verifier(0);
        Result cancelled = new Result(), live = new Result();
        first.verify(expected(), cancelled); second.verify(expected(), live); AndroidUtilities.drain();
        check(network.pending.size() == 2, "independent requests not created");
        first.cancel(); AndroidUtilities.drain();
        check(network.pending.size() == 1 && AndroidUtilities.pendingTimers() == 1, "cancel crossed verifier GUID");
        deliver(page(message())); success(live); check(cancelled.calls == 0, "cancelled verifier called back");
    }

    private static void ownerChange() {
        reset(false); UserConfig.getInstance(0).setClientUserId(2000);
        Result before = start(0); error(before, "账号已变化");
        check(network.sent.isEmpty(), "sent using recycled account slot");
        reset(false); Result during = start(0); UserConfig.getInstance(0).setClientUserId(0);
        deliver(page(message())); error(during, "账号已变化");
        reset(false); Result queued = start(0); network.reply(page(message()), null);
        UserConfig.getInstance(0).setClientUserId(2000); AndroidUtilities.drain(); error(queued, "账号已变化");
        reset(false); Result timedOut = start(0); UserConfig.getInstance(0).setClientUserId(2000);
        AndroidUtilities.fireTimers(); error(timedOut, "账号已变化");
        temporary(timedOut);
    }

    private static void defaultInvalidation() {
        reset(false);
        int[] errors = {0};
        verifier(0).verify(expected(), new SummarySourceVerifier.Callback() {
            public void onVerified(SummaryMessage current) { throw new AssertionError("deleted source succeeded"); }
            public void onError(String message) { errors[0]++; }
        });
        AndroidUtilities.drain(); deliver(page());
        check(errors[0] == 1, "default onInvalidated must delegate to onError");
        check(network.pending.isEmpty() && AndroidUtilities.pendingTimers() == 0, "default callback leaked resources");
    }

    private static void rejectMessage(Consumer<TLRPC.Message> change, String expectedError) {
        reset(false); Result result = start(0); TLRPC.Message value = message(); change.accept(value);
        deliver(page(value)); error(result, expectedError);
        invalidated(result);
    }

    private static void rejectChat(Consumer<TLRPC.Chat> change) {
        reset(false); Result result = start(0); TLRPC.TL_messages_messages page = page(message());
        change.accept(page.chats.get(0)); deliver(page); error(result, "群权限");
        if (page.chats.get(0).min) temporary(result); else invalidated(result);
    }

    private static Result start(long topic) {
        Result result = new Result(); verifier(topic).verify(expected(), result); AndroidUtilities.drain(); return result;
    }

    private static SummarySourceVerifier verifier(long topic) {
        return new SummarySourceVerifier(0, OWNER, DIALOG, topic);
    }

    private static SummaryMessage expected() {
        return new SummaryMessage(DIALOG, MESSAGE, 10000, "旧名字", "原消息正文", 55, 111,
                DIALOG, false, false, 20, true, true);
    }

    private static TLRPC.Message message() {
        TLRPC.Message message = new TLRPC.TL_message();
        message.id = MESSAGE; message.date = 10000; message.edit_date = 20; message.message = expected().text;
        message.peer_id = channel ? new TLRPC.TL_peerChannel() : new TLRPC.TL_peerChat();
        if (channel) message.peer_id.channel_id = -DIALOG; else message.peer_id.chat_id = -DIALOG;
        message.from_id = new TLRPC.TL_peerUser(); message.from_id.user_id = 55;
        message.reply_to = new TLRPC.TL_messageReplyHeader(); message.reply_to.reply_to_msg_id = 111;
        return message;
    }

    private static TLRPC.Chat chat() {
        TLRPC.Chat chat = channel ? new TLRPC.TL_channel() : new TLRPC.TL_chat();
        chat.id = -DIALOG; chat.title = "群聊"; chat.megagroup = channel; chat.forum = channel; return chat;
    }

    private static TLRPC.TL_messages_messages page(TLRPC.Message... messages) {
        TLRPC.TL_messages_messages page = new TLRPC.TL_messages_messages();
        page.chats.add(chat()); for (TLRPC.Message message : messages) page.messages.add(message); return page;
    }

    private static void deliver(TLObject response) { network.reply(response, null); AndroidUtilities.drain(); }

    private static void reset(boolean useChannel) {
        AndroidUtilities.reset(); channel = useChannel;
        for (int account = 0; account < 4; account++) {
            ConnectionsManager.getInstance(account).reset();
            UserConfig.getInstance(account).setClientUserId(OWNER + account);
            MessagesController c = MessagesController.getInstance(account);
            c.chats.clear(); c.users.clear(); c.fullChats.clear(); c.inputPeerOverrides.clear();
        }
        controller.chats.put(-DIALOG, chat());
    }

    private static void success(Result result) {
        check(result.calls == 1 && result.source != null && result.error == null, "verification failed: " + result.error);
        check(network.pending.isEmpty() && AndroidUtilities.pendingTimers() == 0, "success leaked request/timer");
    }

    private static void error(Result result, String expected) {
        check(result.calls == 1 && result.source == null && result.error != null && result.error.contains(expected),
                "expected error " + expected + ", got " + result.error);
        check(network.pending.isEmpty() && AndroidUtilities.pendingTimers() == 0, "error leaked request/timer");
    }

    private static void invalidated(Result result) {
        check(result.invalidations == 1, "confirmed source change must invalidate the summary snapshot");
    }

    private static void temporary(Result result) {
        check(result.invalidations == 0, "temporary verification failure must not claim the source changed");
    }

    private static void test(String name, Runnable action) {
        try { action.run(); passed++; }
        catch (Throwable failure) { throw new AssertionError(name, failure); }
    }

    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
