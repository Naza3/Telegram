/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.ui.Components;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, text-only emphasis. Keeps every character and offset, including legacy source references. */
public final class SummaryTextFormatter {
    private static final Pattern HEADING = Pattern.compile("(?m)^(?:#{1,6}[ \\t]+[^\\r\\n]+|\\*\\*[^\\r\\n]+\\*\\*[ \\t]*|【[^\\r\\n]{1,80}】[：:]?[ \\t]*)$");
    private SummaryTextFormatter() { }
    public static SpannableStringBuilder format(String text) {
        SpannableStringBuilder styled = new SpannableStringBuilder(text == null ? "" : text);
        Matcher matcher = HEADING.matcher(styled);
        while (matcher.find()) styled.setSpan(new StyleSpan(Typeface.BOLD), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }
}
