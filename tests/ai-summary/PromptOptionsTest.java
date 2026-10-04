package org.telegram.messenger.ai;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import java.util.Arrays;

/** Immutable direction, Unicode boundaries and real-owner/dialog/topic preference isolation. */
public final class PromptOptionsTest {
    private static int assertions;

    public static void main(String[] args) {
        unicodeAndSnapshots();
        preferences();
        priorRulesVersionKeepsSavedDirection();
        System.out.println("PromptOptionsTest: " + assertions + " assertions passed");
    }

    private static void unicodeAndSnapshots() {
        for (String id : Arrays.asList(PromptOptions.GENERAL, PromptOptions.PROJECT,
                PromptOptions.DECISIONS, PromptOptions.TODOS)) {
            PromptOptions options = new PromptOptions(id, "  保留不同意见\n和负责人  ");
            check(options.templateId.equals(id) && !PromptOptions.templateLabel(id).isEmpty(), "known template");
            check(options.templateVersion == PromptOptions.TEMPLATE_VERSION
                    && options.builtinRulesVersion == PromptOptions.BUILTIN_RULES_VERSION, "version snapshot");
            check(options.customInstructions.equals("保留不同意见\n和负责人"), "trim outer whitespace only");
        }
        PromptOptions exact = new PromptOptions(PromptOptions.GENERAL, repeat("😀", 1000));
        check(exact.customInstructions.length() == 2000, "limit counts Unicode code points, not UTF-16 units");
        fails(() -> new PromptOptions(PromptOptions.GENERAL, repeat("😀", 1001)), "over Unicode limit rejected");
        fails(() -> new PromptOptions("future-template", ""), "unknown template rejected");
        fails(() -> new PromptOptions(PromptOptions.GENERAL, "bad\uD83D"), "unpaired high surrogate rejected");
        fails(() -> new PromptOptions(PromptOptions.GENERAL, "bad\uDE00"), "unpaired low surrogate rejected");
        PromptOptions activeSnapshot = new PromptOptions(PromptOptions.PROJECT, "发布阻塞");
        PromptOptions editedNextTask = new PromptOptions(PromptOptions.TODOS, "负责人");
        check(activeSnapshot.templateId.equals(PromptOptions.PROJECT)
                && activeSnapshot.customInstructions.equals("发布阻塞") && !activeSnapshot.equals(editedNextTask),
                "editing the next task cannot mutate active immutable options");
        check(PromptOptions.DEFAULT.equals(new PromptOptions(PromptOptions.GENERAL, null)), "old settings migrate to default direction");
    }

