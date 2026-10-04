/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.SummaryHistoryQuery;
import org.telegram.messenger.ai.SummaryHistoryStore;
import org.telegram.messenger.ai.SummaryPublishStore;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SummaryPublishStatusText;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/** Shared list view, not a nested Fragment. Host forwards visible-tab lifecycle events. */
public final class SummaryHistoryPanel implements NotificationCenter.NotificationCenterDelegate {
    private final BaseFragment parent;
    private final int account;
    private final long ownerId;
    private final long baseDialogId;
    private final long baseTopicId;
    private final ArrayList<SummaryHistoryStore.Record> records = new ArrayList<>();
    private final HashMap<String, SummaryPublishStore.Attempt> latestAttempts = new HashMap<>();
    private boolean publishStatusUnavailable;
    private long filterDialogId;
    private long filterTopicId;
    private LinearLayout content;
    private TextView scopeButton;
    private EditText search;
    private boolean resumed;
    private boolean destroyed;
    private boolean invalidated;
    private boolean loading = true;
    private int operation;
    private String error;
    private String query = "";

    public SummaryHistoryPanel(BaseFragment parent, int account, long ownerId) {
        this(parent, account, ownerId, 0, -1);
    }

    public SummaryHistoryPanel(BaseFragment parent, int account, long ownerId, long dialogId, long topicId) {
        if (parent == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || ownerId <= 0
                || dialogId > 0 || dialogId == Long.MIN_VALUE || topicId < -1 || topicId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("历史记录范围无效。");
        }
        this.parent = parent;
        this.account = account;
        this.ownerId = ownerId;
        this.baseDialogId = this.filterDialogId = dialogId;
        this.baseTopicId = this.filterTopicId = dialogId == 0 ? -1 : topicId;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.appDidLogout);
    }

    public View createView(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(Theme.key_windowBackgroundGray));
        search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        search.setHintTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        search.setHint("搜索群名或总结正文");
        search.setContentDescription("搜索当前账号本机保存的总结，不联网");
        search.setMinHeight(dp(48));
        search.setPadding(dp(16), dp(8), dp(16), dp(8));
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                render();
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        root.addView(search, LayoutHelper.createLinear(-1, -2, 12, 4, 12, 0));
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        scopeButton = button(toolbar, "全部群／话题", this::chooseScope);
        button(toolbar, "刷新", this::refresh);
        button(toolbar, "清空", this::confirmClear);
        root.addView(toolbar, LayoutHelper.createLinear(-1, -2, 12, 0, 12, 0));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, LayoutHelper.createLinear(-1, 0, 1f));
        render();
        return root;
    }

    public void onResume() {
        resumed = true;
        if (!sameOwner()) invalidate(); else reload(null);
    }

    public void onPause() { resumed = false; operation++; }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        resumed = false;
        operation++;
        records.clear();
        latestAttempts.clear();
        query = "";
        if (search != null) search.setText("");
        if (content != null) content.removeAllViews();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.appDidLogout);
    }

    public void refresh() { reload(null); }

    private boolean sameOwner() {
        return !destroyed && !invalidated && UserConfig.selectedAccount == account
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    private boolean active() {
        if (!sameOwner()) { invalidate(); return false; }
        return resumed && parent.getParentActivity() != null && !parent.getParentActivity().isFinishing();
    }

    private void invalidate() {
        if (destroyed || invalidated) return;
        invalidated = true;
        operation++;
        loading = false;
        error = "账号已切换或退出，请从当前账号重新打开总结历史。";
        records.clear();
        latestAttempts.clear();
        query = "";
        if (search != null) { search.setText(""); search.setEnabled(false); }
        parent.dismissCurrentDialog();
        render();
    }

    @Override public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (id == NotificationCenter.activeAccountChanged && UserConfig.selectedAccount != account
                || id == NotificationCenter.appDidLogout && changedAccount == account) invalidate();
    }

    private void reload(Runnable mutation) {
        if (!active()) return;
        final int request = ++operation;
        loading = true;
        error = null;
        records.clear();
        latestAttempts.clear();
        render();
        Utilities.globalQueue.postRunnable(() -> {
            if (!sameOwner()) return;
            try {
                if (mutation != null) mutation.run();
                List<SummaryHistoryStore.Record> loaded = SummaryHistoryStore.list(account, ownerId, baseDialogId, baseTopicId);
                HashSet<String> ids = new HashSet<>();
                for (SummaryHistoryStore.Record record : loaded) ids.add(record.id);
                Map<String, SummaryPublishStore.Attempt> attempts = new HashMap<>();
                boolean statusUnavailable = false;
                try { if (!ids.isEmpty()) attempts = SummaryPublishStore.latestForRecords(account, ownerId, ids); }
                catch (RuntimeException failure) { statusUnavailable = true; }
                final Map<String, SummaryPublishStore.Attempt> loadedAttempts = attempts;
                final boolean unavailable = statusUnavailable;
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    loading = false;
                    records.addAll(loaded);
                    latestAttempts.putAll(loadedAttempts);
                    publishStatusUnavailable = unavailable;
                    render();
                });
            } catch (RuntimeException failure) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (request != operation || !active()) return;
                    loading = false;
                    error = "无法读取或更新总结历史：" + SummaryHistoryActivity.failureMessage(failure);
                    render();
                });
            }
        });
    }

    public void confirmClear() {
        if (!active() || loading) return;
        final long dialogId = filterDialogId;
        final long topicId = filterTopicId;
        String scope = dialogId == 0 ? "当前账号的全部总结历史" : topicId == -1 ? "所选群及所有话题的总结历史"
                : topicId == 0 ? "所选群中不限定话题的总结历史" : "所选话题的总结历史";
        parent.showDialog(new AlertDialog.Builder(parent.getParentActivity(), parent.getResourceProvider())
                .setTitle("清空总结历史")
                .setMessage("确定删除" + scope + "？搜索关键词不会缩小删除范围。\n\n只删除本机总结，不删除 Telegram 消息，删除后无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    if (active()) reload(() -> SummaryHistoryStore.clear(account, ownerId, dialogId, topicId));
                }).create());
    }

    private void confirmDelete(SummaryHistoryStore.Record record) {
        if (!active() || loading) return;
        parent.showDialog(new AlertDialog.Builder(parent.getParentActivity(), parent.getResourceProvider())
                .setTitle("删除这条总结")
                .setMessage(SummaryHistoryActivity.chatLabel(record) + "\n" + SummaryHistoryActivity.formatTime(record.generatedAtMillis)
                        + "\n\n仅删除本机总结，Telegram 消息不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (active()) reload(() -> SummaryHistoryStore.delete(account, ownerId, record.id));
                }).create());
    }

    private void chooseScope() {
        if (!active() || loading) return;
        if (baseDialogId != 0) { chooseTopic(baseDialogId); return; }
        LinkedHashMap<Long, String> chats = new LinkedHashMap<>();
        for (SummaryHistoryStore.Record record : records) {
            chats.putIfAbsent(record.dialogId, record.chatTitle.isEmpty() ? "聊天 " + record.dialogId : record.chatTitle);
        }
        ArrayList<Long> ids = new ArrayList<>(chats.keySet());
        ArrayList<String> labels = new ArrayList<>();
        labels.add("全部群／话题");
        for (Long id : ids) labels.add(chats.get(id));
        parent.showDialog(new AlertDialog.Builder(parent.getParentActivity(), parent.getResourceProvider())
                .setTitle("筛选本机总结的来源群")
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (!active()) return;
                    if (which == 0) { filterDialogId = 0; filterTopicId = -1; render(); }
                    else chooseTopic(ids.get(which - 1));
                }).create());
    }

    private void chooseTopic(long dialogId) {
        if (!active()) return;
        if (baseTopicId >= 0) { filterDialogId = dialogId; filterTopicId = baseTopicId; render(); return; }
        ArrayList<Long> topics = new ArrayList<>();
        for (SummaryHistoryStore.Record record : records) {
            if (record.dialogId == dialogId && !topics.contains(record.topicId)) topics.add(record.topicId);
        }
        ArrayList<String> labels = new ArrayList<>();
        labels.add("本群全部总结");
        for (Long topic : topics) labels.add(topic == 0 ? "整个聊天的总结" : "话题 " + topic);
        parent.showDialog(new AlertDialog.Builder(parent.getParentActivity(), parent.getResourceProvider())
                .setTitle("筛选话题")
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (!active()) return;
                    filterDialogId = dialogId;
                    filterTopicId = which == 0 ? -1 : topics.get(which - 1);
                    render();
                }).create());
    }

    private String scopeLabel() {
        if (filterDialogId == 0) return "全部群／话题";
        String title = "所选群";
        for (SummaryHistoryStore.Record record : records) {
            if (record.dialogId == filterDialogId && !record.chatTitle.isEmpty()) { title = record.chatTitle; break; }
        }
        return title + (filterTopicId == -1 ? " · 全部" : filterTopicId == 0 ? " · 整群" : " · 话题 " + filterTopicId);
    }

    private void render() {
        if (content == null || destroyed) return;
        content.removeAllViews();
        scopeButton.setText(scopeLabel());
        if (loading) { text(content, "正在读取本机总结…", 16, true); return; }
        if (error != null) { text(content, error, 16, true); return; }
        List<SummaryHistoryStore.Record> visible = SummaryHistoryQuery.filter(records, filterDialogId, filterTopicId, query);
        text(content, visible.size() + " 条总结 · 本机加密保存 · 长按可删除", 13, false);
        if (publishStatusUnavailable) text(content, "发送状态暂不可用，请在聊天中核对。原总结仍可查看。", 13, false);
        if (visible.isEmpty()) {
            text(content, query.trim().isEmpty() ? "当前范围还没有保存的总结。" : "没有找到匹配的总结。试试其他关键词或来源范围。", 16, true);
            return;
        }
        String previousDay = null;
        SimpleDateFormat dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        for (SummaryHistoryStore.Record record : visible) {
            String day = dayKey.format(new Date(record.generatedAtMillis));
            if (!day.equals(previousDay)) { text(content, dateLabel(record.generatedAtMillis), 15, true); previousDay = day; }
            LinearLayout row = new LinearLayout(content.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(8), dp(14), dp(12));
            row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), color(Theme.key_windowBackgroundWhite), color(Theme.key_listSelector)));
            text(row, SummaryHistoryActivity.chatLabel(record), 16, true);
            text(row, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(record.generatedAtMillis))
                    + " · " + record.sources.size() + " 条文字 · " + record.rangeLabel + (record.partial ? " · 部分覆盖" : ""), 13, false);
            text(row, excerpt(record.summary, 100), 14, false);
            SummaryPublishStore.Attempt attempt = latestAttempts.get(record.id);
            if (attempt != null) text(row, SummaryPublishStatusText.label(attempt), 13, false);
            row.setFocusable(true);
            row.setOnClickListener(view -> {
                if (active() && !loading) parent.presentFragment(new SummaryHistoryDetailActivity(account, record.id));
            });
            row.setOnLongClickListener(view -> { confirmDelete(record); return true; });
            content.addView(row, LayoutHelper.createLinear(-1, -2, 0, 4, 0, 4));
        }
        text(content, "最多保留 100 条、8 MiB；不保存 API Key 或原消息正文。", 13, false);
    }

    private static String dateLabel(long millis) {
        Calendar today = Calendar.getInstance();
        Calendar date = Calendar.getInstance(); date.setTimeInMillis(millis);
        if (sameDate(today, date)) return "今天";
        today.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDate(today, date)) return "昨天";
        return new SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(new Date(millis));
    }

    private static boolean sameDate(Calendar a, Calendar b) {
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private TextView button(LinearLayout parentView, String title, Runnable action) {
        TextView button = new TextView(parentView.getContext());
        button.setText(title);
        button.setTextSize(14);
        button.setTextColor(color(Theme.key_windowBackgroundWhiteBlueText));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setFocusable(true);
        button.setOnClickListener(view -> { if (active()) action.run(); });
        parentView.addView(button, LayoutHelper.createLinear(0, -2, 1f));
        return button;
    }

    private TextView text(LinearLayout parentView, CharSequence value, int size, boolean bold) {
        TextView view = new TextView(parentView.getContext());
        view.setText(value); view.setTextSize(size);
        view.setTextColor(color(bold ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
        view.setGravity(Gravity.START); view.setLineSpacing(dp(2), 1f);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        parentView.addView(view, LayoutHelper.createLinear(-1, -2, 0, 6, 0, 4));
        return view;
    }

    private static String excerpt(String text, int limit) {
        return text.codePointCount(0, text.length()) <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit)) + "…";
    }
    private int color(int key) { return parent.getThemedColor(key); }
    private static int dp(float value) { return AndroidUtilities.dp(value); }
}
