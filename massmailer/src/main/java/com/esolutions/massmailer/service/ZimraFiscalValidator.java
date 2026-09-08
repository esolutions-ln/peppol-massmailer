package com.esolutions.massmailer.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates that a PDF invoice has been fiscalised by ZIMRA before dispatch.
 *
 * ZIMRA fiscal devices embed the following markers as rendered text (visible
 * in the document):
 *
 *   - Verification Code  e.g. E960606BFCB6F08A
 *   - Verification URL   https://fdms.zimra.co.zw/...
 *   - Device ID          numeric
 *   - Fiscal Day         numeric
 *   - Fiscal Invoice Number
 *   - Global Receipt Number
 *
 * PDF content streams are almost always FlateDecode-compressed, so these
 * markers cannot be found by scanning the raw PDF bytes — they only exist
 * once the streams are decompressed and the text extracted. This validator
 * uses Apache PDFBox to extract text before applying the marker checks. If
 * the bytes cannot be parsed as a PDF at all (e.g. in unit tests that pass
 * plain text), it falls back to scanning the raw bytes directly.
 *
 * Validation strategy:
 *   1. Require a ZIMRA verification URL domain (*.zimra.co.zw with an "fdms"
 *      prefix, e.g. fdms.zimra.co.zw or the fdmstest.zimra.co.zw sandbox host)
 *      — this is the strongest single indicator of a fiscalised document.
 *   2. Require at least one of: a 16-char hex verification code pattern
 *      (contiguous or hyphen-grouped, e.g. B993-BD3C-88A8-0A3C), or the text
 *      "Verification Code" label.
 *
 * Device ID / Fiscal Day / Fiscal Invoice Number / Global Receipt Number are
 * NOT required: some legitimate ZIMRA fiscal receipt formats (e.g. property
 * management / rental invoices) omit those labels entirely while still
 * carrying a valid verification code and FDMS URL. Their presence is logged
 * when available but does not gate dispatch.
 *
 * This is intentionally lenient on formatting — different fiscal devices and
 * PDF generators may lay out the block differently.
 */
@Component
public class ZimraFiscalValidator {

    private static final Logger log = LoggerFactory.getLogger(ZimraFiscalValidator.class);

    // ZIMRA FDMS verification URL domain, allowing environment prefixes like
    // "fdms.zimra.co.zw" or "fdmstest.zimra.co.zw"
    private static final Pattern FDMS_DOMAIN_PATTERN =
            Pattern.compile("(?i)fdms[a-z0-9-]*\\.zimra\\.co\\.zw");

    // 16-character uppercase hex verification code, optionally grouped with
    // hyphens (e.g. E960606BFCB6F08A or B993-BD3C-88A8-0A3C)
    private static final Pattern VERIFICATION_CODE_PATTERN =
            Pattern.compile("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}|[0-9A-F]{16}");

    // Text labels that appear in the fiscal block
    private static final String LABEL_VERIFICATION_CODE = "Verification Code";
    private static final String LABEL_VERIFICATION_URL  = "Verification URL";
    private static final String LABEL_DEVICE_ID         = "Device ID";
    private static final String LABEL_FISCAL_DAY        = "Fiscal Day";
    private static final String LABEL_FISCAL_INVOICE    = "Fiscal Invoice";
    private static final String LABEL_GLOBAL_RECEIPT    = "Global Receipt";

    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }
        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }

    /**
     * Validates that the PDF bytes contain ZIMRA fiscal markers.
     *
     * @param pdfBytes raw PDF bytes
     * @param invoiceNumber invoice number for logging context
     * @return ValidationResult — check {@code valid()} before proceeding
     */
    public ValidationResult validate(byte[] pdfBytes, String invoiceNumber) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return ValidationResult.fail(List.of("PDF is empty"));
        }

        String content = extractText(pdfBytes, invoiceNumber);

        List<String> errors = new ArrayList<>();

        // ── Rule 1: ZIMRA FDMS domain must be present ──────────────────────
        if (!FDMS_DOMAIN_PATTERN.matcher(content).find()) {
            errors.add("Missing ZIMRA FDMS verification URL (fdms.zimra.co.zw). " +
                    "The invoice does not appear to have been fiscalised.");
        }

        // ── Rule 2: Verification code label or hex code ────────────────────
        boolean hasCodeLabel = content.contains(LABEL_VERIFICATION_CODE)
                || content.contains(LABEL_VERIFICATION_URL);
        boolean hasCodePattern = VERIFICATION_CODE_PATTERN.matcher(content).find();

        if (!hasCodeLabel && !hasCodePattern) {
            errors.add("Missing ZIMRA verification code. " +
                    "Expected 'Verification Code' label or a 16-character hex code (e.g. E960606BFCB6F08A).");
        }

        // ── Fiscal device fields are informational only ─────────────────────
        // Not every legitimate fiscal receipt format includes these labels
        // (e.g. property/rental invoices), so their absence does not fail
        // validation — only rules 1 and 2 gate dispatch.
        boolean hasFiscalField = content.contains(LABEL_DEVICE_ID)
                || content.contains(LABEL_FISCAL_DAY)
                || content.contains(LABEL_FISCAL_INVOICE)
                || content.contains(LABEL_GLOBAL_RECEIPT);

        if (!hasFiscalField) {
            log.debug("Invoice {} has no fiscal device fields (Device ID/Fiscal Day/etc.) — " +
                    "not required, proceeding based on verification code + FDMS URL.", invoiceNumber);
        }

        if (!errors.isEmpty()) {
            log.warn("Fiscalisation validation FAILED for invoice {}: {}", invoiceNumber, errors);
            return ValidationResult.fail(errors);
        }

        log.debug("Fiscalisation validation passed for invoice {}", invoiceNumber);
        return ValidationResult.ok();
    }

    /**
     * Extracts the rendered text of the PDF via PDFBox (decompressing content
     * streams in the process). Falls back to a raw Latin-1 byte scan if the
     * bytes cannot be parsed as a PDF, so callers passing plain text (e.g.
     * unit tests) continue to work unchanged.
     */
    private String extractText(byte[] pdfBytes, String invoiceNumber) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not parse invoice {} as a PDF ({}); falling back to raw byte scan",
                    invoiceNumber, e.getMessage());
            return new String(pdfBytes, StandardCharsets.ISO_8859_1);
        }
    }
}
