package com.esolutions.massmailer.customer.service;

import com.esolutions.massmailer.model.DeliveryMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bulk customer registry loader from a CSV upload.
 *
 * <h3>Accepted columns (header is required, order is free)</h3>
 * <pre>
 *   erpCustomerId  (required — the unique key for matching existing customers)
 *   email
 *   name
 *   phone
 *   companyName
 *   tradingName
 *   vatNumber
 *   tinNumber
 *   bpn
 *   addressLine1
 *   addressLine2
 *   city
 *   country
 *   deliveryMode   (EMAIL | AS4 | BOTH; blank = inherit org default)
 *   erpSource
 *   erpCustomerId  (ERP-native customer key, e.g. Exor TenantCode)
 *   peppolParticipantId
 * </pre>
 *
 * <p>Lines that fail validation are skipped and reported per-row in {@link ImportResult#errors()};
 * the import does not abort on a single bad row.
 *
 * <h3>Identifier semantics</h3>
 * Only {@code erpCustomerId} is treated as unique. {@code vatNumber}, {@code tinNumber}, and
 * {@code bpn} are stored verbatim and are NOT validated for uniqueness, format, or referential
 * integrity — a single buyer can legitimately operate multiple branch accounts that share the
 * same VAT / TIN / BPN. The importer will happily create or update many rows with the same
 * fiscal identifiers under the same organisation.
 */
@Service
public class CustomerCsvImportService {

    private static final Logger log = LoggerFactory.getLogger(CustomerCsvImportService.class);

    public record ImportError(int row, String message, String rawLine) {}

    /**
     * Outcome of a single CSV row — emitted for every row processed, in input order.
     * {@code identifier} carries the customer's erpCustomerId (the unique key for the import).
     */
    public record RowOutcome(int row, String identifier, String status, String message) {}

    public record ImportResult(
            int totalRows,
            int created,
            int updated,
            int skipped,
            List<ImportError> errors,
            List<RowOutcome> outcomes
    ) {}

    public record PreviewResult(
            List<String> columns,
            List<List<String>> sampleRows,
            Map<String, String> suggestedMapping
    ) {}

    /** Set of target fields the importer understands. Used for mapping validation + auto-suggest. */
    private static final List<String> TARGET_FIELDS = List.of(
            "email", "name", "phone",
            "email2", "name2", "phone2",
            "email3", "name3", "phone3",
            "email4", "name4", "phone4",
            "companyName", "tradingName",
            "vatNumber", "tinNumber", "bpn",
            "addressLine1", "addressLine2", "city", "country",
            "deliveryMode", "erpSource", "erpCustomerId", "peppolParticipantId"
    );

    /** Each entry is one (emailTarget, nameTarget, phoneTarget) tuple to upsert as a contact. */
    private static final List<String[]> CONTACT_SLOTS = List.of(
            new String[]{"email",  "name",  "phone"},
            new String[]{"email2", "name2", "phone2"},
            new String[]{"email3", "name3", "phone3"},
            new String[]{"email4", "name4", "phone4"}
    );

    private final CustomerService customerService;
    private final ContactService contactService;

    public CustomerCsvImportService(CustomerService customerService, ContactService contactService) {
        this.customerService = customerService;
        this.contactService = contactService;
    }

    public ImportResult importCsv(UUID organizationId, InputStream csv) throws IOException {
        return importCsv(organizationId, csv, null);
    }

    /**
     * Reads up to {@code sampleLimit} data rows after the header for a preview UI.
     * Every column is preserved — the caller maps source columns to target fields explicitly.
     * Also returns a heuristic suggested mapping (target → source column name).
     */
    public PreviewResult preview(InputStream csv, int sampleLimit) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String headerLine = readRecord(reader);
            if (headerLine == null || headerLine.isBlank()) {
                throw new IllegalArgumentException("CSV is empty — header row required");
            }
            if (headerLine.charAt(0) == '﻿') headerLine = headerLine.substring(1);
            char delim = detectDelimiter(headerLine);
            List<String> columns = parseLine(headerLine, delim);

            List<List<String>> samples = new ArrayList<>();
            String record;
            while ((record = readRecord(reader)) != null && samples.size() < sampleLimit) {
                if (record.isBlank()) continue;
                samples.add(parseLine(record, delim));
            }
            return new PreviewResult(columns, samples, suggestMapping(columns));
        }
    }

    /**
     * Performs the bulk import.
     *
     * @param mapping optional explicit field map: target field → source column name (case-insensitive).
     *                When null or empty, falls back to matching by target field name in the header
     *                (case-insensitive).
     */
    public ImportResult importCsv(UUID organizationId, InputStream csv,
                                   Map<String, String> mapping) throws IOException {
        int total = 0, created = 0, updated = 0, skipped = 0;
        List<ImportError> errors = new ArrayList<>();
        List<RowOutcome> outcomes = new ArrayList<>();

        try (var reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String headerLine = readRecord(reader);
            if (headerLine == null || headerLine.isBlank()) {
                throw new IllegalArgumentException("CSV is empty — header row required");
            }
            // Strip BOM if present
            if (headerLine.charAt(0) == '﻿') headerLine = headerLine.substring(1);

            char delim = detectDelimiter(headerLine);
            List<String> headers = parseLine(headerLine, delim);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                idx.put(headers.get(i).trim().toLowerCase(Locale.ROOT), i);
            }

            // Build target → column-index lookup. When an explicit mapping is supplied,
            // it overrides header-name matching for that target.
            Map<String, Integer> target = new HashMap<>();
            for (String t : TARGET_FIELDS) {
                String source = mapping != null ? mapping.get(t) : null;
                if (source != null && !source.isBlank()) {
                    Integer ci = idx.get(source.trim().toLowerCase(Locale.ROOT));
                    if (ci != null) target.put(t, ci);
                } else if (idx.containsKey(t.toLowerCase(Locale.ROOT))) {
                    target.put(t, idx.get(t.toLowerCase(Locale.ROOT)));
                }
            }
            if (!target.containsKey("erpCustomerId")) {
                throw new IllegalArgumentException(
                        "No column mapped to 'erpCustomerId'. The customer ID is the unique key for the import — "
                                + "provide a mapping or include an 'erpCustomerId' header.");
            }

            String line;
            int rowNum = 1; // header is row 1 in the file
            while ((line = readRecord(reader)) != null) {
                rowNum++;
                if (line.isBlank()) continue;
                total++;
                String customerId = null;
                try {
                    List<String> fields = parseLine(line, delim);
                    customerId = byTarget(fields, target, "erpCustomerId");
                    if (customerId == null || customerId.isBlank()) {
                        errors.add(new ImportError(rowNum, "Missing erpCustomerId", line));
                        outcomes.add(new RowOutcome(rowNum, null, "SKIPPED", "Missing erpCustomerId"));
                        skipped++;
                        continue;
                    }
                    DeliveryMode mode = parseMode(byTarget(fields, target, "deliveryMode"));

                    String rawCompanyName = byTarget(fields, target, "companyName");
                    String companyName = extractCompanyName(rawCompanyName);

                    var result = customerService.upsertByErpCustomerId(
                            organizationId,
                            customerId,
                            companyName,
                            byTarget(fields, target, "tradingName"),
                            byTarget(fields, target, "erpSource"),
                            mode,
                            byTarget(fields, target, "vatNumber"),
                            byTarget(fields, target, "tinNumber"),
                            byTarget(fields, target, "bpn"),
                            byTarget(fields, target, "peppolParticipantId"),
                            byTarget(fields, target, "addressLine1"),
                            byTarget(fields, target, "addressLine2"),
                            byTarget(fields, target, "city"),
                            byTarget(fields, target, "country")
                    );

                    java.util.Set<String> seenEmails = new java.util.HashSet<>();
                    for (String[] slot : CONTACT_SLOTS) {
                        String slotEmail = pickFirstEmail(byTarget(fields, target, slot[0]));
                        if (slotEmail == null) continue;
                        String normalized = slotEmail.toLowerCase(Locale.ROOT);
                        if (!seenEmails.add(normalized)) continue;
                        contactService.upsert(
                                result.customer().getId(),
                                slotEmail,
                                byTarget(fields, target, slot[1]),
                                byTarget(fields, target, slot[2])
                        );
                    }

                    if (result.created()) {
                        created++;
                        outcomes.add(new RowOutcome(rowNum, customerId, "CREATED", null));
                    } else {
                        updated++;
                        outcomes.add(new RowOutcome(rowNum, customerId, "UPDATED", null));
                    }
                } catch (Exception e) {
                    String msg = rootCauseMessage(e);
                    log.warn("Import row {} (customerId={}) failed: {}", rowNum, customerId, msg, e);
                    errors.add(new ImportError(rowNum, msg, line));
                    outcomes.add(new RowOutcome(rowNum, customerId, "ERROR", msg));
                    skipped++;
                }
            }
        }
        log.info("CSV import for org {} — total={}, created={}, updated={}, skipped={}, errors={}",
                organizationId, total, created, updated, skipped, errors.size());
        return new ImportResult(total, created, updated, skipped, errors, outcomes);
    }

    // ── helpers ──

    private static String byTarget(List<String> fields, Map<String, Integer> target, String key) {
        Integer i = target.get(key);
        if (i == null || i >= fields.size()) return null;
        String v = fields.get(i);
        if (v == null || v.isBlank()) return null;
        v = v.trim();
        // Strip leading single-quote (Excel text-prefix artifact)
        if (v.startsWith("'")) v = v.substring(1).trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * Some ERPs put multiple emails in a single cell, separated by ; , or whitespace.
     * Pick the first one that looks like an email address.
     */
    private static String pickFirstEmail(String cell) {
        if (cell == null || cell.isBlank()) return null;
        for (String token : cell.split("[;,\\s]+")) {
            if (token.contains("@") && token.contains(".")) return token.trim();
        }
        return null;
    }

    /**
     * Heuristic mapping suggestion — given the actual CSV headers, guess which one
     * corresponds to each target field. Used to pre-fill the mapping wizard.
     *
     * The matcher normalizes both sides (lowercase, strip non-alphanumeric) and
     * uses substring matches across a small synonym dictionary.
     */
    private static Map<String, String> suggestMapping(List<String> columns) {
        // target → ordered list of synonyms (any partial match wins)
        Map<String, List<String>> synonyms = new HashMap<>();
        synonyms.put("email", List.of("generalcontactemailnos", "accountscontactemail", "billingemail", "invoiceemail", "email"));
        synonyms.put("name", List.of("generalcontactname", "accountscontactname", "contactname", "name"));
        synonyms.put("phone", List.of("generalcontacttelephone", "generalcontactmobile",
                "mobile", "phone", "telephone"));
        synonyms.put("email2", List.of("generalcontactemail"));
        synonyms.put("name2",  List.of("accountscontactname", "premisescontactname", "generalcontactname"));
        synonyms.put("phone2", List.of("accountscontactnumbers", "premisescontactnumbers", "generalcontactmobile", "generalcontacttelephone"));
        synonyms.put("email3", List.of("premisescontactemail"));
        synonyms.put("name3",  List.of("premisescontactname"));
        synonyms.put("phone3", List.of("premisescontactmobile", "premisescontactnumbers"));
        synonyms.put("email4", List.of("marketinglegalcontactemail"));
        synonyms.put("name4",  List.of("marketinglegalcontactname"));
        synonyms.put("phone4", List.of("marketinglegalcontactmobile", "marketinglegalcontactnumbers"));
        synonyms.put("companyName", List.of("lesseename", "listortradingasname", "lessoraccountname", "legalname", "companyname", "tenantname"));
        synonyms.put("tradingName", List.of("listortradingasname", "tradingas", "tradingname"));
        synonyms.put("vatNumber", List.of("vatnumber", "vat"));
        synonyms.put("tinNumber", List.of("filereference", "tinnumber", "tin"));
        synonyms.put("bpn", List.of("bpn", "businesspartnernumber", "zimrabpn"));
        synonyms.put("addressLine1", List.of("postaladdress1", "address1", "addressline1", "street"));
        synonyms.put("addressLine2", List.of("postaladdress2", "address2", "addressline2"));
        synonyms.put("city", List.of("postaladdress3", "city", "town"));
        synonyms.put("country", List.of("country"));
        synonyms.put("deliveryMode", List.of("deliverymode", "statementdeliverymethod",
                "correspondencetypepref"));
        synonyms.put("erpSource", List.of("erpsource", "source"));
        synonyms.put("erpCustomerId", List.of("tenantcode", "tenantid", "accountno", "accountnumber",
                "accountcode", "customerid", "erpcustomerid", "customercode"));
        synonyms.put("peppolParticipantId", List.of("peppolparticipantid", "participantid", "peppolid"));

        Map<String, String> normalized = new HashMap<>(); // normalized → original header
        for (String c : columns) {
            normalized.put(c.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""), c);
        }

        Map<String, String> out = new HashMap<>();
        for (var e : synonyms.entrySet()) {
            for (String hint : e.getValue()) {
                // exact normalized match first
                if (normalized.containsKey(hint)) {
                    out.put(e.getKey(), normalized.get(hint));
                    break;
                }
                // substring match
                String pick = null;
                for (var n : normalized.entrySet()) {
                    if (n.getKey().contains(hint)) { pick = n.getValue(); break; }
                }
                if (pick != null) {
                    out.put(e.getKey(), pick);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Unwraps wrapped exceptions (Spring DataAccessException → Hibernate → JDBC SQLException)
     * to surface the actual database error message — e.g. the name of the unique constraint
     * that rejected the insert.
     */
    private static String rootCauseMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) msg = root.getClass().getSimpleName();
        // Trim multi-line driver messages to the first informative line
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl).trim() : msg.trim();
    }

    private static final Map<String, DeliveryMode> MODE_ALIASES = Map.of(
        "1", DeliveryMode.EMAIL,
        "2", DeliveryMode.AS4,
        "4", DeliveryMode.BOTH
    );

    private static DeliveryMode parseMode(String s) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim();
        DeliveryMode alias = MODE_ALIASES.get(trimmed);
        if (alias != null) return alias;
        // 3 and other unrecognised values → inherit org default
        return null;
    }

    /**
     * Extracts the legal entity name from a lessee name field that often follows the pattern
     * {@code "<property description> (<number>) (<Entity Name>)"}. When the value has 2+ complete
     * parenthetical groups the last non-generic, non-numeric group is returned. An incomplete
     * trailing {@code (} with meaningful content (>{@code 3} chars) is also accepted. Values with
     * 0–1 parenthetical groups are returned as-is.
     */
    private static final Set<String> PAREN_GENERIC = Set.of("private", "pvt", "pty", "ltd", "usd", "us", "pv");

    static String extractCompanyName(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        Pattern p = Pattern.compile("\\(([^)]*)\\)");
        Matcher m = p.matcher(raw);

        List<String> groups = new ArrayList<>();
        while (m.find()) {
            groups.add(m.group(1));
        }

        int lastOpen = raw.lastIndexOf('(');
        int lastClose = raw.lastIndexOf(')');
        String incomplete = "";
        if (lastOpen > lastClose) {
            incomplete = raw.substring(lastOpen + 1).trim();
        }

        // Real incomplete trailing group → use it (4+ chars avoids single-word truncation artifacts)
        if (incomplete.length() > 3 && !groups.isEmpty()) {
            return incomplete;
        }

        // 2+ complete groups → last non-generic non-numeric is the entity name
        if (groups.size() >= 2) {
            for (int i = groups.size() - 1; i >= 0; i--) {
                String g = groups.get(i).trim();
                if (!PAREN_GENERIC.contains(g.toLowerCase(Locale.ROOT)) && !g.matches("\\d+")) {
                    return g;
                }
            }
        }

        return raw;
    }

    /**
     * Reads one logical CSV record, joining physical lines while a quoted field is still open.
     * Returns null at EOF. Embedded newlines inside quoted fields are preserved as literal '\n'.
     */
    static String readRecord(BufferedReader reader) throws IOException {
        String first = reader.readLine();
        if (first == null) return null;
        StringBuilder sb = new StringBuilder(first);
        while (hasUnclosedQuote(sb)) {
            String next = reader.readLine();
            if (next == null) break;
            sb.append('\n').append(next);
        }
        return sb.toString();
    }

    private static boolean hasUnclosedQuote(CharSequence s) {
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '"') {
                if (inQuotes && i + 1 < s.length() && s.charAt(i + 1) == '"') { i++; continue; }
                inQuotes = !inQuotes;
            }
        }
        return inQuotes;
    }

    /**
     * RFC 4180-ish CSV/TSV line parser — supports quoted fields, escaped quotes ("") inside quotes.
     * Delimiters inside quoted fields are preserved. Returns fields in order.
     */
    static List<String> parseLine(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == delimiter) {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"' && cur.length() == 0) {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * Auto-detect whether the file is comma-separated or tab-separated by counting
     * each character in the header row. Whichever appears more wins.
     */
    static char detectDelimiter(String headerLine) {
        long tabs = headerLine.chars().filter(c -> c == '\t').count();
        long commas = headerLine.chars().filter(c -> c == ',').count();
        return tabs > commas ? '\t' : ',';
    }
}
