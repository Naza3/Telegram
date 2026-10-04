/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ai.AiSummaryClient;
import org.telegram.messenger.ai.AiSummarySettings;
import org.telegram.messenger.ai.ApiProfilesStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

/** Account-bound profiles; editing and testing never silently select another service. */
public final class ApiProfilesActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final int account;
    private final long ownerId;
    private final Runnable onChanged;
    private final ArrayList<AiSummarySettings.Config> profiles = new ArrayList<>();
    private final ArrayList<View> editorFields = new ArrayList<>();
    private String selectedId = "";
    private LinearLayout content;
    private AiSummarySettings.Config opening;
    private EditTextBoldCursor name, address, model, key, output, budget;
    private RadioGroup serviceTypes;
    private CheckBox stream;
    private TextView status, cancelTest;
    private AiSummaryClient diagnostic;
    private boolean editing, busy, destroyed, invalidated;
    private int operation;

    public ApiProfilesActivity(int account, long ownerId, Runnable onChanged) {
        this.account = account;
        this.ownerId = ownerId;
        this.onChanged = onChanged;
        setCurrentAccount(account);
    }

    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate() || !sameOwner()) return false;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        return true;
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("模型 API 配置");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) back(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        fragmentView = scroll;
        load();
        return scroll;
    }

    @Override public void onPause() {
        stopDiagnostic("测试已停止；未保存设置。");
        super.onPause();
    }

    @Override public void onFragmentDestroy() {
        destroyed = true; operation++;
        stopDiagnostic(null);
        clearEditor(); profiles.clear();
        if (content != null) content.removeAllViews();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        super.onFragmentDestroy();
    }

    @Override public boolean onBackPressed(boolean invoked) {
        if (editing || diagnostic != null || busy) { if (invoked) back(); return false; }
        return super.onBackPressed(invoked);
    }

    private void back() {
        if (diagnostic != null) { stopDiagnostic("测试已停止；未保存设置。"); return; }
        if (busy) return;
        if (editing) { clearEditor(); editing = false; load(); }
        else finishFragment();
    }

    private boolean sameOwner() {
        return !destroyed && !invalidated && ownerId > 0 && UserConfig.selectedAccount == account
                && UserConfig.getInstance(account).getClientUserId() == ownerId;
    }

    private boolean active(int generation) { return generation == operation && sameOwner() && !isFinished; }

    @Override public void didReceivedNotification(int id, int changedAccount, Object... args) {
        if (sameOwner()) return;
        invalidated = true; operation++; stopDiagnostic(null); busy = false;
        clearEditor(); profiles.clear();
        if (content != null) {
            content.removeAllViews();
            text("账号已切换或退出，请从当前账号重新打开 API 配置。", true);
        }
    }

    private void load() {
        if (!sameOwner() || content == null) return;
        final int generation = ++operation;
        busy = true; editing = false; clearEditor(); profiles.clear();
        content.removeAllViews(); text("正在读取模型配置…", true);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                List<AiSummarySettings.Config> values = ApiProfilesStore.list(account, ownerId);
                final String selectedResult = ApiProfilesStore.selectedId(account, ownerId);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    busy = false; selectedId = selectedResult; profiles.addAll(values); showList(null);
                });
            } catch (RuntimeException ignored) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    busy = false; showList("无法读取 API 配置，请重试。已有配置不会被覆盖。");
                });
            }
        });
    }

    private void showList(String error) {
        content.removeAllViews();
        actionBar.setTitle("模型 API 配置");
        text("每项配置独立保存地址、模型与密钥。切换只影响下一次任务，正在运行的总结继续使用启动时的配置。", false);
        if (error != null) { text(error, true); action("重新读取", this::load); return; }
        if (selectedId.isEmpty()) text("尚未选择 API 配置。请明确选择一项后开始总结，不会自动切换到其他服务。", true);
        action("新增 API 配置", () -> showEditor(null));
        for (AiSummarySettings.Config config : profiles) {
            text(config.profileName + (config.profileId.equals(selectedId) ? " · 当前使用" : ""), true);
            text((config.serviceType == AiSummarySettings.ServiceType.MNN_LOCAL ? "MNN 本机" : "OpenAI 兼容")
                    + " · " + (config.model.isEmpty() ? "服务当前模型" : config.model), false);
            text(config.baseUrl, false);
            action("使用「" + config.profileName + "」", () -> select(config));
            LinearLayout row = new LinearLayout(content.getContext());
            rowAction(row, "编辑", () -> showEditor(config));
            rowAction(row, "测试连接", () -> test(config));
            rowAction(row, "删除", () -> confirmDelete(config));
            content.addView(row, LayoutHelper.createLinear(-1, -2));
        }
        status = text(profiles.size() + " / 20 项 · 仅当前账号本机保存", false);
        cancelTest = action("取消连接测试", () -> stopDiagnostic("测试已停止。"));
        cancelTest.setVisibility(View.GONE);
        // Cancellation remains available while all mutating actions are blocked.
        cancelTest.setOnClickListener(view -> stopDiagnostic("测试已停止。"));
    }

    private void showEditor(AiSummarySettings.Config profile) {
        if (!sameOwner() || busy) return;
        clearEditor(); editing = true;
        opening = profile == null ? new AiSummarySettings.Config(AiSummarySettings.DEFAULT_BASE_URL,
                AiSummarySettings.DEFAULT_MODEL, "") : profile;
        content.removeAllViews();
        actionBar.setTitle(profile == null ? "新增 API 配置" : "编辑 API 配置");
        name = edit("配置名称（最多 80 字符）", profile == null ? "" : profile.profileName, InputType.TYPE_CLASS_TEXT);
        serviceTypes = new RadioGroup(content.getContext());
        for (AiSummarySettings.ServiceType type : AiSummarySettings.ServiceType.values()) {
            RadioButton choice = new RadioButton(content.getContext());
            choice.setText(type == AiSummarySettings.ServiceType.MNN_LOCAL ? "MNN Chat 本机服务" : "通用 OpenAI 兼容服务");
            choice.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            choice.setId(View.generateViewId()); choice.setTag(type); serviceTypes.addView(choice); editorFields.add(choice);
            if (type == opening.serviceType) serviceTypes.check(choice.getId());
        }
        content.addView(serviceTypes, LayoutHelper.createLinear(-1, -2));
        text("服务地址", true);
        address = edit("http://127.0.0.1:8080/v1", opening.baseUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        text("模型名称（可留空）", true);
        model = edit("留空使用服务当前模型", opening.model, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text("MNN 需先加载模型并开启 API 服务；填写模型名不会切换 MNN 已加载的模型。", false);
        text("API Key", true);
        key = edit("服务未开启鉴权时可留空", opening.apiKey, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTransformationMethod(PasswordTransformationMethod.getInstance()); key.setSaveEnabled(false);
        text("密钥随本项配置独立加密保存。新建配置不会复制其他配置的密钥。", false);
        text("最大输出 tokens（64–8192；MNN 最高 2048）", true);
        output = edit("512", Integer.toString(opening.maxOutputTokens), InputType.TYPE_CLASS_NUMBER);
        text("上下文字符预算（2048–" + AiSummarySettings.MAX_INPUT_CHARACTER_BUDGET + "）", true);
        budget = edit("6000", Integer.toString(opening.inputCharacterBudget), InputType.TYPE_CLASS_NUMBER);
        text("字符预算不是模型 token 数；还需为核心要求、输入说明与输出预留空间。", false);
        stream = new CheckBox(content.getContext());
        stream.setText("流式显示（服务支持时生效）"); stream.setChecked(opening.stream);
        stream.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText)); editorFields.add(stream);
        content.addView(stream, LayoutHelper.createLinear(-1, -2));
        status = text("连接测试只发送固定短消息，不读取群消息，也不会自动保存。", false);
        action("测试当前填写的配置", () -> { AiSummarySettings.Config config = snapshot(); if (config != null) test(config); });
        cancelTest = action("取消连接测试", () -> stopDiagnostic("测试已停止；未保存设置。"));
        cancelTest.setVisibility(View.GONE); cancelTest.setOnClickListener(view -> stopDiagnostic("测试已停止；未保存设置。"));
        action("保存配置", this::save);
        action("放弃修改并返回列表", this::back);
    }

    private AiSummarySettings.Config snapshot() {
        int tokens, characters;
        try { tokens = Integer.parseInt(output.getText().toString().trim()); characters = Integer.parseInt(budget.getText().toString().trim()); }
        catch (NumberFormatException ignored) { status.setText("输出上限与字符预算需要填写有效整数。"); return null; }
        View selected = serviceTypes.findViewById(serviceTypes.getCheckedRadioButtonId());
        AiSummarySettings.Config config = opening.withValues(address.getText().toString(), model.getText().toString(),
                key.getText().toString(), tokens, stream.isChecked(), characters, (AiSummarySettings.ServiceType) selected.getTag());
        String error = AiSummarySettings.validate(config);
        if (error != null) { status.setText(error); return null; }
        return config;
    }

    private void save() {
        AiSummarySettings.Config values = snapshot();
        if (values == null) return;
        String profileName = name.getText().toString().trim();
        if (profileName.isEmpty() || profileName.codePointCount(0, profileName.length()) > 80) {
            name.setError("请填写 1–80 个字符的名称"); name.requestFocus(); return;
        }
        final AiSummarySettings.Config expected = opening;
        mutate(() -> {
            if (expected.profileId.isEmpty()) ApiProfilesStore.create(account, ownerId, profileName, values);
            else ApiProfilesStore.update(account, ownerId, expected.profileId, expected.profileRevision, profileName, values);
        }, false, "保存失败。配置可能已被修改或删除，请保留当前填写内容并重新打开该配置。");
    }

    private void select(AiSummarySettings.Config config) {
        mutate(() -> ApiProfilesStore.select(account, ownerId, config.profileId), true,
                "选择失败，该配置可能已删除，请刷新列表。");
    }

    private void confirmDelete(AiSummarySettings.Config config) {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider()).setTitle("删除 API 配置")
                .setMessage("删除「" + config.profileName + "」及其密钥？"
                        + (config.profileId.equals(selectedId) ? "删除后需明确选择另一项，不会自动回退。" : ""))
                .setPositiveButton("删除", (dialog, which) -> mutate(() -> ApiProfilesStore.delete(account, ownerId,
                        config.profileId, config.profileRevision), false, "删除失败，配置可能已变化，请刷新后重试。"))
                .setNegativeButton("取消", null).create());
    }

    private interface Mutation { void run(); }
    private void mutate(Mutation mutation, boolean finishAfter, String error) {
        if (!sameOwner() || busy) return;
        final int generation = ++operation; busy = true; setFieldsEnabled(false); status.setText("正在保存配置…");
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (!sameOwner()) return;
                mutation.run();
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    busy = false; setFieldsEnabled(true);
                    if (onChanged != null) onChanged.run();
                    if (finishAfter) finishFragment(); else { clearEditor(); editing = false; load(); }
                });
            } catch (RuntimeException failure) {
                // Store parameter validation uses fixed safe messages; arbitrary storage errors stay generic.
                final String notice = failure instanceof IllegalArgumentException && failure.getMessage() != null
                        ? failure.getMessage() : error;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(generation)) return;
                    busy = false; setFieldsEnabled(true); status.setText(notice);
                });
            }
        });
    }

    private void test(AiSummarySettings.Config config) {
        if (!sameOwner() || busy) return;
        final int generation = ++operation;
        busy = true; setFieldsEnabled(false); cancelTest.setVisibility(View.VISIBLE);
        status.setText("正在测试连接…测试不会保存配置。");
        diagnostic = new AiSummaryClient();
        diagnostic.testConnection(config, new AiSummaryClient.DiagnosticCallback() {
            @Override public void onSuccess(AiSummaryClient.DiagnosticResult result) {
                if (!active(generation)) return;
                diagnostic = null; busy = false; setFieldsEnabled(true); cancelTest.setVisibility(View.GONE);
                status.setText("连接成功 · " + result.elapsedMs + " 毫秒\n" + (result.reportedModel == null
                        ? "服务未报告实际模型名称。" : "服务报告模型：" + result.reportedModel)
                        + "\n仅验证普通请求；未保存或切换配置。");
            }
            @Override public void onError(String message) { onError(message, null); }
            @Override public void onError(String message, AiSummaryClient.DiagnosticErrorInfo info) {
                if (!active(generation)) return;
                diagnostic = null; busy = false; setFieldsEnabled(true); cancelTest.setVisibility(View.GONE);
                String detail = info == null ? "" : "\nHTTP " + info.status + " · " + info.responseFormat
                        + (info.serverMessage.isEmpty() ? "" : "\n" + info.serverMessage);
                status.setText(message + detail + "\n可修改设置后重新测试；未保存或切换配置。");
                status.setTextIsSelectable(true);
                status.setLinksClickable(false);
            }
        });
    }

    private void stopDiagnostic(String notice) {
        if (diagnostic == null) return;
        operation++; diagnostic.cancel(); diagnostic = null; busy = false; setFieldsEnabled(true);
        if (cancelTest != null) cancelTest.setVisibility(View.GONE);
        if (notice != null && status != null && !destroyed) status.setText(notice);
    }

    private void clearEditor() {
        if (key != null) key.setText("");
        opening = null; name = address = model = key = output = budget = null;
        serviceTypes = null; stream = null; editorFields.clear();
    }
    private void setFieldsEnabled(boolean enabled) { for (View view : editorFields) view.setEnabled(enabled); }
    private TextView text(String value, boolean bold) {
        TextView view = new TextView(content.getContext()); view.setText(value); view.setTextSize(bold ? 16 : 14);
        view.setTextColor(getThemedColor(bold ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
        if (bold) view.setTypeface(AndroidUtilities.bold());
        content.addView(view, LayoutHelper.createLinear(-1, -2, 0, 10, 0, 4)); return view;
    }
    private TextView action(String label, Runnable run) {
        TextView view = text(label, true); view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        view.setMinHeight(dp(46)); view.setGravity(Gravity.CENTER_VERTICAL); view.setFocusable(true);
        view.setOnClickListener(ignored -> { if (sameOwner() && !busy) run.run(); }); return view;
    }
    private void rowAction(LinearLayout row, String label, Runnable run) {
        TextView view = new TextView(content.getContext()); view.setText(label); view.setTextSize(14);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText)); view.setMinHeight(dp(44)); view.setGravity(Gravity.CENTER);
        view.setOnClickListener(ignored -> { if (sameOwner() && !busy) run.run(); });
        row.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private EditTextBoldCursor edit(String hint, String value, int inputType) {
        EditTextBoldCursor view = new EditTextBoldCursor(content.getContext()); view.setTextSize(16); view.setInputType(inputType);
        view.setSingleLine(true); view.setHint(hint); view.setText(value); view.setSaveEnabled(false);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        content.addView(view, LayoutHelper.createLinear(-1, dp(52), 0, 4, 0, 6)); editorFields.add(view); return view;
    }
    private static int dp(float value) { return AndroidUtilities.dp(value); }
}
