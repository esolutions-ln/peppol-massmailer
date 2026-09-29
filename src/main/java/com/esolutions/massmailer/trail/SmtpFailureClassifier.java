package com.esolutions.massmailer.trail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts diagnostic fields from SMTP / Brevo failure text.
 *
 * Works on the concatenated messages of the whole exception cause chain so it
 * sees both Spring's wrapper text and the provider's raw reply, e.g.
 * {@code "525 5.7.1 Unauthorized IP address"} or
 * {@code "Brevo /smtp/email failed: 401 {... unrecognised IP address 1.2.3.4 ...}"}.
 */
public final class SmtpFailureClassifier {

    private SmtpFailureClassifier() {}

    /** Leading SMTP reply: "525 5.7.1 ..." or "535-5.7.8 ...". */
    private static final Pattern SMTP_REPLY =
            Pattern.compile("(?m)(?:^|\\s)([2-5]\\d{2})[ -]+([2-5]\\.\\d{1,3}\\.\\d{1,3})?");

    /** Brevo client error format: "Brevo /smtp/email failed: 401 {...}". */
    private static final Pattern BREVO_STATUS = Pattern.compile("Brevo \\S+ failed: (\\d{3})\\b");

    private static final Pattern ENHANCED_ONLY = Pattern.compile("\\b([2-5]\\.\\d{1,3}\\.\\d{1,3})\\b");

    private static final Pattern IPV4 =
            Pattern.compile("(?<![\\d.])((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})(?!\\.?\\d)");

    private static final Pattern IPV6_BRACKETED = Pattern.compile("\\[((?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4})]");

    /** Provider phrasings for "your sending IP is not allowed". */
    private static final Pattern IP_DENIED = Pattern.compile(
            "unauthori[sz]ed ip"
                    + "|unrecogni[sz]ed ip"
                    + "|ip address (?:is )?not (?:allowed|authori[sz]ed|permitted|whitelisted)"
                    + "|ip (?:is )?not (?:allowed|authori[sz]ed|permitted|whitelisted)"
                    + "|not (?:allowed|authori[sz]ed|permitted) (?:from|for) (?:this|your) ip"
                    + "|client host \\S* ?(?:\\[[^]]*] )?(?:is )?(?:blocked|rejected)"
                    + "|relay access denied"
                    + "|blocked using .*(?:spamhaus|barracuda|blocklist|blacklist)",
            Pattern.CASE_INSENSITIVE);

    /** Concatenated messages of every throwable in the cause chain. */
    public static String chainText(Throwable e) {
        List<String> parts = new ArrayList<>();
        Throwable t = e;
        int guard = 0;
        while (t != null && guard++ < 16) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                parts.add(t.getMessage().strip());
            }
            if (t.getCause() == t) break;
            t = t.getCause();
        }
        return String.join(" | ", parts);
    }

    public static boolean isIpDenied(String text) {
        return text != null && IP_DENIED.matcher(text).find();
    }

    /** SMTP reply code, or Brevo HTTP status; null if none found. */
    public static Integer responseCode(String text) {
        if (text == null) return null;
        Matcher brevo = BREVO_STATUS.matcher(text);
        if (brevo.find()) return Integer.valueOf(brevo.group(1));
        Matcher m = SMTP_REPLY.matcher(text);
        while (m.find()) {
            // Require an enhanced status or a 4xx/5xx code to avoid matching stray numbers
            if (m.group(2) != null || m.group(1).charAt(0) >= '4') {
                return Integer.valueOf(m.group(1));
            }
        }
        return null;
    }

    public static String enhancedStatus(String text) {
        if (text == null) return null;
        Matcher m = SMTP_REPLY.matcher(text);
        while (m.find()) {
            if (m.group(2) != null) return m.group(2);
        }
        Matcher e = ENHANCED_ONLY.matcher(text);
        return e.find() ? e.group(1) : null;
    }

    /** First IP address quoted in the provider's text, if any. */
    public static String reportedIp(String text) {
        if (text == null) return null;
        Matcher v4 = IPV4.matcher(text);
        if (v4.find()) return v4.group(1);
        Matcher v6 = IPV6_BRACKETED.matcher(text);
        return v6.find() ? v6.group(1) : null;
    }

    /** Validates a bare IPv4/IPv6 literal (used for the egress lookup response). */
    public static boolean looksLikeIp(String s) {
        if (s == null || s.isBlank() || s.length() > 45) return false;
        if (IPV4.matcher(s).matches()) return true;
        return s.contains(":") && s.matches("[0-9a-fA-F:.]+");
    }
}
