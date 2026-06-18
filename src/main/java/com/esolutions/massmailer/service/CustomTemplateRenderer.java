package com.esolutions.massmailer.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders user-defined email templates (subject + body) by substituting
 * {{placeholder}} tokens against a flat variable map and wrapping the body
 * in a minimal HTML shell.
 *
 * Intentionally simple — no expressions, no conditionals. If a placeholder
 * has no matching variable, it is rendered as an empty string.
 */
@Service
public class CustomTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    /** Substitutes {{var}} tokens in an arbitrary string (subject or single-line value). */
    public String substitute(String input, Map<String, Object> vars) {
        if (input == null || input.isEmpty()) return input;
        Matcher m = PLACEHOLDER.matcher(input);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object value = vars != null ? vars.get(key) : null;
            String replacement = value != null ? value.toString() : "";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Renders a body string (plain text with {{placeholders}} and newlines)
     * into a self-contained HTML email body. Newlines become {@code <br>}.
     * If the body already contains HTML tags it is preserved as-is.
     */
    public String renderHtmlBody(String body, Map<String, Object> vars) {
        String substituted = substitute(body, vars);
        String content = looksLikeHtml(substituted)
                ? substituted
                : escapeHtml(substituted).replace("\n", "<br>\n");
        return """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                %s
                </div>
                </body></html>
                """.formatted(content);
    }

    private boolean looksLikeHtml(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains("<p>") || lower.contains("<br") || lower.contains("<div")
                || lower.contains("<html") || lower.contains("<body");
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
