package com.esolutions.massmailer.service;

import com.esolutions.massmailer.service.ZimraFiscalValidator.ValidationResult;
import net.jqwik.api.*;
import net.jqwik.api.arbitraries.StringArbitrary;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for ZimraFiscalValidator.
 *
 * P7: Fiscal Validation Gate — validates Requirements 6.1, 6.2, 6.3
 *
 * No Spring context needed — ZimraFiscalValidator is instantiated directly.
 */
class ZimraFiscalValidatorPropertyTest {

    private final ZimraFiscalValidator validator = new ZimraFiscalValidator();

    // ── Shared marker constants ───────────────────────────────────────────────

    private static final String FDMS_DOMAIN = "fdms.zimra.co.zw";
    private static final String VERIFICATION_CODE_LABEL = "Verification Code";
    private static final String VERIFICATION_URL_LABEL  = "Verification URL";
    private static final String HEX16_PATTERN           = "ABCDEF0123456789"; // 16-char hex
    private static final String DEVICE_ID               = "Device ID";
    private static final String FISCAL_DAY              = "Fiscal Day";
    private static final String FISCAL_INVOICE          = "Fiscal Invoice";
    private static final String GLOBAL_RECEIPT          = "Global Receipt";

    // ── Property P7: Fiscal Validation Gate ──────────────────────────────────
    // Validates: Requirements 6.1, 6.2, 6.3

    /**
     * P7a — Content containing all three ZIMRA marker rules must be valid.
     *
     * Rule 1: fdms.zimra.co.zw
     * Rule 2: "Verification Code" label (or URL label, or 16-char hex)
     * Rule 3: at least one of Device ID / Fiscal Day / Fiscal Invoice / Global Receipt
     *
     * **Validates: Requirements 6.1, 6.2**
     */
    @Property
    void contentWithAllThreeMarkersIsValid(
            @ForAll("paddingStrings") String prefix,
            @ForAll("paddingStrings") String middle,
            @ForAll("paddingStrings") String suffix,
            @ForAll("rule2Markers")   String rule2Marker,
            @ForAll("rule3Markers")   String rule3Marker
    ) {
        String content = prefix
                + FDMS_DOMAIN + middle
                + rule2Marker + suffix
                + rule3Marker;

        ValidationResult result = validate(content);

        assertThat(result.valid())
                .as("Content with all three markers should be valid")
                .isTrue();
        assertThat(result.errors())
                .as("Valid result must have no errors")
                .isEmpty();
    }

    /**
     * P7b — Content missing Rule 1 (fdms.zimra.co.zw) must be invalid with errors.
     *
     * **Validates: Requirements 6.1, 6.3**
     */
    @Property
    void contentMissingRule1IsInvalid(
            @ForAll("paddingStrings") String padding,
            @ForAll("rule2Markers")   String rule2Marker,
            @ForAll("rule3Markers")   String rule3Marker
    ) {
        // Deliberately omit FDMS_DOMAIN
        String content = padding + rule2Marker + padding + rule3Marker;

        ValidationResult result = validate(content);

        assertThat(result.valid())
                .as("Content missing fdms.zimra.co.zw should be invalid")
                .isFalse();
        assertThat(result.errors())
                .as("Invalid result must have non-empty errors")
                .isNotEmpty();
    }

    /**
     * P7c — Content missing Rule 2 (no verification code label or hex pattern)
     * must be invalid with errors.
     *
     * **Validates: Requirements 6.1, 6.3**
     */
    @Property
    void contentMissingRule2IsInvalid(
            @ForAll("paddingStrings") String padding,
            @ForAll("rule3Markers")   String rule3Marker
    ) {
        // Include Rule 1 and Rule 3 but omit any Rule 2 marker
        String content = FDMS_DOMAIN + padding + rule3Marker;

        ValidationResult result = validate(content);

        assertThat(result.valid())
                .as("Content missing verification code/label should be invalid")
                .isFalse();
        assertThat(result.errors())
                .as("Invalid result must have non-empty errors")
                .isNotEmpty();
    }

    /**
     * P7d — Content missing Rule 3 (no fiscal device field) must be invalid with errors.
     *
     * **Validates: Requirements 6.1, 6.3**
     */
    @Property
    void contentMissingRule3IsInvalid(
            @ForAll("paddingStrings") String padding,
            @ForAll("rule2Markers")   String rule2Marker
    ) {
        // Include Rule 1 and Rule 2 but omit any Rule 3 marker
        String content = FDMS_DOMAIN + padding + rule2Marker;

        ValidationResult result = validate(content);

        assertThat(result.valid())
                .as("Content missing fiscal device fields should be invalid")
                .isFalse();
        assertThat(result.errors())
                .as("Invalid result must have non-empty errors")
                .isNotEmpty();
    }

    /**
     * P7e — The invariant errors.isEmpty() == valid() must hold for ALL inputs.
     *
     * This covers both valid and invalid content to ensure the invariant is universal.
     *
     * **Validates: Requirements 6.2, 6.3**
     */
    @Property
    void errorsEmptyEqualsValidForAllInputs(@ForAll("arbitraryPdfContent") String content) {
        ValidationResult result = validate(content);

        assertThat(result.errors().isEmpty())
                .as("errors.isEmpty() must equal valid() for all inputs")
                .isEqualTo(result.valid());
    }

    // ── Arbitraries ──────────────────────────────────────────────────────────

    /**
     * Generates arbitrary padding strings (printable ASCII, no ZIMRA markers).
     * Kept short to avoid accidentally embedding marker substrings.
     */
    @Provide
    Arbitrary<String> paddingStrings() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0)
                .ofMaxLength(20);
    }

    /**
     * Generates valid Rule 2 markers: "Verification Code" label, "Verification URL"
     * label, or a 16-character uppercase hex string.
     */
    @Provide
    Arbitrary<String> rule2Markers() {
        Arbitrary<String> codeLabel = Arbitraries.just(VERIFICATION_CODE_LABEL);
        Arbitrary<String> urlLabel  = Arbitraries.just(VERIFICATION_URL_LABEL);
        Arbitrary<String> hexCode   = Arbitraries.strings()
                .withChars("0123456789ABCDEF")
                .ofLength(16);
        return Arbitraries.oneOf(codeLabel, urlLabel, hexCode);
    }

    /**
     * Generates valid Rule 3 markers: one of the four fiscal device field labels.
     */
    @Provide
    Arbitrary<String> rule3Markers() {
        return Arbitraries.of(DEVICE_ID, FISCAL_DAY, FISCAL_INVOICE, GLOBAL_RECEIPT);
    }

    /**
     * Generates arbitrary PDF-like content strings (mix of valid and invalid).
     * Uses printable ASCII plus the ZIMRA markers occasionally.
     */
    @Provide
    Arbitrary<String> arbitraryPdfContent() {
        Arbitrary<String> randomContent = Arbitraries.strings()
                .withCharRange(' ', '~')
                .ofMinLength(0)
                .ofMaxLength(200);

        // Also include fully valid content to exercise the valid() == true branch
        Arbitrary<String> validContent = Combinators.combine(
                rule2Markers(), rule3Markers()
        ).as((r2, r3) -> FDMS_DOMAIN + " " + r2 + " " + r3);

        return Arbitraries.oneOf(randomContent, validContent);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ValidationResult validate(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.ISO_8859_1);
        return validator.validate(bytes, "TEST-INV-001");
    }
}
