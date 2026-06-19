package com.esolutions.watcher.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SidecarData(
        UUID organizationId,
        String campaignName,
        String subject,
        String templateName,
        String invoiceNumber,
        String recipientEmail,
        String recipientName,
        String recipientCompany,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal totalAmount,
        BigDecimal vatAmount,
        String currency,
        String fiscalDeviceSerialNumber,
        String fiscalDayNumber,
        String globalInvoiceCounter,
        String verificationCode,
        String qrCodeUrl,
        String vatNumber,
        String tinNumber,
        String bpn,
        String tradingName,
        String addressLine1,
        String addressLine2,
        String city,
        String country,
        String erpSource,
        Map<String, Object> templateVariables,
        Map<String, Object> mergeFields
) {
    public String effectiveCampaignName() {
        return campaignName != null ? campaignName : "Watcher auto-dispatch";
    }

    public String effectiveSubject() {
        return subject != null ? subject : "Your Invoice";
    }

    public String effectiveTemplateName() {
        return templateName != null ? templateName : "invoice";
    }

    public String effectiveInvoiceNumber(Path pdfPath) {
        if (invoiceNumber != null && !invoiceNumber.isBlank()) return invoiceNumber;
        if (pdfPath != null) {
            String name = pdfPath.getFileName().toString();
            return name.endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
        }
        return "unknown";
    }

    public boolean hasTaxId() {
        return (bpn != null && !bpn.isBlank())
                || (vatNumber != null && !vatNumber.isBlank())
                || (tinNumber != null && !tinNumber.isBlank());
    }
}
