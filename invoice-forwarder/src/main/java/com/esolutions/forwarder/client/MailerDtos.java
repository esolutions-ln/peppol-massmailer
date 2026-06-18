package com.esolutions.forwarder.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side mirrors of the upstream Mass Mailer DTOs.
 * Only the fields the forwarder reads/writes — extra response fields are ignored.
 */
public final class MailerDtos {
    private MailerDtos() {}

    public record LoginRequest(String username, String password) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginResponse(String token, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomerResponse(
            UUID id,
            UUID organizationId,
            String email,
            String name,
            String companyName,
            String tradingName,
            String vatNumber,
            String tinNumber,
            String bpn,
            String addressLine1,
            String addressLine2,
            String city,
            String country,
            boolean unsubscribed
    ) {}

    public record RegisterCustomerRequest(
            String email,
            String name,
            String companyName,
            String tradingName,
            String erpSource,
            String vatNumber,
            String tinNumber,
            String bpn,
            String addressLine1,
            String addressLine2,
            String city,
            String country
    ) {}

    public record InvoiceRecipientEntry(
            String email,
            String name,
            String invoiceNumber,
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
            String pdfBase64,
            String pdfFileName,
            Map<String, Object> mergeFields
    ) {}

    public record CampaignRequest(
            String name,
            String subject,
            String templateName,
            Map<String, Object> templateVariables,
            UUID organizationId,
            String callbackUrl,
            List<InvoiceRecipientEntry> recipients
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CampaignCreatedResponse(UUID id, String name, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CampaignResponse(UUID id, String name, String status,
                                    int totalRecipients, int delivered, int failed) {}
}
