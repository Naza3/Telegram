/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

/** Persistence-safe source identity and independently known SHA-256 vectors. */
public final class SummarySourceReferenceTest {
    private static int assertions;
    private static final String ABC_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    public static void main(String[] args) throws Exception {
        SummarySourceReference ascii = SummarySourceReference.from(new SummaryMessage(-100, 3, 10000, "姓名", "abc"));
        check(ABC_HASH.equals(ascii.textHash) && ascii.matchesText("abc"), "SHA-256 vector mismatch");
        check(!ascii.matchesText("ABC") && !ascii.matchesText("abc\n") && !ascii.matchesText(null), "content changes were normalized away");
        String original = "原消息正文😀\n";
        SummarySourceReference unicode = SummarySourceReference.from(new SummaryMessage(-100, 4, 10000,
                "不应保存的姓名", original, -200, 9, -100, true, false, 20, true, true));
        check("4d5707a9864c14575940cba52e5de595532eb619b51992c77e532679677bdf87".equals(unicode.textHash), "UTF-8 vector mismatch");
        check(unicode.matchesText(original) && !unicode.matchesText(original.trim()), "Unicode text identity changed");
        check(unicode.dialogId == -100 && unicode.id == 4 && unicode.date == 10000
                && unicode.editDate == 20 && unicode.senderId == -200, "source identity lost");
        SummarySourceReference restored = new SummarySourceReference(-100, 3, 10000, 0, 0, ABC_HASH.toUpperCase(Locale.US));
        check(ABC_HASH.equals(restored.textHash) && restored.matchesText("abc"), "persisted hash canonicalization failed");
        check(Modifier.isFinal(SummarySourceReference.class.getModifiers()), "reference class can be mutated by a subclass");
        for (Field field : SummarySourceReference.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            check(Modifier.isFinal(field.getModifiers()), "mutable reference field");
            check(field.getType() == long.class || field.getType() == int.class
                    || field.getType() == String.class && "textHash".equals(field.getName()), "reference retains data beyond identity/hash");
            if (field.getType() == String.class) check(!original.equals(field.get(unicode)), "reference retains original text");
        }
        for (long dialog : new long[]{0, 100, Long.MIN_VALUE, 0x400000000000002aL}) {
            reject(() -> new SummarySourceReference(dialog, 3, 10000, 0, 0, ABC_HASH));
        }
        for (int id : new int[]{0, -1}) reject(() -> new SummarySourceReference(-100, id, 10000, 0, 0, ABC_HASH));
        for (int date : new int[]{0, -1}) reject(() -> new SummarySourceReference(-100, 3, date, 0, 0, ABC_HASH));
        reject(() -> new SummarySourceReference(-100, 3, 10000, -1, 0, ABC_HASH));
        reject(() -> new SummarySourceReference(-100, 3, 10000, 0, Long.MIN_VALUE, ABC_HASH));
        for (String hash : new String[]{null, "", ABC_HASH.substring(1), ABC_HASH + "0", "z".repeat(64), " " + ABC_HASH.substring(1)}) {
            reject(() -> new SummarySourceReference(-100, 3, 10000, 0, 0, hash));
        }
        reject(() -> SummarySourceReference.from(null));
        reject(() -> SummarySourceReference.from(new SummaryMessage(-100, 0, 10000, "", "must not appear in errors")));
        System.out.println("SummarySourceReferenceTest: " + assertions + " assertions passed");
    }

    private static void reject(Runnable operation) {
        try { operation.run(); throw new AssertionError("invalid persisted reference accepted"); }
        catch (IllegalArgumentException expected) {
            check(!expected.getMessage().contains("must not appear"), "reference validation echoed source text");
        }
    }

    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
