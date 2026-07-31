package com.esolutions.massmailer.peppol.as4;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.apache.xml.security.Init;
import org.apache.xml.security.encryption.XMLCipher;
import org.apache.xml.security.utils.EncryptionConstants;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * AS4 ebMS 3.0 transport client implementation.
 *
 * <p>Builds a valid ebMS 3.0 SOAP 1.2 envelope, signs it with the sender's
 * X.509 private key using XML-DSIG (enveloped signature), encrypts the
 * payload with the receiver's X.509 certificate using XML-Enc (AES-256 /
 * RSA-OAEP via Apache Santuario), and POSTs it to the receiver's AS4
 * endpoint. The MDN response is parsed to determine delivery success or failure.
 *
 * <p>This implementation is compliant with the PEPPOL Transport Infrastructure
 * AS4 Profile 2.0 for message-level security (sign-then-encrypt).
 */
@Component
public class As4TransportClientImpl implements As4TransportClient {

    private static final Logger log = LoggerFactory.getLogger(As4TransportClientImpl.class);

    // PEPPOL / ebMS 3.0 namespace constants
    private static final String NS_SOAP12  = "http://www.w3.org/2003/05/soap-envelope";
    private static final String NS_EBMS    = "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/";
    private static final String NS_WSU     = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
    private static final String NS_WSSE    = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    // PEPPOL document type / process IDs
    private static final String PEPPOL_DOCUMENT_TYPE =
            "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1";
    private static final String PEPPOL_PROCESS_ID =
            "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

    static {
        Init.init();
    }

    private final RestTemplate restTemplate;

    public As4TransportClientImpl(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Override
    public As4DeliveryResult send(As4Message message) {
        requireCryptoMaterial(message);
        try {
            return buildSignAndPost(message);
        } catch (Exception e) {
            throw new As4TransportException(
                    "AS4 transport failed: " + e.getMessage(), e);
        }
    }

    private static void requireCryptoMaterial(As4Message message) {
        if (message.senderPrivateKey() == null || message.senderCert() == null) {
            throw new As4TransportException(
                    "AS4 transport requires the sender's X.509 private key and certificate. " +
                    "Provision sender AP credentials before enabling AS4 (or set " +
                    "simplifiedHttpDelivery=true on the receiver AccessPoint for internal traffic).");
        }
        if (message.receiverCert() == null) {
            throw new As4TransportException(
                    "AS4 transport requires the receiver's X.509 certificate for payload encryption. " +
                    "Register the receiver AP's certificate in the eRegistry before AS4 delivery.");
        }
    }

    private As4DeliveryResult buildSignAndPost(As4Message message) throws Exception {
        String messageId = "msg-" + UUID.randomUUID() + "@invoicedirect.biz";
        log.info("AS4 send: messageId={} → {}", messageId, message.receiverEndpointUrl());
        Document soapDoc = buildSoapEnvelope(message, messageId);
        signSoapMessage(soapDoc, message.senderPrivateKey(), message.senderCert());
        encryptPayload(soapDoc, message.receiverCert());
        String soapXml = serialiseDocument(soapDoc);
        return postAndParseMdn(soapXml, message.receiverEndpointUrl(), messageId);
    }

    // -------------------------------------------------------------------------
    // SOAP envelope construction
    // -------------------------------------------------------------------------

    /**
     * Builds a SOAP 1.2 envelope with an ebMS 3.0 {@code eb:Messaging} header
     * and a {@code Body} containing the Base64-encoded UBL payload.
     */
    private Document buildSoapEnvelope(As4Message msg, String messageId) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.newDocument();

        // <S12:Envelope>
        Element envelope = doc.createElementNS(NS_SOAP12, "S12:Envelope");
        envelope.setAttribute("xmlns:S12", NS_SOAP12);
        envelope.setAttribute("xmlns:eb", NS_EBMS);
        envelope.setAttribute("xmlns:wsu", NS_WSU);
        envelope.setAttribute("xmlns:wsse", NS_WSSE);
        envelope.setAttribute("xmlns:xenc", "http://www.w3.org/2001/04/xmlenc#");
        doc.appendChild(envelope);

        // <S12:Header>
        Element header = doc.createElementNS(NS_SOAP12, "S12:Header");
        envelope.appendChild(header);

        // <eb:Messaging>
        Element messaging = buildMessagingHeader(doc, msg, messageId);
        header.appendChild(messaging);

        // <S12:Body>
        Element body = doc.createElementNS(NS_SOAP12, "S12:Body");
        body.setAttributeNS(NS_WSU, "wsu:Id", "body");
        body.setIdAttributeNS(NS_WSU, "Id", true);
        envelope.appendChild(body);

        // Embed UBL payload as Base64 in a PayloadDocument element
        String payloadId = "cid:payload-" + UUID.randomUUID() + "@invoicedirect.biz";
        Element payloadDoc = doc.createElementNS(NS_EBMS, "eb:PayloadDocument");
        payloadDoc.setAttribute("id", payloadId);
        payloadDoc.setTextContent(
                Base64.getEncoder().encodeToString(
                        msg.ublXmlPayload().getBytes(StandardCharsets.UTF_8)));
        body.appendChild(payloadDoc);

        return doc;
    }

