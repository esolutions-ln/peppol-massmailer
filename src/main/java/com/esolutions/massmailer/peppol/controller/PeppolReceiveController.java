package com.esolutions.massmailer.peppol.controller;

import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.InboundDocument;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import com.esolutions.massmailer.peppol.repository.InboundDocumentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PEPPOL inbound receive endpoint — Corner 3 (C3) of the 4-corner model.
 *
 * As an Access Point provider, every document received here is:
 *   1. Persisted immediately to {@code peppol_inbound_documents} (proof of receipt)
 *   2. Validated (non-empty, contains Invoice element)
 *   3. Resolved to the correct receiver organization via the eRegistry
 *   4. Queued for C4 routing (onward delivery to buyer ERP)
 *   5. Acknowledged with HTTP 200 + receipt ID
 *
 * Source and destination are tracked on every record:
 *   - senderParticipantId  = X-PEPPOL-Sender-ID header (C2)
 *   - receiverParticipantId = X-PEPPOL-Receiver-ID header (C3/C4)
 *   - receiverOrganizationId = resolved from eRegistry by participantId
 */
@RestController
@RequestMapping("/peppol")
@Tag(name = "PEPPOL Inbound (C3 Receive)")
public class PeppolReceiveController {

    private static final Logger log = LoggerFactory.getLogger(PeppolReceiveController.class);

    private final InboundDocumentRepository inboundRepo;
    private final AccessPointRepository apRepo;

