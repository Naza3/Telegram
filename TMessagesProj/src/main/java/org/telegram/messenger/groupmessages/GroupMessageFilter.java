/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.groupmessages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable display-only matcher. It never changes, deletes, or reads stored messages. */
public final class GroupMessageFilter {
    public static final int NONE = 0;
    public static final int UID = 1;
    public static final int KEYWORD = 2;

    private final Set<Long> uids;
    private final List<String> keywords;

    public GroupMessageFilter(Set<Long> uids, List<String> keywords) {
        HashSet<Long> ids = new HashSet<>();
        if (uids != null) {
            for (Long id : uids) if (id != null && id > 0) ids.add(id);
        }
        this.uids = Collections.unmodifiableSet(ids);
        ArrayList<String> words = new ArrayList<>();
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword == null) continue;
                String normalized = keyword.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty() && !words.contains(normalized)) words.add(normalized);
            }
        }
        this.keywords = Collections.unmodifiableList(words);
    }

    public boolean isEmpty() {
        return uids.isEmpty() && keywords.isEmpty();
    }

    /** UID OR literal keyword. Outgoing and service/synthetic rows always remain visible. */
    public int match(boolean ordinary, boolean outgoing, long actualSenderUid, CharSequence body) {
        if (!ordinary || outgoing || isEmpty()) return NONE;
        if (actualSenderUid > 0 && uids.contains(actualSenderUid)) return UID;
        if (body == null || body.length() == 0 || keywords.isEmpty()) return NONE;
        String text = body.toString().toLowerCase(Locale.ROOT);
        for (String keyword : keywords) if (text.contains(keyword)) return KEYWORD;
        return NONE;
    }
}
