package com.esolutions.massmailer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

@Component
public class PdfInvoiceParser {

    private static final Logger log = LoggerFactory.getLogger(PdfInvoiceParser.class);

    public record ExtractedBuyerInfo(
            String companyName,
            String tinNumber,
            String vatNumber,
            String bpn,
            String addressLine1,
            String email,
            String accountNo
    ) {}

    public ExtractedBuyerInfo extractBuyerInfo(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length < 10) return null;

        String text = extractPdfText(pdfBytes);
        if (text == null || text.isBlank()) return null;

        return parseBuyerInfo(text);
    }

    private String extractPdfText(byte[] pdfBytes) {
        String content = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        StringBuilder result = new StringBuilder();

        int searchFrom = 0;
        int streamCount = 0;
        while (true) {
            int streamStart = content.indexOf("stream", searchFrom);
            if (streamStart < 0) break;

            int dataStart = streamStart + 6;
            while (dataStart < content.length() && (content.charAt(dataStart) == '\r' || content.charAt(dataStart) == '\n'))
                dataStart++;

            int endstreamIdx = content.indexOf("endstream", dataStart);
            if (endstreamIdx < 0) break;

            streamCount++;
            String streamData = content.substring(dataStart, endstreamIdx).trim();
            byte[] streamBytes = streamData.getBytes(StandardCharsets.ISO_8859_1);

            boolean isCompressed = false;
            int dictEnd = streamStart;
            int dictStart = content.lastIndexOf("<<", streamStart);
            if (dictStart >= 0) {
                String dict = content.substring(dictStart, streamStart);
                if (dict.contains("FlateDecode")) {
                    isCompressed = true;
                }
            }
            log.debug("Stream {}: start={}, len={}, compressed={}",
                    streamCount, streamStart, streamData.length(), isCompressed);

            byte[] decompressed = null;
            if (isCompressed) {
                decompressed = tryInflate(streamBytes);
            } else {
                decompressed = streamBytes;
            }

            if (decompressed != null && decompressed.length > 0) {
                String streamText = new String(decompressed, StandardCharsets.ISO_8859_1);
                extractTextOperators(streamText, result);
            }

            searchFrom = endstreamIdx + 9;
        }

        return result.toString();
    }

    private byte[] tryInflate(byte[] data) {
        if (data == null || data.length == 0) return null;
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) break;
                baos.write(buf, 0, n);
            }
            inflater.end();
            return baos.toByteArray();
        } catch (Exception e) {
            inflater.end();
            return null;
        }
    }

    private void extractTextOperators(String streamContent, StringBuilder out) {
        if (streamContent == null || streamContent.isEmpty()) return;

        int i = 0;
        while (i < streamContent.length()) {
            if (streamContent.charAt(i) == '(') {
                int start = i;
                i = findEndOfPdfString(streamContent, i);
                if (i < 0) break;

                String rawString = streamContent.substring(start + 1, i);
                String unescaped = unescapePdfString(rawString);

                i++;
                while (i < streamContent.length() && streamContent.charAt(i) == ' ') i++;

                if (matchOperator(streamContent, i, "Tj")) {
                    if (isReadableText(unescaped) && unescaped.length() >= 3) {
                        out.append(unescaped).append('\n');
                    }
                    i += 2;
                } else if (matchOperator(streamContent, i, "'")) {
                    if (isReadableText(unescaped) && unescaped.length() >= 3) {
                        out.append(unescaped).append('\n');
                    }
                    i += 1;
                } else {
                    i = start + 1;
                }

            } else if (streamContent.charAt(i) == '[') {
                int endBracket = findEndOfArray(streamContent, i);
                if (endBracket < 0) { i++; continue; }

                String arrayContent = streamContent.substring(i + 1, endBracket);
                i = endBracket + 1;
                while (i < streamContent.length() && streamContent.charAt(i) == ' ') i++;

                if (matchOperator(streamContent, i, "TJ")) {
                    StringBuilder line = new StringBuilder();
                    int pos = 0;
                    while (pos < arrayContent.length()) {
                        while (pos < arrayContent.length() && arrayContent.charAt(pos) == ' ') pos++;
                        if (pos >= arrayContent.length()) break;

                        if (arrayContent.charAt(pos) == '(') {
                            int end = findEndOfPdfString(arrayContent, pos);
                            if (end < 0) break;
                            String rawString = arrayContent.substring(pos + 1, end);
                            String unescaped = unescapePdfString(rawString);
                            if (isReadableText(unescaped)) {
                                line.append(unescaped);
                            }
                            pos = end + 1;
                        } else {
                            while (pos < arrayContent.length() && arrayContent.charAt(pos) != ' ' && arrayContent.charAt(pos) != '(')
                                pos++;
                        }
                    }
                    if (!line.isEmpty()) {
                        out.append(line).append('\n');
                    }
                    i += 2;
                }

            } else {
                i++;
            }
        }
    }

    private int findEndOfPdfString(String s, int start) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == ')') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    private int findEndOfArray(String s, int start) {
        int depth = 1;
        int i = start + 1;
        while (i < s.length() && depth > 0) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == '(') {
                i = findEndOfPdfString(s, i);
                if (i < 0) return -1;
            }
            i++;
        }
        return depth == 0 ? i - 1 : -1;
    }

    private boolean matchOperator(String s, int pos, String op) {
        if (pos + op.length() > s.length()) return false;
        for (int j = 0; j < op.length(); j++) {
            if (s.charAt(pos + j) != op.charAt(j)) return false;
        }
        int after = pos + op.length();
        return after >= s.length() || s.charAt(after) == ' ' || s.charAt(after) == '\n' || s.charAt(after) == '\r';
    }

    private String unescapePdfString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '(' -> sb.append('(');
                    case ')' -> sb.append(')');
                    case '\\' -> sb.append('\\');
                    default -> {
                        if (next >= '0' && next <= '9') {
                            StringBuilder octal = new StringBuilder();
                            octal.append(next);
                            int k = i + 2;
                            while (k < s.length() && octal.length() < 3 && s.charAt(k) >= '0' && s.charAt(k) <= '7') {
                                octal.append(s.charAt(k));
                                k++;
                            }
                            try {
                                sb.append((char) Integer.parseInt(octal.toString(), 8));
                                i = k - 1;
                            } catch (Exception e) {
                                sb.append(next);
                            }
                        } else {
                            sb.append(next);
                        }
                    }
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private boolean isReadableText(String text) {
        if (text == null || text.isEmpty()) return false;
        int alpha = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) || Character.isDigit(c) || c == ' ' || c == '-' || c == '/' ||
                c == '.' || c == '@' || c == ':' || c == ',' || c == '&') {
                alpha++;
            }
        }
        return (double) alpha / text.length() > 0.5;
    }

    private ExtractedBuyerInfo parseBuyerInfo(String text) {
        String[] lines = text.split("\n");
        List<String> cleaned = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) cleaned.add(trimmed);
        }

        String fullText = String.join(" ", cleaned);
        String lowerFull = fullText.toLowerCase();
        String email = extractPattern(fullText, "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

        String companyName = null;

        companyName = extractCompanyName(fullText, cleaned);

        if (companyName == null) {
            for (String line : cleaned) {
                if (line.contains("(Pvt)") || line.contains("(Private)") || line.contains("Limited") || line.contains("Ltd")) {
                    companyName = line;
                    break;
                }
            }
        }

        String tin = null, vat = null, bpn = null;
        for (String line : cleaned) {
            String lower = line.toLowerCase();
            if (tin == null && lower.contains("tin")) {
                String val = extractValueAfterColon(line);
                if (val != null && val.length() >= 6) tin = val;
            }
            if (vat == null && lower.contains("vat")) {
                String val = extractValueAfterColonOrSpace(line);
                if (val != null && val.length() >= 6) vat = val;
            }
            if (bpn == null && lower.equals("bpn")) {
                String val = extractValueAfterColon(line);
                if (val != null && val.length() >= 6) bpn = val;
            }
        }

        if (tin == null) tin = extractNumberPattern(fullText, "(?i)tin\\s*:?\\s*(\\d{6,})");
        if (vat == null) vat = extractNumberPattern(fullText, "(?i)vat\\s*:?\\s*(\\d{6,})");
        if (bpn == null) bpn = extractNumberPattern(fullText, "(?i)bpn\\s*:?\\s*(\\d{6,})");

        String accountNo = null;
        for (String line : cleaned) {
            String lower = line.toLowerCase();
            if (lower.contains("account")) {
                String val = extractValueAfterColon(line);
                if (val == null) val = extractValueAfterSpace(line);
                if (val != null && !val.isEmpty()) {
                    accountNo = val;
                    break;
                }
            }
        }
        if (accountNo == null) {
            accountNo = extractPattern(fullText, "(?i)account\\s*(?:no\\.?|#)?\\s*:?\\s*([A-Za-z0-9\\-/_]+)");
        }

        String address = null;
        String[] addressKeywords = {"drive", "road", "street", "avenue", "close", "lane",
                "harare", "bulawayo", "msasa", "borrowdale", "gweru", "mutare"};
        for (String line : cleaned) {
            String lower = line.toLowerCase();
            for (String kw : addressKeywords) {
                if (lower.contains(kw) && line.length() > 5) {
                    address = line;
                    break;
                }
            }
            if (address != null) break;
        }

        if (companyName == null && email != null) {
            companyName = email.substring(0, email.indexOf('@'));
        }

        return new ExtractedBuyerInfo(
                companyName,
                tin,
                vat,
                bpn,
                address,
                email,
                accountNo
        );
    }

    private static final Set<String> ADDRESS_KEYWORDS = Set.of(
            "drive", "road", "street", "avenue", "close", "lane", "way",
            "harare", "bulawayo", "msasa", "borrowdale", "gweru", "mutare",
            "kwekwe", "kadoma", "chegutu", "marondera", "masvingo",
            "p.o.", "po box", "box",
            "ground", "floor", "north", "south", "east", "west",
            "park", "court", "house", "building", "centre", "tower"
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
            "date", "deposit", "payment", "receipt", "invoice", "credit", "debit",
            "note", "memo", "period", "total", "subtotal", "balance", "amount"
    );

    private String extractCompanyName(String fullText, List<String> lines) {
        String best = findBestPvtCompany(lines);
        if (best != null) return best;

        Pattern companyPtn = Pattern.compile(
                "(?<![A-Za-z0-9&])" +
                "([A-Z][A-Za-z0-9&',.\\-() ]{2,60}" +
                "(?:\\(Pvt\\)\\s*Limited|\\(Pvt\\)\\s*Ltd|\\(Private\\)\\s*Limited|Limited|Ltd))" +
                "(?![A-Za-z0-9&])"
        );
        Matcher cm = companyPtn.matcher(fullText);
        String regexBest = null;
        int regexBestStart = -1;
        while (cm.find()) {
            String match = cm.group(1).trim();
            if (!match.contains("(Pvt)") && !match.contains("(Private)") &&
                !match.contains("Ltd") && !match.contains("Limited")) continue;
            int companySuffixCount = 0;
            int idx = 0;
            while (true) {
                int found = match.indexOf("Limited", idx);
                if (found < 0) found = match.indexOf("Ltd", idx);
                if (found < 0) break;
                companySuffixCount++;
                idx = found + 1;
            }
            String candidate = stripLeadingNoise(match);
            if (candidate == null) candidate = match;
            if (companySuffixCount <= 1 && candidate.length() > 5) {
                if (cm.start() > regexBestStart) {
                    regexBest = candidate;
                    regexBestStart = cm.start();
                }
            }
        }
        if (regexBest != null) return regexBest;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() > 5 && Character.isUpperCase(trimmed.charAt(0))) {
                boolean hasUpper = false;
                boolean hasLower = false;
                for (int i = 1; i < trimmed.length(); i++) {
                    char c = trimmed.charAt(i);
                    if (Character.isUpperCase(c)) hasUpper = true;
                    if (Character.isLowerCase(c)) hasLower = true;
                }
                if (hasUpper && hasLower && trimmed.length() > 8) {
                    return trimmed;
                }
            }
        }

        return null;
    }

    private String findBestPvtCompany(List<String> lines) {
        String best = null;
        int bestLen = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean hasPvt = line.contains("(Pvt)") || line.contains("(Private)");
            boolean hasSuffix = line.contains("Ltd") || line.contains("Limited");
            if (!hasSuffix) continue;

            String merged;
            if (hasPvt) {
                merged = mergePrefixLines(lines, i);
            } else {
                merged = mergePrefixLines(lines, i);
                if (merged == null) merged = line.trim();
            }
            if (merged == null) continue;

            String stripped = stripLeadingNoise(merged);
            log.debug("candidate i={} hasPvt={} raw='{}' stripped='{}'", i, hasPvt, merged, stripped);
            if (stripped == null) continue;

            int effectiveLen = stripped.length() + (hasPvt ? 100 : 0);
            if (effectiveLen > bestLen) {
                bestLen = effectiveLen;
                best = stripped;
            }
        }
        return best;
    }

    private String stripLeadingNoise(String name) {
        String[] words = name.split("\\s+");
        int start = 0;
        Pattern dateWord = Pattern.compile("(?i)^(january|february|march|april|may|june|july|august|september|october|november|december)$");
        while (start < words.length) {
            String w = words[start].toLowerCase();
            boolean selfColon = words[start].endsWith(":");
            boolean nextColon = start + 1 < words.length && words[start + 1].endsWith(":");
            if (selfColon) {
                start++;
            } else if (nextColon) {
                start += 2;
            } else if (dateWord.matcher(w).matches() && start + 1 < words.length &&
                       words[start + 1].matches("\\d{4}")) {
                start += 2;
            } else if (STOP_WORDS.contains(w) || ADDRESS_KEYWORDS.contains(w)) {
                start++;
            } else {
                break;
            }
        }
        if (start >= words.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < words.length; i++) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private String mergePrefixLines(List<String> lines, int idx) {
        String line = lines.get(idx);
        StringBuilder merged = new StringBuilder(line);
        for (int j = idx - 1; j >= 0; j--) {
            String prev = lines.get(j).trim().toLowerCase();
            if (prev.length() < 25 && prev.length() >= 2 &&
                Character.isUpperCase(lines.get(j).trim().charAt(0)) &&
                !prev.contains(":") && !prev.contains("@") &&
                !prev.matches(".*[0-9/\\\\.,;!?(){}\\[\\]].*")) {
                boolean hasCompanySuffix = prev.contains("ltd") || prev.contains("limited") ||
                    prev.contains("(pvt)") || prev.contains("(private)");
                boolean isStop = hasCompanySuffix ||
                    STOP_WORDS.contains(prev) ||
                    ADDRESS_KEYWORDS.stream().anyMatch(k -> prev.contains(k) || prev.equals(k));
                if (isStop) break;
                merged.insert(0, " ");
                merged.insert(0, lines.get(j).trim());
            } else {
                break;
            }
        }
        String result = merged.toString().trim();
        return result.length() > 5 ? result : null;
    }

    private String extractPattern(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group() : null;
    }

    private String extractNumberPattern(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String extractValueAfterColon(String line) {
        int colon = line.indexOf(':');
        if (colon >= 0) {
            String after = line.substring(colon + 1).trim();
            if (!after.isEmpty() && after.chars().anyMatch(Character::isDigit)) {
                return after.replaceAll("[^0-9]", "");
            }
        }
        return null;
    }

    private String extractValueAfterColonOrSpace(String line) {
        int colon = line.indexOf(':');
        if (colon >= 0) {
            String after = line.substring(colon + 1).trim();
            if (!after.isEmpty() && after.chars().anyMatch(Character::isDigit)) {
                return after.replaceAll("[^0-9]", "");
            }
        }
        return null;
    }

    private String extractValueAfterSpace(String line) {
        int space = line.indexOf(' ');
        int hash = line.indexOf('#');
        int start = Math.max(space, hash);
        if (start < 0) return null;
        String after = line.substring(start + 1).trim();
        if (!after.isEmpty()) {
            return after.split("\\s+")[0].replaceAll("[^A-Za-z0-9\\-/_]", "");
        }
        return null;
    }
}
