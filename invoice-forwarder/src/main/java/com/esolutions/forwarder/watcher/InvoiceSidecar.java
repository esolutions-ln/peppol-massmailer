package com.esolutions.forwarder.watcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Sidecar JSON written by the ERP next to each invoice PDF.
 * Same shape as the in-process PdfFolderWatcherService sidecar so ERPs that
 * already integrate with the local watcher need no changes to switch to the forwarder.
 *
 * <p>Customer resolution on the forwarder uses (vatNumber → tinNumber) first;
 * email is still required because the upstream campaign API is keyed on email per recipient.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InvoiceSidecar(
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
        /** ZIMRA Business Partner Number — preferred buyer key. */
        String bpn,
        /** Trading-as name, distinct from recipientCompany (legal name). */
        String tradingName,
        String addressLine1,
        String addressLine2,
        String city,
        String country,
        String erpSource,
        Map<String, Object> templateVariables,
        Map<String, Object> mergeFields
) {}
