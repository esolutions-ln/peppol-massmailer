package com.esolutions.massmailer.peppol.ubl;

import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.model.Organization;
import net.jqwik.api.*;
import net.jqwik.api.Combinators;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link UblInvoiceBuilder}.
 *
 * <p>Property 9: UBL Monetary Total Invariant — Validates: Requirements 5.3
 * <p>Property 10: UBL Round-Trip — Validates: Requirements 5.7
 */
class UblInvoiceBuilderPropertyTest {

    private final UblInvoiceBuilder builder = new UblInvoiceBuilder();

    /**
     * Property 9: UBL Monetary Total Invariant
     *
     * <p>For any non-negative {@code (subtotalAmount, vatAmount)} pair with scale 2,
     * the UBL XML produced by {@link UblInvoiceBuilder#build} must contain a
     * {@code PayableAmount} element whose value equals {@code subtotalAmount + vatAmount}.
     *
     * <p><b>Validates: Requirements 5.3</b>
     */
    @Property(tries = 200)
    void payableAmountEqualsSubtotalPlusVat(
            @ForAll("nonNegativeAmount") BigDecimal subtotalAmount,
            @ForAll("nonNegativeAmount") BigDecimal vatAmount) throws Exception {

        BigDecimal expectedPayable = subtotalAmount.add(vatAmount).setScale(2, RoundingMode.HALF_UP);

        CanonicalInvoice invoice = buildMinimalInvoice(subtotalAmount, vatAmount, expectedPayable);
        Organization supplier = buildMinimalSupplier();

        String ublXml = builder.build(invoice, supplier);

        BigDecimal actualPayable = extractPayableAmount(ublXml);

        assertThat(actualPayable)
                .as("PayableAmount in UBL XML must equal subtotalAmount + vatAmount "
                        + "(subtotal=%s, vat=%s, expected=%s, actual=%s)",
                        subtotalAmount, vatAmount, expectedPayable, actualPayable)
                .isEqualByComparingTo(expectedPayable);
    }

    // ── Property 10: UBL Round-Trip ───────────────────────────────────────────