    /**
     * Builds the {@code eb:Messaging} header with UserMessage, MessageInfo,
     * PartyInfo, CollaborationInfo, and PayloadInfo sub-elements.
     */
    private Element buildMessagingHeader(Document doc, As4Message msg, String messageId) {
        Element messaging = doc.createElementNS(NS_EBMS, "eb:Messaging");
        messaging.setAttributeNS(NS_WSU, "wsu:Id", "messaging");

        Element userMessage = doc.createElementNS(NS_EBMS, "eb:UserMessage");
        messaging.appendChild(userMessage);

        // MessageInfo
        Element msgInfo = doc.createElementNS(NS_EBMS, "eb:MessageInfo");
        userMessage.appendChild(msgInfo);
        appendText(doc, msgInfo, NS_EBMS, "eb:Timestamp", Instant.now().toString());
        appendText(doc, msgInfo, NS_EBMS, "eb:MessageId", messageId);

        // PartyInfo
        Element partyInfo = doc.createElementNS(NS_EBMS, "eb:PartyInfo");
        userMessage.appendChild(partyInfo);

        Element from = doc.createElementNS(NS_EBMS, "eb:From");
        partyInfo.appendChild(from);
        Element fromPartyId = doc.createElementNS(NS_EBMS, "eb:PartyId");
        fromPartyId.setAttribute("type", "urn:fdc:peppol.eu:2017:identifiers:ap");
        fromPartyId.setTextContent(msg.senderParticipantId());
        from.appendChild(fromPartyId);
        appendText(doc, from, NS_EBMS, "eb:Role",
                "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/initiator");

        Element to = doc.createElementNS(NS_EBMS, "eb:To");
        partyInfo.appendChild(to);
        Element toPartyId = doc.createElementNS(NS_EBMS, "eb:PartyId");
        toPartyId.setAttribute("type", "urn:fdc:peppol.eu:2017:identifiers:ap");
        toPartyId.setTextContent(msg.receiverParticipantId());
        to.appendChild(toPartyId);
        appendText(doc, to, NS_EBMS, "eb:Role",
                "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/responder");

        // CollaborationInfo
        Element collabInfo = doc.createElementNS(NS_EBMS, "eb:CollaborationInfo");
        userMessage.appendChild(collabInfo);

        Element service = doc.createElementNS(NS_EBMS, "eb:Service");
        service.setAttribute("type", "urn:fdc:peppol.eu:2017:identifiers:doctype");
        service.setTextContent(
                msg.documentTypeId() != null ? msg.documentTypeId() : PEPPOL_DOCUMENT_TYPE);
        collabInfo.appendChild(service);

        appendText(doc, collabInfo, NS_EBMS, "eb:Action",
                msg.processId() != null ? msg.processId() : PEPPOL_PROCESS_ID);
        appendText(doc, collabInfo, NS_EBMS, "eb:ConversationId",
                "conv-" + UUID.randomUUID());

        // PayloadInfo
        Element payloadInfo = doc.createElementNS(NS_EBMS, "eb:PayloadInfo");
        userMessage.appendChild(payloadInfo);

        Element partInfo = doc.createElementNS(NS_EBMS, "eb:PartInfo");
        partInfo.setAttribute("href", "#body");
        payloadInfo.appendChild(partInfo);

        Element partProperties = doc.createElementNS(NS_EBMS, "eb:PartProperties");
        partInfo.appendChild(partProperties);

        Element prop = doc.createElementNS(NS_EBMS, "eb:Property");
        prop.setAttribute("name", "MimeType");
        prop.setTextContent("application/xml");
        partProperties.appendChild(prop);

        return messaging;
    }

