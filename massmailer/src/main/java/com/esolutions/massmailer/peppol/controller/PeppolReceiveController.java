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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PEPPOL inbound receive endpoint — Corner 3 (C3) of the 4-corner model.
 *
 * <p>As an Access Point, every accepted document is:
 * <ol>
 *   <li>Authenticated via HMAC-SHA256 over the request body using the sender AP's shared secret.</li>
 *   <li>Validated for shape (non-empty, UBL Invoice/CreditNote root).</li>
 *   <li>Deduplicated by SHA-256 payload hash — replays return the original receipt.</li>
 *   <li>Resolved to the receiver organization via the eRegistry.</li>
 *   <li>Persisted to {@code peppol_inbound_documents} and queued for C4 routing.</li>
 * </ol>
 *
 * <p>Sender identity is <b>never</b> taken on faith from the {@code X-PEPPOL-Sender-ID}
 * header alone — the claim must be backed by a valid HMAC signature using the
 * pre-shared secret registered for that AP in the eRegistry. When the sender AP
 * has no shared secret on file, inbound traffic from that AP is rejected
 * (fail-closed) rather than silently accepted.
 */
@RestController
@RequestMapping("/peppol")
@Tag(name = "PEPPOL Inbound (C3 Receive)")
public class PeppolReceiveController {

    private static final Logger log = LoggerFactory.getLogger(PeppolReceiveController.class);

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String HEADER_SIGNATURE = "X-PEPPOL-Signature";
    private static final String UBL_CBC_NS =
            "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    private static final int MAX_INBOX_PAGE_SIZE = 200;
    private static final int DEFAULT_INBOX_PAGE_SIZE = 50;

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
                    Inbound endpoint for PEPPOL BIS 3.0 UBL documents.

                    **Required headers:**
                    - `X-PEPPOL-Sender-ID` — sender AP participant ID (must be registered with a shared secret)
                    - `X-PEPPOL-Receiver-ID` — receiver participant ID
                    - `X-PEPPOL-Signature` — Base64(HmacSHA256(senderSecret, requestBody))

                    **Optional headers:**
                    - `X-Invoice-Number`, `X-PEPPOL-Document-Type`, `X-PEPPOL-Process`

