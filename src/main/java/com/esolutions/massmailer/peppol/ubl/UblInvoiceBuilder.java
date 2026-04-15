package com.esolutions.massmailer.peppol.ubl;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.organization.model.Organization;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Builds PEPPOL BIS Billing 3.0 compliant UBL 2.1 Invoice XML.
 *
 * Specification: https://docs.peppol.eu/poacc/billing/3.0/
 * UBL 2.1 Invoice schema: urn:oasis:names:specification:ubl:schema:xsd:Invoice-2
 *
 * Key BIS 3.0 rules implemented:
 *   - BR-01: Invoice must have a specification identifier (CustomizationID)
 *   - BR-02: Invoice must have a profile identifier (ProfileID)
 *   - BR-04: Invoice must have an invoice number (ID)
 *   - BR-09: Seller must have a name (AccountingSupplierParty)
 *   - BR-10: Buyer must have a name (AccountingCustomerParty)
 *   - BR-CO-15: VAT breakdown required when VAT amount > 0
 *   - BR-53: Buyer VAT identifier or legal registration required
 *
 * This builder produces a simplified but spec-compliant UBL document.
 * For full production compliance, validate output against the official
 * PEPPOL Schematron rules (EN16931-CII-validation).
 */
@Component
public class UblInvoiceBuilder {

    /** PEPPOL BIS Billing 3.0 customization ID */
    public static final String CUSTOMIZATION_ID =
            "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1";

    /** PEPPOL BIS Billing 3.0 profile ID */
    public static final String PROFILE_ID =
            "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

