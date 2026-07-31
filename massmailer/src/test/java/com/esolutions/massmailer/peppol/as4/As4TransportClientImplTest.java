package com.esolutions.massmailer.peppol.as4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import com.esolutions.massmailer.peppol.PeppolTestCertificates;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class As4TransportClientImplTest {

    private static final String PEM_CERT = PeppolTestCertificates.PEM_CERT;
    private static final String PEM_PRIVATE_KEY = PeppolTestCertificates.PEM_KEY;

    private static X509Certificate testCert;
    private static PrivateKey testPrivateKey;

    private RestTemplate restTemplate;
    private As4TransportClientImpl client;

    @BeforeAll
    static void parseTestCert() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        testCert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(PEM_CERT.getBytes()));

        String pem = PEM_PRIVATE_KEY
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        testPrivateKey = kf.generatePrivate(spec);
    }

    private static As4Message fullMessage(String ublPayload) {
        return new As4Message(
                "0190:sender-org", "0190:receiver-org",
                "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##...",
                "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0",
                ublPayload,
                testCert, testPrivateKey, testCert,
                "https://ap.example.com/as4");
    }

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new As4TransportClientImpl(restTemplate);
    }

    // -------------------------------------------------------------------------
    // Crypto material validation
    // -------------------------------------------------------------------------

    @Test
    void send_throwsWhenPrivateKeyNull() {
        As4Message msg = new As4Message(
                "0190:sender", "0190:receiver", "dt", "pid",
                "<inv/>", testCert, null, testCert, "https://ap.example.com/as4");
        assertThatThrownBy(() -> client.send(msg))
                .isInstanceOf(As4TransportException.class)
                .hasMessageContaining("private key");
    }

    @Test
    void send_throwsWhenSenderCertNull() {
        As4Message msg = new As4Message(
                "0190:sender", "0190:receiver", "dt", "pid",
                "<inv/>", null, testPrivateKey, testCert, "https://ap.example.com/as4");
        assertThatThrownBy(() -> client.send(msg))
                .isInstanceOf(As4TransportException.class)
                .hasMessageContaining("certificate");
    }

    @Test
    void send_throwsWhenReceiverCertNull() {
        As4Message msg = new As4Message(
                "0190:sender", "0190:receiver", "dt", "pid",
                "<inv/>", testCert, testPrivateKey, null, "https://ap.example.com/as4");
        assertThatThrownBy(() -> client.send(msg))
                .isInstanceOf(As4TransportException.class)
                .hasMessageContaining("receiver");
    }

    // -------------------------------------------------------------------------
    // Full AS4 send flow
    // -------------------------------------------------------------------------

    @Test
    void send_successfulDelivery() {
        String successMdn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <S12:Envelope xmlns:S12="http://www.w3.org/2003/05/soap-envelope"
                              xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <S12:Header>
                    <eb:Messaging>
                      <eb:SignalMessage>
                        <eb:MessageInfo>
                          <eb:Timestamp>2026-06-19T12:00:00Z</eb:Timestamp>
                          <eb:MessageId>mdn-reply-001@ap.example.com</eb:MessageId>
                        </eb:MessageInfo>
                        <eb:Receipt>
                          <eb:NonRepudiationInformation/>
                        </eb:Receipt>
                      </eb:SignalMessage>
                    </eb:Messaging>
                  </S12:Header>
                  <S12:Body/>
                </S12:Envelope>
                """;
        when(restTemplate.postForEntity(
                eq("https://ap.example.com/as4"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(successMdn));

        As4DeliveryResult result = client.send(fullMessage("<invoice/>"));

        assertThat(result.success()).isTrue();
        assertThat(result.mdnMessageId()).isEqualTo("mdn-reply-001@ap.example.com");
        assertThat(result.mdnStatus()).isEqualTo("processed");
    }

    @Test
    void send_httpErrorReturnsFailure() {
        when(restTemplate.postForEntity(
                eq("https://ap.example.com/as4"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Server error"));

        As4DeliveryResult result = client.send(fullMessage("<invoice/>"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorDescription()).contains("500");
    }

    @Test
    void send_networkErrorThrowsException() {
        when(restTemplate.postForEntity(
                eq("https://ap.example.com/as4"), any(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        assertThatThrownBy(() -> client.send(fullMessage("<invoice/>")))
                .isInstanceOf(As4TransportException.class)
                .hasMessageContaining("Network error");
    }

    // -------------------------------------------------------------------------
    // MDN parsing (via reflection)
    // -------------------------------------------------------------------------

    @Test
    void parseMdn_emptyBodyReturnsSuccess() throws Exception {
        As4DeliveryResult result = invokeParseMdn("", "msg-001");
        assertThat(result.success()).isTrue();
    }

    @Test
    void parseMdn_ebErrorReturnsFailure() throws Exception {
        String mdn = """
                <?xml version="1.0"?>
                <S12:Envelope xmlns:S12="http://www.w3.org/2003/05/soap-envelope"
                              xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <S12:Header>
                    <eb:Messaging>
                      <eb:SignalMessage>
                        <eb:MessageInfo><eb:MessageId>mdn-err</eb:MessageId></eb:MessageInfo>
                        <eb:Error errorCode="EB:001" shortDescription="Value exceeds limit"/>
                      </eb:SignalMessage>
                    </eb:Messaging>
                  </S12:Header>
                </S12:Envelope>
                """;
        As4DeliveryResult result = invokeParseMdn(mdn, "msg-001");
        assertThat(result.success()).isFalse();
        assertThat(result.errorDescription()).contains("EB:001");
    }

    @Test
    void parseMdn_ebReceiptReturnsSuccess() throws Exception {
        String mdn = """
                <?xml version="1.0"?>
                <S12:Envelope xmlns:S12="http://www.w3.org/2003/05/soap-envelope"
                              xmlns:eb="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <S12:Header>
                    <eb:Messaging>
                      <eb:SignalMessage>
                        <eb:MessageInfo><eb:MessageId>mdn-ok-001</eb:MessageId></eb:MessageInfo>
                        <eb:Receipt><eb:NonRepudiationInformation/></eb:Receipt>
                      </eb:SignalMessage>
                    </eb:Messaging>
                  </S12:Header>
                </S12:Envelope>
                """;
        As4DeliveryResult result = invokeParseMdn(mdn, "msg-001");
        assertThat(result.success()).isTrue();
        assertThat(result.mdnMessageId()).isEqualTo("mdn-ok-001");
    }

    @Test
    void parseMdn_soapWithoutSignalReturnsImplicitSuccess() throws Exception {
        String mdn = """
                <?xml version="1.0"?>
                <S12:Envelope xmlns:S12="http://www.w3.org/2003/05/soap-envelope">
                  <S12:Header/>
                  <S12:Body/>
                </S12:Envelope>
                """;
        As4DeliveryResult result = invokeParseMdn(mdn, "msg-001");
        assertThat(result.success()).isTrue();
        assertThat(result.mdnStatus()).isEqualTo("processed");
    }

    @Test
    void parseMdn_nonXmlWithErrorTextReturnsFailure() throws Exception {
        As4DeliveryResult result = invokeParseMdn(
                "Delivery failed: connection timeout", "msg-001");
        assertThat(result.success()).isFalse();
        assertThat(result.errorDescription()).contains("failed");
    }

    @Test
    void parseMdn_nonXmlWithoutErrorReturnsSuccess() throws Exception {
        As4DeliveryResult result = invokeParseMdn("OK", "msg-001");
        assertThat(result.success()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private As4DeliveryResult invokeParseMdn(String body, String sentMessageId)
            throws Exception {
        Method method = As4TransportClientImpl.class.getDeclaredMethod(
                "parseMdn", String.class, String.class);
        method.setAccessible(true);
        return (As4DeliveryResult) method.invoke(client, body, sentMessageId);
    }
}