                    Duplicates (same payload hash) return the original receipt with `status=duplicate`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Document received and queued (or duplicate of a prior receipt)")
    @ApiResponse(responseCode = "400", description = "Invalid or malformed UBL document")
    @ApiResponse(responseCode = "401", description = "Missing or invalid HMAC signature")
    @ApiResponse(responseCode = "403", description = "Sender AP is unknown or has no shared secret on file")
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
            @RequestHeader(value = "X-Invoice-Number", required = false) String invoiceNumber,
            @RequestHeader(value = HEADER_SIGNATURE, required = false) String signature) {

        // 1. Shape validation
        if (ublXml == null || ublXml.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty document body"));
        }
        if (!ublXml.contains("<Invoice") && !ublXml.contains(":Invoice")
                && !ublXml.contains("<CreditNote") && !ublXml.contains(":CreditNote")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not a valid UBL Invoice or CreditNote document"));
        }

        // 2. Sender lookup
        if (senderParticipantId == null || senderParticipantId.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing X-PEPPOL-Sender-ID header"));
        }
        Optional<AccessPoint> senderAp = apRepo.findByParticipantId(senderParticipantId);
        if (senderAp.isEmpty() || !senderAp.get().isActive()) {
            log.warn("Inbound rejected: unknown or inactive sender AP '{}'", senderParticipantId);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Sender AP not registered or not active",
                    "senderParticipantId", senderParticipantId));
        }

        // 3. HMAC verification — fail-closed when no secret is configured for this AP
        String secret = senderAp.get().getInboundSharedSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Inbound rejected: sender AP '{}' has no inboundSharedSecret configured", senderParticipantId);
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Sender AP has no inbound shared secret configured — contact the receiver to register one"));
        }
        if (signature == null || signature.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing " + HEADER_SIGNATURE + " header"));
        }
        if (!verifySignature(secret, ublXml, signature)) {
            log.warn("Inbound rejected: HMAC signature mismatch for sender '{}'", senderParticipantId);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid HMAC signature"));
        }

        // 4. Dedup by payload hash
        String payloadHash = sha256(ublXml);
        Optional<InboundDocument> existing = inboundRepo.findByPayloadHash(payloadHash);
        if (existing.isPresent()) {
            InboundDocument prior = existing.get();
            log.info("Duplicate inbound payload detected: hash={} returning prior receipt {}",
                    payloadHash, prior.getId());
            return ResponseEntity.ok(Map.of(
                    "status", "duplicate",
                    "receiptId", prior.getId().toString(),
                    "invoiceNumber", prior.getInvoiceNumber() != null ? prior.getInvoiceNumber() : "unknown",
                    "payloadHash", payloadHash,
                    "message", "Identical payload was already received — returning the prior receipt"));
        }

        // 5. Resolve receiver organization
        String resolvedInvoiceNumber = invoiceNumber != null ? invoiceNumber : extractInvoiceId(ublXml);
        UUID receiverOrgId = null;
        if (receiverParticipantId != null) {
            receiverOrgId = apRepo.findByParticipantId(receiverParticipantId)
                    .map(AccessPoint::getOrganizationId)
                    .orElse(null);
            if (receiverOrgId == null) {
                log.warn("Inbound document for unknown receiver participant: {}", receiverParticipantId);
            }
        }

        // 6. Persist — proof of receipt
        InboundDocument doc = InboundDocument.builder()
                .senderParticipantId(senderParticipantId)
                .receiverParticipantId(receiverParticipantId)
                .receiverOrganizationId(receiverOrgId)
                .invoiceNumber(resolvedInvoiceNumber)
                .documentTypeId(documentType)
                .processId(processId)
                .ublXmlPayload(ublXml)
                .payloadHash(payloadHash)
                .build();
        inboundRepo.save(doc);

        log.info("PEPPOL inbound: id={} invoice={} from={} to={} org={}",
                doc.getId(), resolvedInvoiceNumber,
                senderParticipantId, receiverParticipantId, receiverOrgId);

        return ResponseEntity.ok(Map.of(
                "status", "received",
                "receiptId", doc.getId().toString(),
                "invoiceNumber", resolvedInvoiceNumber != null ? resolvedInvoiceNumber : "unknown",
                "senderParticipantId", senderParticipantId,
                "receiverParticipantId", receiverParticipantId != null ? receiverParticipantId : "unknown",
                "receiverOrganizationId", receiverOrgId != null ? receiverOrgId.toString() : "unresolved",
                "payloadHash", payloadHash,
                "message", "Document persisted and queued for C4 routing"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /peppol/as4/inbox — Admin-only — paginated
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Query inbound documents (admin only, paginated)",
            description = "Returns inbound PEPPOL documents. Admin role required. " +
                    "Page size is capped at " + MAX_INBOX_PAGE_SIZE + ".")
    @GetMapping(value = "/as4/inbox", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<InboundDocument>> queryInbox(
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) String senderParticipantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_INBOX_PAGE_SIZE) int size) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_INBOX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "receivedAt"));

        if (organizationId != null) {
            return ResponseEntity.ok(
                    inboundRepo.findByReceiverOrganizationIdOrderByReceivedAtDesc(organizationId, pageable));
        }
        if (senderParticipantId != null) {
            return ResponseEntity.ok(
                    inboundRepo.findBySenderParticipantIdOrderByReceivedAtDesc(senderParticipantId, pageable));
        }
        return ResponseEntity.ok(inboundRepo.findAllByOrderByReceivedAtDesc(pageable));
    }

    @Operation(summary = "PEPPOL AP health check")
    @GetMapping(value = "/as4/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "endpoint", "/peppol/as4/receive",
                "transportProfile", "peppol-transport-as4-v2_0"));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Constant-time HMAC verification — protects against timing side channels.
     */
    private boolean verifySignature(String secret, String body, String presentedSignature) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] presented;
            try {
                presented = Base64.getDecoder().decode(presentedSignature.trim());
            } catch (IllegalArgumentException ex) {
                return false;
            }
            return MessageDigest.isEqual(expected, presented);
        } catch (Exception e) {
            log.error("HMAC verification failed unexpectedly: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Namespace-aware extraction of the top-level UBL invoice/credit-note ID
     * (the first {@code cbc:ID} child of the root, never a line-item ID).
     */
    private String extractInvoiceId(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            // First-level child of the document root that is a cbc:ID
            NodeList kids = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                var node = kids.item(i);
                if ("ID".equals(node.getLocalName()) && UBL_CBC_NS.equals(node.getNamespaceURI())) {
                    String text = node.getTextContent();
                    return text == null ? null : text.trim();
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Could not extract cbc:ID from inbound UBL: {}", e.getMessage());
            return null;
        }
    }

    private String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE — this branch is unreachable
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
