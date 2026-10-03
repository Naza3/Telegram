/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Selects a small server explanation for a fixed-text probe, never for chat requests. */
final class AiSummaryDiagnosticSanitizer {
    private static final int MAX_MESSAGE_CODE_POINTS = 400;
    private static final String REDACTED = "[凭据已隐藏]";
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(?:bearer|basic)\\s+[^\\s,;\\\"']+");
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)\\b(?:authorization|proxy-authorization|x[-_]?api[-_]?key|api[-_ ]?key|access[-_ ]?token|refresh[-_ ]?token|password|passwd|secret|token)\\b[\\\"']?\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern KEY_SHAPED = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern URL_CREDENTIAL = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s<>@]*@");
    private static final Pattern COOKIE = Pattern.compile("(?i)\\b(?:set-cookie|cookie)\\s*[:=].*");
    private static final Pattern HTML_PAGE = Pattern.compile("(?i)<\\s*(?:!doctype\\s+html|/?(?:html|head|body|script|style|title)\\b)");
    private static final Pattern PAYLOAD = Pattern.compile("(?i)(?:request|response|json)\\s*(?:body|payload|headers|input)\\s*[:=]|[\\\"']?(?:messages|headers|prompt|request_body)[\\\"']?\\s*[:=]\\s*[\\[{]");

    private AiSummaryDiagnosticSanitizer() { }

    static AiSummaryClient.DiagnosticErrorInfo create(int status, String body, String contentType, String apiKey) {
        String raw = body == null ? "" : body.trim();
        if (raw.isEmpty()) return info(status, "", "empty");
        if (isHtml(raw, contentType)) return info(status, "服务返回 HTML 页面，未显示页面内容。", "html");
        String format;
        String selected;
        if (raw.startsWith("{") || raw.startsWith("[") || raw.startsWith("\"")) {
            format = "json";
            selected = "";
            try {
                JSONObject object = new JSONObject(raw);
                Object error = object.opt("error");
                if (error instanceof String) selected = (String) error;
                else if (error instanceof JSONObject) selected = string(((JSONObject) error).opt("message"));
                if (selected.isEmpty()) selected = string(object.opt("message"));
                if (selected.isEmpty()) selected = string(object.opt("detail"));
                if (selected.isEmpty() && status == 422 && object.opt("detail") instanceof JSONArray) {
                    JSONArray details = object.getJSONArray("detail");
                    StringBuilder messages = new StringBuilder();
                    for (int i = 0; i < Math.min(4, details.length()); i++) {
                        JSONObject detail = details.optJSONObject(i);
                        String message = detail == null ? "" : string(detail.opt("msg"));
                        if (!message.isEmpty()) {
                            if (messages.length() > 0) messages.append("；");
                            messages.append(message);
                        }
                    }
                    selected = messages.toString();
                }
            } catch (JSONException ignored) {
                // Unknown/truncated structured data is never displayed as a raw fallback.
            }
        } else {
            format = "text";
            selected = raw.length() <= 2048 ? raw : "";
        }
        String safe = sanitize(selected, apiKey == null ? "" : apiKey);
        if (isHtml(safe, null)) return info(status, "服务返回 HTML 页面，未显示页面内容。", "html");
        return info(status, safe, format);
    }

    private static String string(Object value) { return value instanceof String ? (String) value : ""; }

    private static AiSummaryClient.DiagnosticErrorInfo info(int status, String message, String format) {
        return new AiSummaryClient.DiagnosticErrorInfo(status, message, format);
    }

    private static boolean isHtml(String value, String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.US).contains("html")
                || value.trim().startsWith("<") || HTML_PAGE.matcher(value).find();
    }

    private static String sanitize(String value, String apiKey) {
        // Decode escaped echoes before redaction. A nested encoding that remains after this
        // bounded work is suppressed instead of exposing a possibly encoded credential.
        value = URL_CREDENTIAL.matcher(redactKnown(value, apiKey)).replaceAll("$1" + REDACTED + "@");
        for (int i = 0; i < 8; i++) {
            String decoded = redactKnown(decodeEscapes(value), apiKey);
            if (decoded.equals(value)) break;
            value = decoded;
        }
        if (!decodeEscapes(value).equals(value)) return "";
        value = redactKnown(normalizeCharacters(value), apiKey);
        value = URL_CREDENTIAL.matcher(value).replaceAll("$1" + REDACTED + "@");
        value = COOKIE.matcher(value).replaceAll(REDACTED);
        value = BEARER.matcher(value).replaceAll(REDACTED);
        value = CREDENTIAL.matcher(value).replaceAll(REDACTED);
        value = KEY_SHAPED.matcher(value).replaceAll(REDACTED);
        value = redactKnown(value, apiKey);
        // Do not echo embedded request/response structures, even inside a scalar error field.
        java.util.regex.Matcher payload = PAYLOAD.matcher(value);
        if (payload.find()) value = value.substring(0, payload.start());
        int objectStart = value.indexOf('{');
        if (objectStart >= 0) value = value.substring(0, objectStart);
        value = value.replaceAll("\\s+", " ").trim();
        if (value.startsWith("[") && !value.startsWith(REDACTED)) return "";
        int points = value.codePointCount(0, value.length());
        if (points > MAX_MESSAGE_CODE_POINTS) {
            value = value.substring(0, value.offsetByCodePoints(0, MAX_MESSAGE_CODE_POINTS - 1)) + "…";
        }
        return value;
    }

    private static String normalizeCharacters(String value) {
        StringBuilder normalized = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.getType(cp) == Character.FORMAT) continue;
            if (Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                normalized.append(' ');
            } else normalized.appendCodePoint(cp);
        }
        return normalized.toString();
    }

    private static String redactKnown(String value, String apiKey) {
        if (apiKey.isEmpty()) return value;
        String normalizedKey = normalizeCharacters(apiKey);
        String replacement = REDACTED.contains(apiKey) || !normalizedKey.isEmpty() && REDACTED.contains(normalizedKey) ? "***" : REDACTED;
        value = value.replace(apiKey, replacement);
        if (!normalizedKey.isEmpty()) value = value.replace(normalizedKey, replacement);
        // Form encoding represents spaces as '+'. Redact its exact encoding without globally
        // decoding literal plus signs in server explanations or in the configured key.
        try {
            String encoded = URLEncoder.encode(apiKey, "UTF-8");
            value = Pattern.compile(Pattern.quote(encoded), Pattern.CASE_INSENSITIVE).matcher(value).replaceAll(replacement);
        } catch (UnsupportedEncodingException impossible) { /* UTF-8 is always available. */ }
        return value;
    }

    private static String decodeEscapes(String value) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && hex(value.charAt(i + 1)) >= 0 && hex(value.charAt(i + 2)) >= 0) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                while (i + 2 < value.length() && value.charAt(i) == '%' && hex(value.charAt(i + 1)) >= 0 && hex(value.charAt(i + 2)) >= 0) {
                    bytes.write(hex(value.charAt(i + 1)) * 16 + hex(value.charAt(i + 2)));
                    i += 3;
                }
                decoded.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                continue;
            }
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == 'u' && i + 5 < value.length()) {
                    int code = 0;
                    boolean valid = true;
                    for (int j = i + 2; j <= i + 5; j++) {
                        int digit = hex(value.charAt(j));
                        if (digit < 0) { valid = false; break; }
                        code = code * 16 + digit;
                    }
                    if (valid) { decoded.append((char) code); i += 6; continue; }
                }
                if (next == '\\' || next == '/' || next == '\"') {
                    decoded.append(next); i += 2; continue;
                }
                if (next == 'n' || next == 'r' || next == 't' || next == 'b' || next == 'f') {
                    decoded.append(' '); i += 2; continue;
                }
            }
            decoded.append(c); i++;
        }
        return decoded.toString();
    }

    private static int hex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }
}