    private static void preferences() {
        int account = 0;
        long owner = 5001;
        UserConfig.getInstance(account).setClientUserId(owner);
        PromptPreferences.clearOwner(account, owner);
        PromptOptions global = new PromptOptions(PromptOptions.PROJECT, "项目风险");
        PromptOptions chat = new PromptOptions(PromptOptions.DECISIONS, "保留反对理由");
        PromptOptions topic = new PromptOptions(PromptOptions.TODOS, "谁负责");
        PromptPreferences.Resolved initial = PromptPreferences.load(account, owner, -10, 0);
        check(initial.scope == PromptPreferences.Scope.BUILTIN && initial.options.equals(PromptOptions.DEFAULT), "built-in fallback");
        PromptPreferences.save(account, owner, -10, 0, PromptPreferences.Scope.ACCOUNT, global);
        check(PromptPreferences.load(account, owner, -11, 5).options.equals(global), "account default inherited by another chat/topic");
        PromptPreferences.save(account, owner, -10, 0, PromptPreferences.Scope.CHAT, chat);
        check(PromptPreferences.load(account, owner, -10, 0).scope == PromptPreferences.Scope.CHAT
                && PromptPreferences.load(account, owner, -10, 0).options.equals(chat), "chat beats account default");
        check(PromptPreferences.load(account, owner, -10, 42).options.equals(global), "topic does not accidentally inherit sibling whole-group preference");
        PromptPreferences.save(account, owner, -10, 42, PromptPreferences.Scope.CHAT, topic);
        check(PromptPreferences.load(account, owner, -10, 42).options.equals(topic), "topic preference isolated");
        PromptPreferences.save(account, owner, -10, 42, PromptPreferences.Scope.SESSION, global);
        check(PromptPreferences.load(account, owner, -10, 42).options.equals(topic), "session override never persists");
        PromptPreferences.clearChat(account, owner, -10, 42);
        check(PromptPreferences.load(account, owner, -10, 42).options.equals(global), "clear topic restores account default");
        PromptPreferences.clearAccountDefault(account, owner);
        check(PromptPreferences.load(account, owner, -11, 0).scope == PromptPreferences.Scope.BUILTIN, "clear account restores built-in");
        check(PromptPreferences.load(account, owner, -10, 0).options.equals(chat), "clearing account preserves explicit chat preference");

        MessagesController.getMainSettings(account).failNextCommits = 1;
        fails(() -> PromptPreferences.save(account, owner, -10, 0, PromptPreferences.Scope.CHAT, global), "failed commit reported");
        check(PromptPreferences.load(account, owner, -10, 0).options.equals(chat), "failed save restores prior in-memory direction");
        MessagesController.getMainSettings(account).failNextCommits = 2;
        fails(() -> PromptPreferences.save(account, owner, -10, 0, PromptPreferences.Scope.CHAT, global), "failed rollback remains an explicit failure");
        check(PromptPreferences.load(account, owner, -10, 0).options.equals(chat), "failed disk rollback still restores Android's in-memory preference");
        MessagesController.getMainSettings(account).failNextCommits = 1;
        fails(() -> PromptPreferences.clearChat(account, owner, -10, 0), "failed removal reported");
        check(PromptPreferences.load(account, owner, -10, 0).options.equals(chat), "failed removal preserves prior direction");
        MessagesController.getMainSettings(account).failNextCommits = 1;
        fails(() -> PromptPreferences.save(account, owner, -12, 0, PromptPreferences.Scope.CHAT, global), "failed first save reported");
        check(PromptPreferences.load(account, owner, -12, 0).scope == PromptPreferences.Scope.BUILTIN,
                "failed first save restores absence rather than saving unsafely");

        long replacementOwner = 5002;
        UserConfig.getInstance(account).setClientUserId(replacementOwner);
        PromptPreferences.clearOwner(account, replacementOwner);
        check(PromptPreferences.load(account, replacementOwner, -10, 0).scope == PromptPreferences.Scope.BUILTIN,
                "account slot reuse does not expose old owner's preferences");
        fails(() -> PromptPreferences.load(account, owner, -10, 0), "stale-owner read rejected");
        fails(() -> PromptPreferences.save(account, owner, -10, 0, PromptPreferences.Scope.CHAT, global), "stale-owner write rejected");
        PromptPreferences.save(account, replacementOwner, -10, 0, PromptPreferences.Scope.ACCOUNT, global);
        PromptPreferences.clearOwner(account, owner);
        check(PromptPreferences.load(account, replacementOwner, -10, 0).options.equals(global), "logout cleanup targets only named owner");
        UserConfig.getInstance(account).setClientUserId(owner);
        check(PromptPreferences.load(account, owner, -10, 0).scope == PromptPreferences.Scope.BUILTIN, "logout cleared old owner's chat preference");

        PromptPreferences.save(account, owner, -10, 0, PromptPreferences.Scope.ACCOUNT, global);
        PromptPreferences.save(account, owner, -10, 42, PromptPreferences.Scope.CHAT, topic);
        String damagedKey = null;
        for (java.util.Map.Entry<String, ?> entry : MessagesController.getMainSettings(account).getAll().entrySet()) {
            if (String.valueOf(entry.getValue()).contains("谁负责")) damagedKey = entry.getKey();
        }
        check(damagedKey != null, "find saved direction to simulate truncated preferences");
        MessagesController.getMainSettings(account).edit().putString(damagedKey, "{broken").commit();
        check(PromptPreferences.load(account, owner, -10, 42).options.equals(global), "corrupt chat record falls back to valid account default");
        PromptPreferences.clearOwner(account, owner);
        PromptPreferences.clearOwner(account, replacementOwner);
        UserConfig.getInstance(account).setClientUserId(1000);
    }

    private static void priorRulesVersionKeepsSavedDirection() {
        int account = 3;
        long owner = 5003;
        UserConfig.getInstance(account).setClientUserId(owner);
        PromptPreferences.clearOwner(account, owner);
        PromptOptions saved = new PromptOptions(PromptOptions.TODOS, "升级后仍保留任务取消与负责人");
        PromptPreferences.save(account, owner, -10, 0, PromptPreferences.Scope.CHAT, saved);
        String key = "local_ai_prompt_" + owner + "_chat_-10_topic_0";
        org.json.JSONObject record = new org.json.JSONObject(MessagesController.getMainSettings(account).getString(key, ""));
        check(record.getInt("rules_version") == 4, "new saved directions identify the alias-only conversation-source rules version");
        for (int priorVersion : new int[] {1, 2, 3}) {
            record.put("rules_version", priorVersion);
            MessagesController.getMainSettings(account).edit().putString(key, record.toString()).commit();
            PromptPreferences.Resolved loaded = PromptPreferences.load(account, owner, -10, 0);
            check(loaded.scope == PromptPreferences.Scope.CHAT && loaded.options.equals(saved), "prior saved template and custom direction remain usable after the rule update");
            check(loaded.options.builtinRulesVersion == 4, "a new request snapshots current rules rather than claiming to run old rules");
        }
        PromptPreferences.clearOwner(account, owner);
        UserConfig.getInstance(account).setClientUserId(1003);
    }

    private static String repeat(String text, int count) { StringBuilder value = new StringBuilder(); for (int i = 0; i < count; i++) value.append(text); return value.toString(); }
    private static void fails(Runnable operation, String reason) {
        try { operation.run(); } catch (IllegalArgumentException | IllegalStateException expected) { assertions++; return; }
        throw new AssertionError(reason);
    }
    private static void check(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
}
