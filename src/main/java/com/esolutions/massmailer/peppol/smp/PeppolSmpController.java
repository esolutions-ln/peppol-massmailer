package com.esolutions.massmailer.peppol.smp;

import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.Optional;

/**
 * PEPPOL SMP (Service Metadata Publisher) endpoint — serves BDXR SMP v2 XML
 * so that other Access Points on the PEPPOL network can discover our registered
 * participants' endpoint URLs and AS4 certificates.
 *
 * <p>This implements the C3 side of the PEPPOL 4-corner model: when another AP
 * wants to send us a document, it first queries this SMP to find our endpoint
 * and certificate for the recipient participant ID.
 *
 * <p>Format: BDXR SMP v2 ({@code http://docs.oasis-open.org/bdxr/ns/SMP/2016/05})
 */
@RestController
@Tag(name = "PEPPOL SMP (Service Metadata Publisher)")
public class PeppolSmpController {

    private static final Logger log = LoggerFactory.getLogger(PeppolSmpController.class);

    private static final String NS_SMP = "http://docs.oasis-open.org/bdxr/ns/SMP/2016/05";
    private static final String TRANSPORT_PROFILE_AS4 = "peppol-transport-as4-v2_0";

    private final AccessPointRepository apRepo;

    public PeppolSmpController(AccessPointRepository apRepo) {
        this.apRepo = apRepo;
    }

    @Operation(summary = "Query SMP service metadata for a participant",
            description = "Returns BDXR SMP v2 XML for the given participant ID and document type. " +
                    "Used by other PEPPOL Access Points to discover our endpoint and certificate.")
    @GetMapping(value = "/bdxr/smp/{participantId}/services/{documentTypeId}",
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> lookupServiceMetadata(
            @PathVariable String participantId,
            @PathVariable String documentTypeId) {

        String decodedId = participantId.replace("::", ":");
        log.debug("SMP query: participant={} doctype={}", decodedId, documentTypeId);

        Optional<AccessPoint> ap = apRepo.findByParticipantId(decodedId);
        if (ap.isEmpty() || !ap.get().isActive()) {
            log.info("SMP lookup failed: participant {} not found or not active", decodedId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(errorXml("Participant " + decodedId + " not registered or not active"));
        }

        AccessPoint accessPoint = ap.get();
        String xml = buildSmpResponse(decodedId, documentTypeId, accessPoint);
        return ResponseEntity.ok(xml);
    }

    /**
     * Redirect-less endpoint format: /bdxr/smp/{scheme}/{value}/services/{doctype}
     * PEPPOL SMP also supports: /bdxr/smp/{scheme}::{value}/services/{doctype}
     */
    @Operation(hidden = true)
    @GetMapping(value = "/bdxr/smp/{scheme}/{value}/services/{documentTypeId}",
            produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> lookupServiceMetadataSplit(
            @PathVariable String scheme,
            @PathVariable String value,
            @PathVariable String documentTypeId) {
        return lookupServiceMetadata(scheme + "::" + value, documentTypeId);
    }

    private String buildSmpResponse(String participantId, String documentTypeId,
                                     AccessPoint ap) {
        String certBase64 = "";
        if (ap.getCertificate() != null && !ap.getCertificate().isBlank()) {
            certBase64 = Base64.getEncoder().encodeToString(
                    ap.getCertificate().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ns:ServiceMetadata xmlns:ns="%s">
                  <ns:ServiceInformation>
                    <ns:ParticipantIdentifier scheme="iso6523-actorid-upis">%s</ns:ParticipantIdentifier>
                    <ns:DocumentIdentifier scheme="cenbii-procid-ubl">%s</ns:DocumentIdentifier>
                    <ns:ProcessList>
                      <ns:Process>
                        <ns:ProcessIdentifier scheme="cenbii-procid-ubl">urn:fdc:peppol.eu:2017:poacc:billing:01:1.0</ns:ProcessIdentifier>
                        <ns:ServiceEndpointList>
                          <ns:Endpoint transportProfile="%s">
                            <ns:EndpointURI>%s</ns:EndpointURI>
                            <ns:Certificate>%s</ns:Certificate>
                            <ns:ServiceDescription>%s</ns:ServiceDescription>
                          </ns:Endpoint>
                        </ns:ServiceEndpointList>
                      </ns:Process>
                    </ns:ProcessList>
                  </ns:ServiceInformation>
                </ns:ServiceMetadata>
                """.formatted(
                        NS_SMP,
                        escapeXml(participantId),
                        escapeXml(documentTypeId),
                        TRANSPORT_PROFILE_AS4,
                        escapeXml(ap.getEndpointUrl()),
                        escapeXml(certBase64),
                        escapeXml(ap.getParticipantName() != null ? ap.getParticipantName() : "PEPPOL Access Point")
                );
    }

    private String errorXml(String message) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ns:ServiceMetadata xmlns:ns="%s">
                  <ns:Error>%s</ns:Error>
                </ns:ServiceMetadata>
                """.formatted(NS_SMP, escapeXml(message));
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