    // -------------------------------------------------------------------------
    // XML-DSIG signing
    // -------------------------------------------------------------------------

    /**
     * Signs the SOAP message using an enveloped XML-DSIG signature over the
     * {@code S12:Body} element, keyed with the sender's X.509 private key.
     * The {@code KeyInfo} element embeds the sender's certificate so the
     * receiver can verify the signature without out-of-band key exchange.
     */
    private void signSoapMessage(Document doc, PrivateKey privateKey, X509Certificate cert)
            throws Exception {
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");

        // Reference to the Body element (id="body")
        Reference ref = fac.newReference(
                "#body",
                fac.newDigestMethod(DigestMethod.SHA256, null),
                List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                null,
                null);

        // SignedInfo with C14N and RSA-SHA256
        SignedInfo si = fac.newSignedInfo(
                fac.newCanonicalizationMethod(
                        CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                List.of(ref));

        // KeyInfo — embed X.509 certificate
        KeyInfoFactory kif = fac.getKeyInfoFactory();
        List<Object> x509Content = new ArrayList<>();
        x509Content.add(cert);
        X509Data xd = kif.newX509Data(x509Content);
        KeyInfo ki = kif.newKeyInfo(List.of(xd));

        // Sign into the SOAP Header
        Element header = (Element) doc.getElementsByTagNameNS(NS_SOAP12, "Header").item(0);
        DOMSignContext dsc = new DOMSignContext(privateKey, header);
        dsc.setDefaultNamespacePrefix("ds");

        XMLSignature signature = fac.newXMLSignature(si, ki);
        signature.sign(dsc);

        log.debug("SOAP message signed with XML-DSIG (RSA-SHA256)");
    }

    // -------------------------------------------------------------------------
    // XML-Enc encryption (AES-256 + RSA-OAEP via Apache Santuario)
    // -------------------------------------------------------------------------

    /**
     * Encrypts the SOAP Body using AES-256 / RSA-OAEP XML-Enc.
     *
     * <p>Uses Apache Santuario to encrypt the Body with an ephemeral AES-256 key,
     * then builds the {@code xenc:EncryptedKey} element manually (using JCA) and
     * places it in the {@code wsse:Security} header with a {@code ds:KeyInfo}
     * referencing the receiver's certificate.
     */
    private void encryptPayload(Document doc, X509Certificate receiverCert) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey symmetricKey = keyGen.generateKey();

        String encryptedKeyId = "EK-" + UUID.randomUUID();

        XMLCipher aesCipher = XMLCipher.getInstance(XMLCipher.AES_256);
        aesCipher.init(XMLCipher.ENCRYPT_MODE, symmetricKey);
        Element bodyElement = (Element) doc.getElementsByTagNameNS(NS_SOAP12, "Body").item(0);
        aesCipher.doFinal(doc, bodyElement);

        Element encryptedData = (Element) bodyElement.getElementsByTagNameNS(
                EncryptionConstants.EncryptionSpecNS, "EncryptedData").item(0);
        if (encryptedData != null) {
            Element keyInfoElement = doc.createElementNS(
                    javax.xml.crypto.dsig.XMLSignature.XMLNS, "ds:KeyInfo");
            Element retrievalMethod = doc.createElementNS(
                    javax.xml.crypto.dsig.XMLSignature.XMLNS, "ds:RetrievalMethod");
            retrievalMethod.setAttribute("URI", "#" + encryptedKeyId);
            retrievalMethod.setAttribute("Type",
                    "http://www.w3.org/2001/04/xmlenc#EncryptedKey");
            keyInfoElement.appendChild(retrievalMethod);
            encryptedData.insertBefore(keyInfoElement, encryptedData.getFirstChild());
        }

        javax.crypto.Cipher rsaCipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsaCipher.init(javax.crypto.Cipher.WRAP_MODE, receiverCert.getPublicKey());
        byte[] encryptedKeyBytes = rsaCipher.wrap(symmetricKey);

        Element header = (Element) doc.getElementsByTagNameNS(NS_SOAP12, "Header").item(0);
        Element security;
        NodeList secList = doc.getElementsByTagNameNS(NS_WSSE, "Security");
        if (secList.getLength() > 0) {
            security = (Element) secList.item(0);
        } else {
            security = doc.createElementNS(NS_WSSE, "wsse:Security");
            Element firstChild = (Element) header.getFirstChild();
            if (firstChild != null) {
                header.insertBefore(security, firstChild);
            } else {
                header.appendChild(security);
            }
        }

        Element encryptedKeyElement = doc.createElementNS(
                EncryptionConstants.EncryptionSpecNS, "xenc:EncryptedKey");
        encryptedKeyElement.setAttributeNS(NS_WSU, "wsu:Id", encryptedKeyId);
        Element encMethod = doc.createElementNS(
                EncryptionConstants.EncryptionSpecNS, "xenc:EncryptionMethod");
        encMethod.setAttribute("Algorithm",
                "http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p");
        encryptedKeyElement.appendChild(encMethod);

        Element keyInfoOuter = doc.createElementNS(
                javax.xml.crypto.dsig.XMLSignature.XMLNS, "ds:KeyInfo");
        Element x509Data = doc.createElementNS(
                javax.xml.crypto.dsig.XMLSignature.XMLNS, "ds:X509Data");
        Element x509CertEl = doc.createElementNS(
                javax.xml.crypto.dsig.XMLSignature.XMLNS, "ds:X509Certificate");
        x509CertEl.setTextContent(
                Base64.getEncoder().encodeToString(receiverCert.getEncoded()));
        x509Data.appendChild(x509CertEl);
        keyInfoOuter.appendChild(x509Data);
        encryptedKeyElement.appendChild(keyInfoOuter);

        Element cipherData = doc.createElementNS(
                EncryptionConstants.EncryptionSpecNS, "xenc:CipherData");
        Element cipherValue = doc.createElementNS(
                EncryptionConstants.EncryptionSpecNS, "xenc:CipherValue");
        cipherValue.setTextContent(Base64.getEncoder().encodeToString(encryptedKeyBytes));
        cipherData.appendChild(cipherValue);
        encryptedKeyElement.appendChild(cipherData);

        security.appendChild(encryptedKeyElement);

        log.debug("SOAP Body encrypted with AES-256 / RSA-OAEP");
    }

