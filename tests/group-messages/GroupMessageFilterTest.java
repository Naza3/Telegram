package org.telegram.messenger.groupmessages;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class GroupMessageFilterTest {
    private static int assertions;

    public static void main(String[] args) {
        GroupMessageFilter off = new GroupMessageFilter(Collections.emptySet(), Collections.emptyList());
        check(off.isEmpty() && off.match(true, false, 42, "广告") == 0, "Empty rules must preserve messages");

        GroupMessageFilter uid = new GroupMessageFilter(new HashSet<>(Arrays.asList(42L, Long.MAX_VALUE)), Collections.emptyList());
        check(uid.match(true, false, 42, null) == GroupMessageFilter.UID, "UID must also match media-only messages");
        check(uid.match(true, false, Long.MAX_VALUE, "body") == GroupMessageFilter.UID, "64-bit UID lost precision");
        check(uid.match(true, false, 43, "42") == 0, "Mentioning a UID is not sender identity");
        check(uid.match(true, false, -42, "same name") == 0, "Channel identity confused with user UID");
        check(uid.match(true, false, 0, "unknown sender") == 0, "Unknown sender was inferred");
        check(uid.match(false, false, 42, "joined") == 0, "Service/synthetic rows must stay visible");
        check(uid.match(true, true, 42, "own") == 0, "Outgoing messages must stay visible");

        GroupMessageFilter words = new GroupMessageFilter(Collections.emptySet(),
                Arrays.asList(" 广告 ", "FIX ISSUE", "a.b", "[gift]", "😀", "", "  "));
        check(words.match(true, false, 99, "群发广告，请联系") == GroupMessageFilter.KEYWORD, "Chinese keyword failed");
        check(words.match(true, false, 99, "prefix A.B suffix") == GroupMessageFilter.KEYWORD, "Case insensitive literal failed");
        check(words.match(true, false, 99, "prefix axb suffix") == 0, "Keyword was interpreted as regex");
        check(words.match(true, false, 99, "gift") == 0, "Brackets were interpreted as regex");
        check(words.match(true, false, 99, "[gift]") == GroupMessageFilter.KEYWORD, "Literal brackets failed");
        check(words.match(true, false, 99, "欢迎😀") == GroupMessageFilter.KEYWORD, "Emoji keyword failed");
        check(words.match(true, false, 99, null) == 0, "Missing body should not match keywords");
        check(words.match(true, false, 99, "") == 0, "Empty body should not match an empty rule");
        check(words.match(false, false, 99, "广告") == 0, "Keyword hid a service row");
        check(words.match(true, true, 99, "广告") == 0, "Keyword hid outgoing message");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            GroupMessageFilter turkish = new GroupMessageFilter(Collections.emptySet(), Arrays.asList("FIX ISSUE"));
            check(turkish.match(true, false, 99, "fix issue") == GroupMessageFilter.KEYWORD, "Device locale affected matching");
        } finally { Locale.setDefault(previous); }

        Set<Long> ids = new HashSet<>(Arrays.asList(42L));
        ArrayList<String> mutableWords = new ArrayList<>(Arrays.asList("ad"));
        GroupMessageFilter snapshot = new GroupMessageFilter(ids, mutableWords);
        ids.clear(); mutableWords.clear();
        check(snapshot.match(true, false, 42, "hello") == GroupMessageFilter.UID, "Mutable UID set changed active snapshot");
        check(snapshot.match(true, false, 99, "AD") == GroupMessageFilter.KEYWORD, "Mutable keywords changed snapshot");
        check(snapshot.match(true, false, 42, "AD") == GroupMessageFilter.UID, "UID OR keyword precedence changed");
        check(snapshot.match(true, false, 99, "ordinary") == 0, "Non-matching message was hidden");
        GroupMessageFilter blank = new GroupMessageFilter(new HashSet<>(Arrays.asList(0L, -42L)), Arrays.asList("", " ", null));
        check(blank.isEmpty(), "Malformed empty settings must not match every message");
        System.out.println("GroupMessageFilterTest: " + assertions + " assertions passed");
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }
}
