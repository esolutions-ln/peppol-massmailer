package com.esolutions.massmailer.peppol.smp;

import com.esolutions.massmailer.peppol.config.PeppolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PeppolSmpClient {

    private static final Logger log = LoggerFactory.getLogger(PeppolSmpClient.class);

    private static final String NS_SMP = "http://docs.oasis-open.org/bdxr/ns/SMP/2016/05";
    private static final String NS_SMP_LEGACY = "http://docs.oasis-open.org/bdxr/ns/SMP/2013/09";

    private final RestTemplate restTemplate;
    private final PeppolProperties properties;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PeppolSmpClient(RestTemplate restTemplate, PeppolProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public Optional<SmpServiceMetadata> lookupServiceMetadata(
            String participantId, String documentTypeId) {
        String key = participantId + "::" + documentTypeId;
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }
        Optional<SmpServiceMetadata> result = doLookup(participantId, documentTypeId);
        cache.put(key, new CacheEntry(result, properties.getSmp().getCacheTtlSeconds()));
        return result;
    }

    private Optional<SmpServiceMetadata> doLookup(String participantId, String documentTypeId) {
        String smpUrl = resolveSmpUrl(participantId);
        String url = smpUrl + "/bdxr/smp/"
                + urlEncodeParticipantId(participantId)
                + "/services/" + documentTypeId;

        log.debug("Querying SMP: {}", url);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_XML, MediaType.TEXT_XML));
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("SMP returned non-2xx for {}: {}", participantId, response.getStatusCode());
                return Optional.empty();
            }

            return Optional.of(parseSmpResponse(participantId, documentTypeId, response.getBody()));

        } catch (RestClientException e) {
            log.warn("SMP query failed for {}: {}", participantId, e.getMessage());
            return Optional.empty();
        }
    }

    String resolveSmpUrl(String participantId) {
        // Try SML DNS-based resolution first, fall back to configured base URL
        try {
            String smlHost = buildSmlHost(participantId);
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(smlHost);
            if (addresses.length > 0) {
                String ip = addresses[0].getHostAddress();
                log.debug("SML resolved {} → {} ({})", participantId, smlHost, ip);
                return "http://" + ip;
            }
        } catch (Exception e) {
            log.debug("SML DNS resolution failed for {}, using configured SMP: {}",
                    participantId, e.getMessage());
        }
        return properties.getSmp().getBaseUrl();
    }

    String buildSmlHost(String participantId) {
        String[] parts = participantId.split(":", 2);
        String scheme = parts.length > 1 ? parts[0] : "iso6523-actorid-upis";
        String value = parts.length > 1 ? parts[1] : participantId;
        return properties.getSml().getDnsPrefix()
                + "-" + scheme + "-" + value
                + "." + properties.getSml().getDomain();
    }

    static String urlEncodeParticipantId(String participantId) {
        return participantId.replace(":", "::");
    }

    SmpServiceMetadata parseSmpResponse(
            String participantId, String documentTypeId, String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            String ns = detectNamespace(doc);

            Element serviceMetadata;
            NodeList sml = doc.getElementsByTagNameNS(ns, "ServiceMetadata");
            if (sml.getLength() > 0) {
                serviceMetadata = (Element) sml.item(0);
            } else {
                NodeList prl = doc.getElementsByTagNameNS(ns, "ParticipantRedirect");
                if (prl.getLength() > 0) {
                    return handleRedirect(participantId, documentTypeId,
                            (Element) prl.item(0), ns);
                }
                throw new IllegalArgumentException("No ServiceMetadata or ParticipantRedirect in SMP response");
            }

            Element redirect = getChild(serviceMetadata, ns, "Redirect");
            if (redirect != null) {
                String href = redirect.getAttribute("href");
                return fetchAndParseMetadata(participantId, documentTypeId, href);
            }

            Element serviceInfo = getChild(serviceMetadata, ns, "ServiceInformation");
            if (serviceInfo == null) {
                throw new IllegalArgumentException("No ServiceInformation in SMP response");
            }

            String processId = null;
            String transportProfile = null;
            String endpointUrl = null;
            X509Certificate cert = null;
            List<String> descriptions = new ArrayList<>();

            NodeList processes = serviceInfo.getElementsByTagNameNS(ns, "Process");
            if (processes.getLength() > 0) {
                Element process = (Element) processes.item(0);
                processId = getTextContent(process, ns, "ProcessIdentifier");
                transportProfile = getTextContent(process, ns, "TransportProfile");

                Element ep = getChild(process, ns, "ServiceEndpointList");
                if (ep != null) {
                    Element epEntry = getChild(ep, ns, "Endpoint");
                    if (epEntry != null) {
                        endpointUrl = getTextContent(epEntry, ns, "EndpointURI");
                        Element certEl = getChild(epEntry, ns, "Certificate");
                        if (certEl != null) {
                            cert = parseCertificate(certEl.getTextContent());
                        }
                        String desc = getTextContent(epEntry, ns, "ServiceDescription");
                        if (desc != null) descriptions.add(desc);
                    }
                }
            }

            if (endpointUrl == null) {
                throw new IllegalArgumentException("No endpoint found in SMP response for " + participantId);
            }

            return new SmpServiceMetadata(
                    participantId, documentTypeId, processId,
                    transportProfile, endpointUrl, cert, descriptions);

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse SMP response for " + participantId + ": " + e.getMessage(), e);
        }
    }

    private SmpServiceMetadata handleRedirect(
            String participantId, String documentTypeId,
            Element redirectEl, String ns) {
        String href = redirectEl.getAttribute("href");
        if (href == null || href.isBlank()) {
            throw new IllegalArgumentException("ParticipantRedirect with no href");
        }
        return fetchAndParseMetadata(participantId, documentTypeId, href);
    }

    private SmpServiceMetadata fetchAndParseMetadata(
            String participantId, String documentTypeId, String url) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_XML, MediaType.TEXT_XML));
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getBody() == null) {
                throw new IllegalArgumentException("Empty response from " + url);
            }
            return parseSmpResponse(participantId, documentTypeId, response.getBody());
        } catch (RestClientException e) {
            throw new IllegalArgumentException("Failed to follow SMP redirect to " + url, e);
        }
    }

    private String detectNamespace(Document doc) {
        Element root = doc.getDocumentElement();
        String ns = root.getNamespaceURI();
        if (ns != null) return ns;
        NodeList smNodes = doc.getElementsByTagNameNS(NS_SMP, "ServiceMetadata");
        if (smNodes.getLength() > 0) return NS_SMP;
        smNodes = doc.getElementsByTagNameNS(NS_SMP_LEGACY, "ServiceMetadata");
        if (smNodes.getLength() > 0) return NS_SMP_LEGACY;
        return NS_SMP;
    }

    private Element getChild(Element parent, String ns, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el
                    && ns.equals(el.getNamespaceURI())
                    && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    private String getTextContent(Element parent, String ns, String localName) {
        Element el = getChild(parent, ns, localName);
        return el != null ? el.getTextContent() : null;
    }

    private X509Certificate parseCertificate(String base64) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            byte[] der = Base64.getDecoder().decode(base64.trim());
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            log.warn("Failed to parse SMP certificate: {}", e.getMessage());
            return null;
        }
    }

    @Scheduled(fixedRateString = "${peppol.smp.cache-ttl-seconds:3600}000")
    public void clearCache() {
        cache.clear();
        log.debug("SMP cache cleared ({} entries)", cache.size());
    }

    private record CacheEntry(Optional<SmpServiceMetadata> value, Instant expiresAt) {
        CacheEntry(Optional<SmpServiceMetadata> value, long ttlSeconds) {
            this(value, Instant.now().plusSeconds(ttlSeconds));
        }
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
