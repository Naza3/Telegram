/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui.Components;

import org.telegram.messenger.ai.SummaryPublishStore;

/** A queued message and a server acknowledgement are deliberately different labels. */
public final class SummaryPublishStatusText {
    private SummaryPublishStatusText() { }
    public static String label(SummaryPublishStore.Attempt attempt) {
        if (attempt == null) return "尚无发送记录";
        String count = attempt.confirmedCount + "/" + attempt.parts.size();
        switch (attempt.status) {
            case SENT: return "已发送 · " + attempt.parts.size() + " 条";
            case SENDING: return "发送中 · 已确认 " + count;
            case FAILED: return "发送失败 · 请在聊天中重试";
            case PARTIAL: return "部分完成 · 已确认 " + count
                    + (attempt.failedCount > 0 ? " · 失败 " + attempt.failedCount + " 条" : "");
            case INTERRUPTED: return "发送已中断 · 请在聊天中核对";
            case SCHEDULED: return "已排期 · 尚未发出";
            default: return "准备发送 · 尚未确认";
        }
    }
}
