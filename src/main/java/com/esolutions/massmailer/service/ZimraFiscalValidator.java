package com.esolutions.massmailer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates that a PDF invoice has been fiscalised by ZIMRA before dispatch.
 *
 * ZIMRA fiscal devices embed the following markers in the PDF content stream
 * as plain text (visible in the rendered document):
 *
 *   - Verification Code  e.g. E960606BFCB6F08A
 *   - Verification URL   https://fdms.zimra.co.zw/...
 *   - Device ID          numeric
 *   - Fiscal Day         numeric
 *   - Fiscal Invoice Number
 *   - Global Receipt Number
 *
 * Because these are rendered text strings, they appear as literal bytes in
 * the PDF content stream and can be detected without a full PDF parser.
 *
 * Validation strategy:
 *   1. Require the ZIMRA verification URL domain (fdms.zimra.co.zw) — this is
 *      the strongest single indicator of a fiscalised document.
 *   2. Require at least one of: a 16-char hex verification code pattern, or
 *      the text "Verification Code" label.
 *   3. Require at least one of: "Device ID", "Fiscal Day", or "Fiscal Invoice".
 *
 * This is intentionally lenient on formatting — different fiscal devices and
 * PDF generators may lay out the block differently.
 */
@Component
public class ZimraFiscalValidator {

    private static final Logger log = LoggerFactory.getLogger(ZimraFiscalValidator.class);

    // ZIMRA FDMS verification URL domain — present in every fiscalised invoice
    private static final String FDMS_DOMAIN = "fdms.zimra.co.zw";

    // 16-character uppercase hex verification code (e.g. E960606BFCB6F08A)
    private static final Pattern VERIFICATION_CODE_PATTERN =
            Pattern.compile("[0-9A-F]{16}");

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

        // Decode the PDF bytes as Latin-1 to preserve all byte values while
        // still allowing string searching. PDF content streams are not UTF-8.
        String content = new String(pdfBytes, StandardCharsets.ISO_8859_1);

        List<String> errors = new ArrayList<>();

        // ── Rule 1: ZIMRA FDMS domain must be present ──────────────────────
        if (!content.contains(FDMS_DOMAIN)) {
            errors.add("Missing ZIMRA FDMS verification URL (fdms.zimra.co.zw). " +
                    "The invoice does not appear to have been fiscalised.");
        }

        // ── Rule 2: Verification code label or 16-char hex code ────────────
        boolean hasCodeLabel = content.contains(LABEL_VERIFICATION_CODE)
                || content.contains(LABEL_VERIFICATION_URL);
        boolean hasCodePattern = VERIFICATION_CODE_PATTERN.matcher(content).find();

        if (!hasCodeLabel && !hasCodePattern) {
            errors.add("Missing ZIMRA verification code. " +
                    "Expected 'Verification Code' label or a 16-character hex code (e.g. E960606BFCB6F08A).");
        }

        // ── Rule 3: At least one fiscal device field ────────────────────────
        boolean hasFiscalField = content.contains(LABEL_DEVICE_ID)
                || content.contains(LABEL_FISCAL_DAY)
                || content.contains(LABEL_FISCAL_INVOICE)
                || content.contains(LABEL_GLOBAL_RECEIPT);

        if (!hasFiscalField) {
            errors.add("Missing fiscal device fields. " +
                    "Expected at least one of: 'Device ID', 'Fiscal Day', 'Fiscal Invoice Number', 'Global Receipt Number'.");
        }

        if (!errors.isEmpty()) {
            log.warn("Fiscalisation validation FAILED for invoice {}: {}", invoiceNumber, errors);
            return ValidationResult.fail(errors);
        }

        log.debug("Fiscalisation validation passed for invoice {}", invoiceNumber);
        return ValidationResult.ok();
    }
}
