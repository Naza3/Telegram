/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Search is applied to already loaded, owner-scoped history; never fetches source messages. */
public final class SummaryHistoryQuery {
    private SummaryHistoryQuery() { }

    public static List<SummaryHistoryStore.Record> filter(List<SummaryHistoryStore.Record> records,
            long dialogId, long topicId, String query) {
        String needle = normalized(query == null ? "" : query.trim());
        ArrayList<SummaryHistoryStore.Record> result = new ArrayList<>();
        for (SummaryHistoryStore.Record record : records) {
            if (dialogId != 0 && record.dialogId != dialogId || topicId >= 0 && record.topicId != topicId) continue;
            if (needle.isEmpty() || normalized(record.chatTitle).contains(needle)
                    || normalized(record.summary).contains(needle)
                    || record.topicId > 0 && ("话题 " + record.topicId).contains(needle)) result.add(record);
        }
        return Collections.unmodifiableList(result);
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
