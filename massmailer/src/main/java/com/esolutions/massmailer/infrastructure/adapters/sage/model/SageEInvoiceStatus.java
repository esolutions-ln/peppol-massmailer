package com.esolutions.massmailer.infrastructure.adapters.sage.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Maps the Sage Network e-Invoice status API response.
 *
 * API: GET /v1/e-invoices/{id}
 * Response shape:
 * {
 *   "data": {
 *     "type": "e-Invoice",
 *     "id": "497f6eca-...",
 *     "attributes": {
 *       "eInvoiceTypeCode": "AP_Invoice",
 *       "attachments": [...],
 *       "sender": { "accessPointAddressType": "fr.siren", "accessPointAddress": "..." },
 *       "recipient": { "accessPointAddressType": "fr.siren", "accessPointAddress": "..." },
 *       "accessPointId": "702dc7ac-...",
 *       "erpInvoiceId": "string",
 *       "currentStatus": { "statusCode": "...", "status": "New", ... },
 *       "history": [...]
 *     }
 *   }
 * }
 *
 * Sage e-Invoice status codes (statusCode UUIDs map to these names):
 *   New          — document received by Sage Network, not yet processed
 *   Sending      — being transmitted to recipient AP
 *   Sent         — delivered to recipient AP (C3)
 *   Acknowledged — recipient AP confirmed receipt (MDN)
 *   Rejected     — recipient AP or buyer rejected the document
 *   Failed       — transmission failed
 *   Cancelled    — sender cancelled before delivery
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SageEInvoiceStatus(
        @JsonProperty("data") Data data
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("type") String type,
            @JsonProperty("id") String id,
            @JsonProperty("attributes") Attributes attributes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attributes(
            @JsonProperty("eInvoiceTypeCode") String eInvoiceTypeCode,
            @JsonProperty("attachments") List<Attachment> attachments,
            @JsonProperty("sender") Participant sender,
            @JsonProperty("recipient") Participant recipient,
            @JsonProperty("accessPointId") String accessPointId,
            @JsonProperty("erpInvoiceId") String erpInvoiceId,
            @JsonProperty("currentStatus") StatusEntry currentStatus,
            @JsonProperty("history") List<HistoryEntry> history
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attachment(
            @JsonProperty("filename") String filename,
            @JsonProperty("mimetype") String mimetype,
            @JsonProperty("downloadUrl") String downloadUrl
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Participant(
            @JsonProperty("accessPointAddressType") String accessPointAddressType,
            @JsonProperty("accessPointAddress") String accessPointAddress
    ) {
        /** Returns PEPPOL participant ID in standard format: {scheme}:{address} */
        public String toParticipantId() {
            if (accessPointAddressType == null || accessPointAddress == null) return null;
            // Map Sage scheme names to PEPPOL scheme codes
            String scheme = switch (accessPointAddressType) {
                case "fr.siren"  -> "0002";
                case "be.cbe"    -> "0208";
                case "de.handelsregister" -> "0204";
                case "gb.vat"    -> "9932";
                case "zw.vat"    -> "0190";  // Zimbabwe VAT
                default          -> accessPointAddressType;
            };
            return scheme + ":" + accessPointAddress;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusEntry(
            @JsonProperty("statusId") String statusId,
            @JsonProperty("statusCode") String statusCode,
            @JsonProperty("status") String status,
            @JsonProperty("notes") String notes,
            @JsonProperty("rejectionReason") String rejectionReason
    ) {
        /**
         * Maps Sage status string to our PeppolDeliveryRecord.DeliveryStatus.
         * Sage statuses: New, Sending, Sent, Acknowledged, Rejected, Failed, Cancelled
         */
        public com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus toDeliveryStatus() {
            if (status == null) return com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus.PENDING;
            return switch (status) {
                case "New"          -> com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus.PENDING;
                case "Sending"      -> com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus.TRANSMITTING;
                case "Sent",
                     "Acknowledged" -> com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus.DELIVERED;
                case "Rejected",
                     "Failed",
                     "Cancelled"    -> com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus.FAILED;
                default             -> com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus.PENDING;
            };
        }

        public boolean isTerminal() {
            return status != null && switch (status) {
                case "Sent", "Acknowledged", "Rejected", "Failed", "Cancelled" -> true;
                default -> false;
            };
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryEntry(
            @JsonProperty("statusId") String statusId,
            @JsonProperty("statusCode") String statusCode,
            @JsonProperty("status") String status,
            @JsonProperty("operationStatus") String operationStatus,
            @JsonProperty("errorCode") String errorCode,
            @JsonProperty("notes") String notes,
            @JsonProperty("rejectionReason") String rejectionReason,
            @JsonProperty("meta") Meta meta
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("createdSource") String createdSource
    ) {}

    // ── Convenience accessors ──

    public String eInvoiceId() {
        return data != null ? data.id() : null;
    }

    public String erpInvoiceId() {
        return data != null && data.attributes() != null ? data.attributes().erpInvoiceId() : null;
    }

    public StatusEntry currentStatus() {
        return data != null && data.attributes() != null ? data.attributes().currentStatus() : null;
    }

    public String senderParticipantId() {
        if (data == null || data.attributes() == null || data.attributes().sender() == null) return null;
        return data.attributes().sender().toParticipantId();
    }

    public String recipientParticipantId() {
        if (data == null || data.attributes() == null || data.attributes().recipient() == null) return null;
        return data.attributes().recipient().toParticipantId();
    }
}
