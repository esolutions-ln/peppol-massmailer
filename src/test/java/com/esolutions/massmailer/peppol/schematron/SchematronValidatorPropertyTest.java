package com.esolutions.massmailer.peppol.schematron;

import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link SchematronValidatorImpl}.
 *
 * <p>Property 11: Schematron Idempotency — Validates: Requirements 6.7
 */
class SchematronValidatorPropertyTest {

    private static final String PROFILE_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

    /**
     * Property 11: Schematron Idempotency
     *
     * <p>For any well-formed UBL XML input, invoking {@code validate(xml, profileId)} twice on the
     * same {@link SchematronValidatorImpl} instance must return equivalent {@link SchematronResult}
     * values — same {@code valid} flag and equivalent violations list.
     *
     * <p><b>Validates: Requirements 6.7</b>
     */
    @Property(tries = 100)
    void schematronValidationIsIdempotent(@ForAll("wellFormedUblXml") String ublXml) {
        SchematronValidatorImpl validator = new SchematronValidatorImpl();

        SchematronResult first  = validator.validate(ublXml, PROFILE_ID);
        SchematronResult second = validator.validate(ublXml, PROFILE_ID);

        assertThat(second.valid())
                .as("valid flag must be identical on second call")
                .isEqualTo(first.valid());

        assertThat(second.violations())
                .as("violations list must be equivalent on second call")
                .containsExactlyInAnyOrderElementsOf(first.violations());
    }

    /**
     * Property 11 (cross-instance variant): Two separate {@link SchematronValidatorImpl} instances
     * must return equivalent results for the same input, confirming no instance-level mutable state
     * leaks between calls.
     *
     * <p><b>Validates: Requirements 6.7</b>
     */
    @Property(tries = 50)
    void schematronValidationIsIdempotentAcrossInstances(@ForAll("wellFormedUblXml") String ublXml) {
        SchematronValidatorImpl validatorA = new SchematronValidatorImpl();
        SchematronValidatorImpl validatorB = new SchematronValidatorImpl();

        SchematronResult resultA = validatorA.validate(ublXml, PROFILE_ID);
        SchematronResult resultB = validatorB.validate(ublXml, PROFILE_ID);

        assertThat(resultB.valid())
                .as("valid flag must match across independent validator instances")
                .isEqualTo(resultA.valid());

        assertThat(resultB.violations())
                .as("violations must match across independent validator instances")
                .containsExactlyInAnyOrderElementsOf(resultA.violations());
    }

    /**
     * Property 11 (repeated calls variant): Calling validate() N times on the same instance with
     * the same input must always return the same result, confirming no accumulated side-effects.
     *
     * <p><b>Validates: Requirements 6.7</b>
     */
    @Property(tries = 30)
    void schematronValidationIsStableAcrossRepeatedCalls(
            @ForAll("wellFormedUblXml") String ublXml,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 2, max = 5) int callCount) {

        SchematronValidatorImpl validator = new SchematronValidatorImpl();

        SchematronResult baseline = validator.validate(ublXml, PROFILE_ID);

        for (int i = 1; i < callCount; i++) {
            SchematronResult repeated = validator.validate(ublXml, PROFILE_ID);

            assertThat(repeated.valid())
                    .as("valid flag must be stable on call #%d", i + 1)
                    .isEqualTo(baseline.valid());

            assertThat(repeated.violations())
                    .as("violations must be stable on call #%d", i + 1)
                    .containsExactlyInAnyOrderElementsOf(baseline.violations());
        }
    }

    // ── Generators ────────────────────────────────────────────────────────────

    /**
     * Generates minimal well-formed UBL 2.1 Invoice XML strings.
     *
     * <p>The generator produces structurally varied but always well-formed XML documents with an
     * {@code Invoice} root element, covering the range of inputs the validator may encounter.
     */
    @Provide
    Arbitrary<String> wellFormedUblXml() {
        Arbitrary<String> invoiceId = Arbitraries.strings()
                .withCharRange('A', 'Z')
                .ofMinLength(1)
                .ofMaxLength(10);

        Arbitrary<String> issueDate = Arbitraries.of(
                "2024-01-15", "2024-06-30", "2023-12-01", "2025-03-22");

        Arbitrary<String> currencyCode = Arbitraries.of("USD", "ZWL", "EUR", "GBP");

        Arbitrary<String> supplierName = Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20);

        Arbitrary<String> amount = Arbitraries.bigDecimals()
                .between(java.math.BigDecimal.ZERO, new java.math.BigDecimal("99999.99"))
                .ofScale(2)
                .map(java.math.BigDecimal::toPlainString);

        return Combinators.combine(invoiceId, issueDate, currencyCode, supplierName, amount)
                .as(SchematronValidatorPropertyTest::buildUblXml);
    }

    /** Builds a minimal but well-formed UBL 2.1 Invoice XML string. */
    private static String buildUblXml(
            String invoiceId,
            String issueDate,
            String currencyCode,
            String supplierName,
            String amount) {

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2">
                    <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0</cbc:CustomizationID>
                    <cbc:ProfileID>urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</cbc:ProfileID>
                    <cbc:ID>%s</cbc:ID>
                    <cbc:IssueDate>%s</cbc:IssueDate>
                    <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>
                    <cbc:DocumentCurrencyCode>%s</cbc:DocumentCurrencyCode>
                    <cac:AccountingSupplierParty>
                        <cac:Party>
                            <cac:PartyName>
                                <cbc:Name>%s</cbc:Name>
                            </cac:PartyName>
                        </cac:Party>
                    </cac:AccountingSupplierParty>
                    <cac:AccountingCustomerParty>
                        <cac:Party>
                            <cac:PartyName>
                                <cbc:Name>Buyer Corp</cbc:Name>
                            </cac:PartyName>
                        </cac:Party>
                    </cac:AccountingCustomerParty>
                    <cac:LegalMonetaryTotal>
                        <cbc:PayableAmount currencyID="%s">%s</cbc:PayableAmount>
                    </cac:LegalMonetaryTotal>
                </Invoice>
                """.formatted(invoiceId, issueDate, currencyCode, supplierName, currencyCode, amount);
    }
}