    public PeppolReceiveController(InboundDocumentRepository inboundRepo,
                                    AccessPointRepository apRepo) {
        this.inboundRepo = inboundRepo;
        this.apRepo = apRepo;
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /peppol/as4/receive — C3 inbound
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Receive a PEPPOL document (C3 inbound endpoint)",
            description = """
                    Inbound endpoint for receiving PEPPOL BIS 3.0 UBL documents.
                    Every received document is persisted immediately for audit,
                    then queued for C4 routing to the buyer's ERP.

                    **Required headers:**
                    - `X-PEPPOL-Sender-ID` — sender's participant ID (C2)
                    - `X-PEPPOL-Receiver-ID` — receiver's participant ID (C3/C4)

                    **Optional headers:**
                    - `X-Invoice-Number` — invoice number for quick lookup
                    - `X-PEPPOL-Document-Type` — document type identifier
                    - `X-PEPPOL-Process` — process identifier

                    Register this URL as your AP endpoint:
                    `https://ap.invoicedirect.biz/peppol/as4/receive`
                    """
    )
    @ApiResponse(responseCode = "200", description = "Document received, persisted, and queued for C4 routing")
    @ApiResponse(responseCode = "400", description = "Invalid or malformed UBL document")
    @PostMapping(value = "/as4/receive",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody String ublXml,
            @RequestHeader(value = "X-PEPPOL-Sender-ID", required = false) String senderParticipantId,
            @RequestHeader(value = "X-PEPPOL-Receiver-ID", required = false) String receiverParticipantId,
            @RequestHeader(value = "X-PEPPOL-Document-Type", required = false) String documentType,
            @RequestHeader(value = "X-PEPPOL-Process", required = false) String processId,
            @RequestHeader(value = "X-Invoice-Number", required = false) String invoiceNumber) {

        // ── 1. Validate payload ──
        if (ublXml == null || ublXml.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty document body"));
        }
        if (!ublXml.contains("<Invoice") && !ublXml.contains(":Invoice")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not a valid UBL Invoice document"));
        }

        // ── 2. Validate sender authenticity — sender must be registered in eRegistry ──
        if (senderParticipantId == null || apRepo.findByParticipantId(senderParticipantId).isEmpty()) {
            log.warn("Inbound document rejected: unknown sender participant ID '{}'", senderParticipantId);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Unknown sender: participant ID not registered in eRegistry",
                    "senderParticipantId", senderParticipantId != null ? senderParticipantId : "null"
            ));
        }

        // ── 3. Extract invoice number from XML if not in header ──
        String resolvedInvoiceNumber = invoiceNumber != null ? invoiceNumber : extractInvoiceId(ublXml);

        // ── 4. Resolve receiver organization from eRegistry ──
        UUID receiverOrgId = null;
        if (receiverParticipantId != null) {
            receiverOrgId = apRepo.findByParticipantId(receiverParticipantId)
                    .map(AccessPoint::getOrganizationId)
                    .orElse(null);
            if (receiverOrgId == null) {
                log.warn("Inbound document for unknown participant: {} — persisting without org resolution",
                        receiverParticipantId);
            }
        }

        // ── 5. Persist immediately — proof of receipt ──
        var doc = InboundDocument.builder()
                .senderParticipantId(senderParticipantId)
                .receiverParticipantId(receiverParticipantId)
                .receiverOrganizationId(receiverOrgId)
                .invoiceNumber(resolvedInvoiceNumber)
                .documentTypeId(documentType)
                .processId(processId)
                .ublXmlPayload(ublXml)
                .payloadHash(sha256(ublXml))
                .build();

        inboundRepo.save(doc);

        log.info("PEPPOL inbound: id={} invoice={} from={} to={} org={}",
                doc.getId(), resolvedInvoiceNumber,
                senderParticipantId, receiverParticipantId, receiverOrgId);

        // ── 6. C4 routing — async onward delivery to buyer ERP ──
        // The document is now safely persisted. C4 routing happens via
        // a scheduled job (PeppolC4RoutingJob) that polls RECEIVED records
        // and forwards to the org's configured ERP webhook endpoint.
        // This decouples receipt acknowledgement from C4 delivery latency.

        return ResponseEntity.ok(Map.of(
                "status", "received",
                "receiptId", doc.getId().toString(),
                "invoiceNumber", resolvedInvoiceNumber != null ? resolvedInvoiceNumber : "unknown",
                "senderParticipantId", senderParticipantId != null ? senderParticipantId : "unknown",
                "receiverParticipantId", receiverParticipantId != null ? receiverParticipantId : "unknown",
                "receiverOrganizationId", receiverOrgId != null ? receiverOrgId.toString() : "unresolved",
                "payloadHash", doc.getPayloadHash(),
                "message", "Document persisted and queued for C4 routing"
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /peppol/as4/inbox — Query inbound documents
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Query inbound documents by organization or sender",
            description = """
                    Returns all inbound PEPPOL documents received at this AP.
                    Filter by `organizationId` (receiver) or `senderParticipantId` (source).
                    Use this to audit all traffic flowing through the AP.
                    """)
    @GetMapping(value = "/as4/inbox", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<InboundDocument>> queryInbox(
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String senderParticipantId) {

        if (organizationId != null) {
            return ResponseEntity.ok(
                    inboundRepo.findByReceiverOrganizationIdOrderByReceivedAtDesc(organizationId));
        }
        if (senderParticipantId != null) {
            return ResponseEntity.ok(
                    inboundRepo.findBySenderParticipantIdOrderByReceivedAtDesc(senderParticipantId));
        }
        return ResponseEntity.ok(inboundRepo.findAll());
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /peppol/as4/health
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "PEPPOL AP health check")
    @GetMapping(value = "/as4/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "endpoint", "/peppol/as4/receive",
                "transportProfile", "peppol-transport-as4-v2_0",
                "simplifiedHttp", "true"
        ));
    }

    // ── Helpers ──

    /** Extracts the invoice ID from UBL XML <cbc:ID> element */
    private String extractInvoiceId(String xml) {
        int start = xml.indexOf("<cbc:ID>");
        int end = xml.indexOf("</cbc:ID>");
        if (start >= 0 && end > start) {
            return xml.substring(start + 8, end).trim();
        }
        return null;
    }

    private String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return "hash-unavailable";
        }
    }
}