    /**
     * Property 10: UBL Round-Trip
     *
     * <p>For any valid {@link CanonicalInvoice}, building UBL XML, parsing key elements
     * back into a canonical form, and building again must produce an equivalent UBL
     * document — same PayableAmount, InvoiceID, IssueDate, DueDate, TaxAmount, and
     * currency. This verifies the builder is deterministic and idempotent.
     *
     * <p><b>Validates: Requirements 5.7</b>
     */
    @Property(tries = 200)
    void ublRoundTripProducesEquivalentDocument(
            @ForAll("variedInvoice") CanonicalInvoice invoice) throws Exception {

        Organization supplier = buildMinimalSupplier();

        // Step 1: Build xml1 from the original invoice
        String xml1 = builder.build(invoice, supplier);

        // Step 2: Parse key elements from xml1
        Document doc1 = parseXml(xml1);
        String id1          = extractCbc(doc1, "ID");
        String issueDate1   = extractCbc(doc1, "IssueDate");
        String dueDate1     = extractCbc(doc1, "DueDate");
        String currency1    = extractCbc(doc1, "DocumentCurrencyCode");
        BigDecimal payable1 = new BigDecimal(extractFirstCbc(doc1, "PayableAmount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmt1  = new BigDecimal(extractFirstCbc(doc1, "TaxAmount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxable1 = new BigDecimal(extractFirstCbc(doc1, "TaxableAmount")).setScale(2, RoundingMode.HALF_UP);

        // Step 3: Reconstruct a CanonicalInvoice from the parsed values
        BigDecimal total = payable1;
        BigDecimal subtotal = taxable1;
        BigDecimal vat = taxAmt1;

        CanonicalInvoice parsedInvoice = new CanonicalInvoice(
                invoice.erpSource(),
                invoice.erpTenantId(),
                invoice.erpInvoiceId(),
                invoice.recipientEmail(),
                invoice.recipientName(),
                invoice.recipientCompany(),
                id1,
                LocalDate.parse(issueDate1),
                LocalDate.parse(dueDate1),
                subtotal,
                vat,
                total,
                currency1,
                invoice.fiscalMetadata(),
                null,
                Map.of()
        );

        // Step 4: Build xml2 from the reconstructed invoice
        String xml2 = builder.build(parsedInvoice, supplier);

        // Step 5: Parse key elements from xml2 and assert equivalence
        Document doc2 = parseXml(xml2);
        String id2          = extractCbc(doc2, "ID");
        String issueDate2   = extractCbc(doc2, "IssueDate");
        String dueDate2     = extractCbc(doc2, "DueDate");
        String currency2    = extractCbc(doc2, "DocumentCurrencyCode");
        BigDecimal payable2 = new BigDecimal(extractFirstCbc(doc2, "PayableAmount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxAmt2  = new BigDecimal(extractFirstCbc(doc2, "TaxAmount")).setScale(2, RoundingMode.HALF_UP);

        assertThat(id2).as("InvoiceID must survive round-trip").isEqualTo(id1);
        assertThat(issueDate2).as("IssueDate must survive round-trip").isEqualTo(issueDate1);
        assertThat(dueDate2).as("DueDate must survive round-trip").isEqualTo(dueDate1);
        assertThat(currency2).as("DocumentCurrencyCode must survive round-trip").isEqualTo(currency1);
        assertThat(payable2).as("PayableAmount must survive round-trip").isEqualByComparingTo(payable1);
        assertThat(taxAmt2).as("TaxAmount must survive round-trip").isEqualByComparingTo(taxAmt1);
    }

    // ── Generators ────────────────────────────────────────────────────────────

    /**
     * Generates non-negative {@link BigDecimal} values with scale 2,
     * in the range [0.00, 999999.99].
     */
    @Provide
    Arbitrary<BigDecimal> nonNegativeAmount() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("999999.99"))
                .ofScale(2);
    }

    /**
     * Generates varied {@link CanonicalInvoice} objects covering:
     * <ul>
     *   <li>Different subtotal/VAT amounts (including zero VAT)</li>
     *   <li>Different currencies</li>
     *   <li>Optional fiscal metadata (present or absent)</li>
     *   <li>Various invoice dates</li>
     * </ul>
     */
    @Provide
    Arbitrary<CanonicalInvoice> variedInvoice() {
        Arbitrary<BigDecimal> subtotal = Arbitraries.bigDecimals()
                .between(new BigDecimal("1.00"), new BigDecimal("999999.99"))
                .ofScale(2);

        // VAT rate: 0%, 5%, 10%, 14.5%, 15%, 20%
        Arbitrary<BigDecimal> vatRate = Arbitraries.of(
                BigDecimal.ZERO,
                new BigDecimal("0.05"),
                new BigDecimal("0.10"),
                new BigDecimal("0.145"),
                new BigDecimal("0.15"),
                new BigDecimal("0.20")
        );

        Arbitrary<String> currency = Arbitraries.of("USD", "ZWL", "EUR", "GBP");

        Arbitrary<LocalDate> issueDate = Arbitraries.of(
                LocalDate.of(2023, 1, 1),
                LocalDate.of(2024, 6, 15),
                LocalDate.of(2025, 12, 31)
        );

        // Optional fiscal metadata
        Arbitrary<CanonicalInvoice.FiscalMetadata> fiscalMeta = Arbitraries.of(
                CanonicalInvoice.FiscalMetadata.EMPTY,
                new CanonicalInvoice.FiscalMetadata("DEV-001", "42", "1000", "ZIMRA-VERIFY-XYZ", null)
        );

        Arbitrary<String> invoiceNumber = Arbitraries.strings()
                .withCharRange('A', 'Z')
                .ofMinLength(3).ofMaxLength(8)
                .map(s -> "INV-" + s);

        return Combinators.combine(subtotal, vatRate, currency, issueDate, fiscalMeta, invoiceNumber)
                .as((sub, rate, cur, date, fiscal, invNum) -> {
                    BigDecimal vat = sub.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal total = sub.add(vat).setScale(2, RoundingMode.HALF_UP);
                    return new CanonicalInvoice(
                            CanonicalInvoice.ErpSource.GENERIC_API,
                            "test-tenant",
                            "ERP-" + invNum,
                            "buyer@example.com",
                            "Buyer Name",
                            "Buyer Corp",
                            invNum,
                            date,
                            date.plusDays(30),
                            sub,
                            vat,
                            total,
                            cur,
                            fiscal,
                            null,
                            Map.of()
                    );
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CanonicalInvoice buildMinimalInvoice(
            BigDecimal subtotalAmount, BigDecimal vatAmount, BigDecimal totalAmount) {
        return new CanonicalInvoice(
                CanonicalInvoice.ErpSource.GENERIC_API,
                "test-tenant",
                "ERP-001",
                "buyer@example.com",
                "Buyer Name",
                "Buyer Corp",
                "INV-TEST-001",
                LocalDate.of(2024, 1, 15),
                LocalDate.of(2024, 2, 14),
                subtotalAmount,
                vatAmount,
                totalAmount,
                "USD",
                CanonicalInvoice.FiscalMetadata.EMPTY,
                null,
                Map.of()
        );
    }

    private Organization buildMinimalSupplier() {
        return Organization.builder()
                .name("Test Supplier")
                .slug("test-supplier")
                .apiKey("testapikey00000000000000000000001")
                .senderEmail("noreply@testsupplier.com")
                .senderDisplayName("Test Supplier Accounts")
                .deliveryMode(DeliveryMode.EMAIL)
                .build();
    }

    /** Parses a UBL XML string into a DOM {@link Document}. */
    private Document parseXml(String ublXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder docBuilder = factory.newDocumentBuilder();
        return docBuilder.parse(new ByteArrayInputStream(ublXml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Extracts the text content of the first {@code cbc:{localName}} element
     * in the UBL namespace.
     */
    private String extractCbc(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS(
                "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2",
                localName);
        assertThat(nodes.getLength())
                .as("UBL XML must contain at least one <%s> element", localName)
                .isGreaterThanOrEqualTo(1);
        return nodes.item(0).getTextContent().trim();
    }

    /**
     * Extracts the text content of the first {@code cbc:{localName}} element —
     * alias for {@link #extractCbc} used when the element may appear multiple times
     * and we only need the first occurrence.
     */
    private String extractFirstCbc(Document doc, String localName) {
        return extractCbc(doc, localName);
    }

    /**
     * Parses the UBL XML string and extracts the {@code PayableAmount} value
     * from {@code /Invoice/LegalMonetaryTotal/PayableAmount}.
     */
    private BigDecimal extractPayableAmount(String ublXml) throws Exception {
        Document doc = parseXml(ublXml);
        NodeList nodes = doc.getElementsByTagNameNS(
                "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2",
                "PayableAmount");

        assertThat(nodes.getLength())
                .as("UBL XML must contain exactly one PayableAmount element")
                .isGreaterThanOrEqualTo(1);

        String rawValue = nodes.item(0).getTextContent().trim();
        return new BigDecimal(rawValue).setScale(2, RoundingMode.HALF_UP);
    }
}