    /** PEPPOL document type identifier */
    public static final String DOCUMENT_TYPE_ID =
            "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::" +
            "Invoice##" + CUSTOMIZATION_ID + "::2.1";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Builds a UBL 2.1 Invoice XML string from a canonical invoice and the sending org.
     * Buyer's VAT and PEPPOL participant ID are omitted (use the overload with CustomerContact
     * for full BR-53 compliance).
     */
    public String build(CanonicalInvoice invoice, Organization supplier) {
        return build(invoice, supplier, null);
    }

    /**
     * Builds a BIS 3.0 compliant UBL 2.1 Invoice XML string.
     *
     * @param invoice  the canonical invoice (ERP-agnostic)
     * @param supplier the sending organization (C1 — supplier)
     * @param buyer    the resolved customer contact; when non-null, buyer VAT ID and PEPPOL
     *                 participant ID are included (required for full BR-53 compliance)
     * @return UBL 2.1 Invoice XML as a String
     */
    public String build(CanonicalInvoice invoice, Organization supplier, CustomerContact buyer) {
        BigDecimal vatAmount = invoice.vatAmount() != null
                ? invoice.vatAmount() : BigDecimal.ZERO;
        BigDecimal totalAmount = invoice.totalAmount() != null
                ? invoice.totalAmount() : BigDecimal.ZERO;

        // Taxable (pre-VAT) amount — derive from total−VAT when subtotal not supplied
        BigDecimal taxableAmount;
        if (invoice.subtotalAmount() != null && invoice.subtotalAmount().compareTo(BigDecimal.ZERO) > 0) {
            taxableAmount = invoice.subtotalAmount();
        } else {
            taxableAmount = totalAmount.subtract(vatAmount).max(BigDecimal.ZERO);
        }

        String currency = invoice.currency() != null ? invoice.currency() : "USD";
        LocalDate issueDate = invoice.invoiceDate() != null ? invoice.invoiceDate() : LocalDate.now();
        LocalDate dueDate = invoice.dueDate() != null ? invoice.dueDate() : issueDate.plusDays(30);

        // VAT rate — derive from amounts if possible, default to 15% (Zimbabwe standard rate)
        String vatRate = "15.00";
        if (taxableAmount.compareTo(BigDecimal.ZERO) > 0 && vatAmount.compareTo(BigDecimal.ZERO) > 0) {
            vatRate = vatAmount.divide(taxableAmount, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString();
        }

        String supplierName = supplier.getCompanyName() != null
                ? supplier.getCompanyName() : supplier.getName();
        String buyerName = invoice.recipientCompany() != null
                ? invoice.recipientCompany() : invoice.recipientName();

        var xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"\n");
        xml.append("         xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\"\n");
        xml.append("         xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">\n");

        // ── BIS 3.0 mandatory header ──
        xml.append("  <cbc:CustomizationID>").append(CUSTOMIZATION_ID).append("</cbc:CustomizationID>\n");
        xml.append("  <cbc:ProfileID>").append(PROFILE_ID).append("</cbc:ProfileID>\n");
        xml.append("  <cbc:ID>").append(esc(invoice.invoiceNumber())).append("</cbc:ID>\n");
        xml.append("  <cbc:IssueDate>").append(issueDate.format(DATE_FMT)).append("</cbc:IssueDate>\n");
        xml.append("  <cbc:DueDate>").append(dueDate.format(DATE_FMT)).append("</cbc:DueDate>\n");
        xml.append("  <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>\n"); // 380 = Commercial Invoice
        xml.append("  <cbc:DocumentCurrencyCode>").append(currency).append("</cbc:DocumentCurrencyCode>\n");
        xml.append("  <cbc:TaxCurrencyCode>").append(currency).append("</cbc:TaxCurrencyCode>\n");

        // ── Fiscal note (ZIMRA verification code) ──
        if (invoice.fiscalMetadata() != null && invoice.fiscalMetadata().isPresent()) {
            xml.append("  <cbc:Note>ZIMRA Verification: ")
               .append(esc(invoice.fiscalMetadata().verificationCode()))
               .append("</cbc:Note>\n");
        }

        // ── PaymentMeans — BR-49: mandatory in BIS 3.0 ──
        // Code 30 = Credit transfer (universal; use 58 for SEPA networks)
        xml.append("  <cac:PaymentMeans>\n");
        xml.append("    <cbc:PaymentMeansCode>30</cbc:PaymentMeansCode>\n");
        xml.append("    <cbc:PaymentDueDate>").append(dueDate.format(DATE_FMT)).append("</cbc:PaymentDueDate>\n");
        xml.append("  </cac:PaymentMeans>\n");

        // ── Accounting Supplier Party (C1 — Seller) ──
        xml.append("  <cac:AccountingSupplierParty>\n");
        xml.append("    <cac:Party>\n");

        // EndpointID — electronic routing identifier (scheme EM = email for non-PEPPOL networks)
        if (supplier.getPeppolParticipantId() != null && !supplier.getPeppolParticipantId().isBlank()) {
            // Use registered PEPPOL participant ID with scheme 0190 (ZW VAT)
            xml.append("      <cbc:EndpointID schemeID=\"0190\">")
               .append(esc(supplier.getPeppolParticipantId()))
               .append("</cbc:EndpointID>\n");
        } else {
            xml.append("      <cbc:EndpointID schemeID=\"EM\">")
               .append(esc(supplier.getSenderEmail()))
               .append("</cbc:EndpointID>\n");
        }

        xml.append("      <cac:PartyName><cbc:Name>")
           .append(esc(supplierName))
           .append("</cbc:Name></cac:PartyName>\n");

        if (supplier.getCompanyAddress() != null) {
            xml.append("      <cac:PostalAddress>\n");
            xml.append("        <cbc:StreetName>").append(esc(supplier.getCompanyAddress())).append("</cbc:StreetName>\n");
            xml.append("        <cac:Country><cbc:IdentificationCode>ZW</cbc:IdentificationCode></cac:Country>\n");
            xml.append("      </cac:PostalAddress>\n");
        }

        // PartyTaxScheme — BR-31: supplier VAT identifier required when VAT is charged
        if (supplier.getVatNumber() != null && !supplier.getVatNumber().isBlank()) {
            xml.append("      <cac:PartyTaxScheme>\n");
            xml.append("        <cbc:CompanyID>").append(esc(supplier.getVatNumber())).append("</cbc:CompanyID>\n");
            xml.append("        <cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme>\n");
            xml.append("      </cac:PartyTaxScheme>\n");
        }

        // LegalEntity — BR-47: seller must have a legal name
        xml.append("      <cac:PartyLegalEntity>\n");
        xml.append("        <cbc:RegistrationName>").append(esc(supplierName)).append("</cbc:RegistrationName>\n");
        if (supplier.getVatNumber() != null && !supplier.getVatNumber().isBlank()) {
            xml.append("        <cbc:CompanyID>").append(esc(supplier.getVatNumber())).append("</cbc:CompanyID>\n");
        }
        xml.append("      </cac:PartyLegalEntity>\n");

        xml.append("      <cac:Contact>\n");
        xml.append("        <cbc:ElectronicMail>").append(esc(supplier.getSenderEmail())).append("</cbc:ElectronicMail>\n");
        xml.append("      </cac:Contact>\n");
        xml.append("    </cac:Party>\n");
        xml.append("  </cac:AccountingSupplierParty>\n");

        // ── Accounting Customer Party (C4 — Buyer) ──
        xml.append("  <cac:AccountingCustomerParty>\n");
        xml.append("    <cac:Party>\n");

        // EndpointID — use buyer's PEPPOL participant ID when known; fall back to email
        String buyerPeppolId = buyer != null ? buyer.getPeppolParticipantId() : null;
        if (buyerPeppolId != null && !buyerPeppolId.isBlank()) {
            xml.append("      <cbc:EndpointID schemeID=\"0190\">")
               .append(esc(buyerPeppolId))
               .append("</cbc:EndpointID>\n");
        } else {
            xml.append("      <cbc:EndpointID schemeID=\"EM\">")
               .append(esc(invoice.recipientEmail()))
               .append("</cbc:EndpointID>\n");
        }

        xml.append("      <cac:PartyName><cbc:Name>")
           .append(esc(buyerName))
           .append("</cbc:Name></cac:PartyName>\n");

        // PartyTaxScheme — BR-53: buyer VAT ID or legal registration required
        String buyerVat = buyer != null ? buyer.getVatNumber() : null;
        if (buyerVat == null && buyer != null) buyerVat = buyer.getTinNumber();
        if (buyerVat != null && !buyerVat.isBlank()) {
            xml.append("      <cac:PartyTaxScheme>\n");
            xml.append("        <cbc:CompanyID>").append(esc(buyerVat)).append("</cbc:CompanyID>\n");
            xml.append("        <cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme>\n");
            xml.append("      </cac:PartyTaxScheme>\n");
        }

        // LegalEntity — BR-48: buyer must have a legal name
        xml.append("      <cac:PartyLegalEntity>\n");
        xml.append("        <cbc:RegistrationName>").append(esc(buyerName)).append("</cbc:RegistrationName>\n");
        if (buyerVat != null && !buyerVat.isBlank()) {
            xml.append("        <cbc:CompanyID>").append(esc(buyerVat)).append("</cbc:CompanyID>\n");
        }
        xml.append("      </cac:PartyLegalEntity>\n");

        xml.append("      <cac:Contact>\n");
        xml.append("        <cbc:ElectronicMail>").append(esc(invoice.recipientEmail())).append("</cbc:ElectronicMail>\n");
        xml.append("      </cac:Contact>\n");
        xml.append("    </cac:Party>\n");
        xml.append("  </cac:AccountingCustomerParty>\n");

        // ── Payment Terms ──
        xml.append("  <cac:PaymentTerms>\n");
        xml.append("    <cbc:Note>Net 30</cbc:Note>\n");
        xml.append("  </cac:PaymentTerms>\n");

        // ── Tax Total (VAT) ──
        xml.append("  <cac:TaxTotal>\n");
        xml.append("    <cbc:TaxAmount currencyID=\"").append(currency).append("\">")
           .append(vatAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
           .append("</cbc:TaxAmount>\n");
        xml.append("    <cac:TaxSubtotal>\n");
        xml.append("      <cbc:TaxableAmount currencyID=\"").append(currency).append("\">")
           .append(taxableAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
           .append("</cbc:TaxableAmount>\n");
        xml.append("      <cbc:TaxAmount currencyID=\"").append(currency).append("\">")
           .append(vatAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
           .append("</cbc:TaxAmount>\n");
        xml.append("      <cac:TaxCategory>\n");
        xml.append("        <cbc:ID>S</cbc:ID>\n"); // S = Standard rate
        xml.append("        <cbc:Percent>").append(vatRate).append("</cbc:Percent>\n");
        xml.append("        <cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme>\n");
        xml.append("      </cac:TaxCategory>\n");
        xml.append("    </cac:TaxSubtotal>\n");
        xml.append("  </cac:TaxTotal>\n");

        // ── Legal Monetary Total ──
        xml.append("  <cac:LegalMonetaryTotal>\n");
        xml.append("    <cbc:LineExtensionAmount currencyID=\"").append(currency).append("\">")
           .append(taxableAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
           .append("</cbc:LineExtensionAmount>\n");
        xml.append("    <cbc:TaxExclusiveAmount currencyID=\"").append(currency).append("\">")
           .append(taxableAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
           .append("</cbc:TaxExclusiveAmount>\n");
        xml.append("    <cbc:TaxInclusiveAmount currencyID=\"").append(currency).append("\">")
           .append(totalAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
           .append("</cbc:TaxInclusiveAmount>\n");
        xml.append("    <cbc:PayableAmount currencyID=\"").append(currency).append("\">")
           .append(totalAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
           .append("</cbc:PayableAmount>\n");
        xml.append("  </cac:LegalMonetaryTotal>\n");

        // ── Invoice Line (single summary line — expand per line item if needed) ──
        xml.append("  <cac:InvoiceLine>\n");
        xml.append("    <cbc:ID>1</cbc:ID>\n");
        xml.append("    <cbc:InvoicedQuantity unitCode=\"EA\">1</cbc:InvoicedQuantity>\n");
        xml.append("    <cbc:LineExtensionAmount currencyID=\"").append(currency).append("\">")
           .append(taxableAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
           .append("</cbc:LineExtensionAmount>\n");
        xml.append("    <cac:TaxTotal>\n");
        xml.append("      <cbc:TaxAmount currencyID=\"").append(currency).append("\">")
         .append(vatAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
         .append("</cbc:TaxAmount>\n");
        xml.append("    </cac:TaxTotal>\n");
        xml.append("    <cac:Item>\n");
        xml.append("      <cbc:Description>Invoice ").append(esc(invoice.invoiceNumber())).append("</cbc:Description>\n");
        xml.append("      <cbc:Name>Invoice ").append(esc(invoice.invoiceNumber())).append("</cbc:Name>\n");
        xml.append("      <cac:ClassifiedTaxCategory>\n");
        xml.append("        <cbc:ID>S</cbc:ID>\n");
        xml.append("        <cbc:Percent>").append(vatRate).append("</cbc:Percent>\n");
        xml.append("        <cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme>\n");
        xml.append("      </cac:ClassifiedTaxCategory>\n");
        xml.append("    </cac:Item>\n");
        xml.append("    <cac:Price>\n");
        xml.append("      <cbc:PriceAmount currencyID=\"").append(currency).append("\">")
           .append(taxableAmount.setScale(2, RoundingMode.HALF_UP).toPlainString())
           .append("</cbc:PriceAmount>\n");
        xml.append("    </cac:Price>\n");
        xml.append("  </cac:InvoiceLine>\n");

        xml.append("</Invoice>\n");
        return xml.toString();
    }

    /** XML-escapes a string value */
    private String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }
}