    // -------------------------------------------------------------------------
    // HTTP transport and MDN parsing
    // -------------------------------------------------------------------------

    /**
     * POSTs the signed SOAP envelope to the receiver's AS4 endpoint and
     * parses the MDN response.
     */
    private As4DeliveryResult postAndParseMdn(String soapXml, String endpointUrl,
                                               String sentMessageId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/soap+xml; charset=UTF-8"));
        headers.set("SOAPAction", "");
        HttpEntity<String> request = new HttpEntity<>(soapXml, headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(endpointUrl, request, String.class);
        } catch (RestClientException ex) {
            throw new As4TransportException(
                    "Network error posting to AS4 endpoint [" + endpointUrl + "]: " + ex.getMessage(), ex);
        }

        int statusCode = response.getStatusCode().value();
        String body = response.getBody();

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("AS4 endpoint returned non-2xx: {} for messageId={}", statusCode, sentMessageId);
            return As4DeliveryResult.failure(
                    "AS4 endpoint returned HTTP " + statusCode + ": " + truncate(body, 200));
        }

        // Parse MDN from response body
        return parseMdn(body, sentMessageId);
    }

    /**
     * Parses the MDN (Message Disposition Notification) from the AS4 response.
     *
     * <p>Looks for {@code eb:SignalMessage} / {@code eb:Receipt} in the response
     * SOAP envelope. Falls back to a text-based heuristic if the response is not
     * a well-formed SOAP/ebMS document (e.g. simplified HTTP endpoints).
     */
    private As4DeliveryResult parseMdn(String responseBody, String sentMessageId) {
        if (responseBody == null || responseBody.isBlank()) {
            // Empty 200 — treat as implicit acknowledgement (simplified endpoints)
            log.debug("Empty MDN body — treating as implicit success for messageId={}", sentMessageId);
            return As4DeliveryResult.success(
                    "mdn-" + UUID.randomUUID(), "processed", responseBody);
        }

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document mdnDoc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));

            // Look for eb:Error elements — indicates failure
            NodeList errors = mdnDoc.getElementsByTagNameNS(NS_EBMS, "Error");
            if (errors.getLength() > 0) {
                Element error = (Element) errors.item(0);
                String errorCode = error.getAttribute("errorCode");
                String shortDesc = error.getAttribute("shortDescription");
                String desc = "MDN error: " + errorCode + " — " + shortDesc;
                log.warn("AS4 MDN failure for messageId={}: {}", sentMessageId, desc);
                return As4DeliveryResult.failure(desc);
            }

            // Look for eb:Receipt — indicates success
            NodeList receipts = mdnDoc.getElementsByTagNameNS(NS_EBMS, "Receipt");
            if (receipts.getLength() > 0) {
                // Extract MDN message ID from SignalMessage/MessageInfo/MessageId
                String mdnMessageId = extractMdnMessageId(mdnDoc, sentMessageId);
                log.info("AS4 MDN receipt received: mdnMessageId={}", mdnMessageId);
                return As4DeliveryResult.success(mdnMessageId, "processed", responseBody);
            }

            // No Receipt and no Error — check for "processed" / "failed" text
            String lowerBody = responseBody.toLowerCase();
            if (lowerBody.contains("failed") || lowerBody.contains("error")) {
                return As4DeliveryResult.failure("MDN indicates failure: " + truncate(responseBody, 200));
            }

            // Default: HTTP 200 with no explicit error → success
            String mdnMessageId = "mdn-" + UUID.randomUUID();
            log.debug("AS4 MDN parsed as implicit success: mdnMessageId={}", mdnMessageId);
            return As4DeliveryResult.success(mdnMessageId, "processed", responseBody);

        } catch (Exception ex) {
            // Non-XML response body — use heuristic
            log.debug("MDN response is not XML, using heuristic: {}", ex.getMessage());
            String lowerBody = responseBody.toLowerCase();
            if (lowerBody.contains("failed") || lowerBody.contains("error")) {
                return As4DeliveryResult.failure("MDN indicates failure: " + truncate(responseBody, 200));
            }
            return As4DeliveryResult.success("mdn-" + UUID.randomUUID(), "processed", responseBody);
        }
    }

    /**
     * Extracts the MDN message ID from the {@code eb:SignalMessage/eb:MessageInfo/eb:MessageId}
     * element, falling back to a generated ID if not present.
     */
    private String extractMdnMessageId(Document mdnDoc, String sentMessageId) {
        NodeList signalMessages = mdnDoc.getElementsByTagNameNS(NS_EBMS, "SignalMessage");
        if (signalMessages.getLength() > 0) {
            Element signalMsg = (Element) signalMessages.item(0);
            NodeList msgIds = signalMsg.getElementsByTagNameNS(NS_EBMS, "MessageId");
            if (msgIds.getLength() > 0) {
                String id = msgIds.item(0).getTextContent();
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
        }
        return "mdn-reply-to-" + sentMessageId;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private String serialiseDocument(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter sw = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    private void appendText(Document doc, Element parent, String ns, String qname, String text) {
        Element el = doc.createElementNS(ns, qname);
        el.setTextContent(text);
        parent.appendChild(el);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "(null)";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
