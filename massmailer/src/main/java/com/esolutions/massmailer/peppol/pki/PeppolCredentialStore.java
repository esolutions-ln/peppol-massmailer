package com.esolutions.massmailer.peppol.pki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class PeppolCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(PeppolCredentialStore.class);

    private final PeppolCertificateRepository certRepo;

    public PeppolCredentialStore(PeppolCertificateRepository certRepo) {
        this.certRepo = certRepo;
    }

    @Transactional
    public PeppolCertificate storeCertificate(
            UUID organizationId,
            String certificatePem,
            String privateKeyPem,
            String description) {
        X509Certificate cert = parseCertificate(certificatePem);
        PeppolCertificate entity = PeppolCertificate.builder()
                .organizationId(organizationId)
                .certificatePem(certificatePem)
                .privateKeyPem(privateKeyPem)
                .issuerDn(cert.getIssuerX500Principal().getName())
                .subjectDn(cert.getSubjectX500Principal().getName())
                .serialNumber(cert.getSerialNumber().toString(16))
                .validFrom(cert.getNotBefore().toInstant())
                .validTo(cert.getNotAfter().toInstant())
                .description(description)
                .build();
        return certRepo.save(entity);
    }

    @Transactional
    public PeppolCertificate rotateCertificate(
            UUID organizationId,
            String certificatePem,
            String privateKeyPem,
            String description) {
        expireCurrent(organizationId);
        return storeCertificate(organizationId, certificatePem, privateKeyPem, description);
    }

    public Optional<LoadedKeyMaterial> loadActive(UUID organizationId) {
        return certRepo.findByOrganizationIdAndStatus(
                        organizationId, PeppolCertificate.CertStatus.ACTIVE)
                .filter(PeppolCertificate::isValid)
                .map(this::toKeyMaterial);
    }

    private void expireCurrent(UUID organizationId) {
        certRepo.findByOrganizationIdAndStatus(
                        organizationId, PeppolCertificate.CertStatus.ACTIVE)
                .ifPresent(existing -> {
                    existing.setStatus(PeppolCertificate.CertStatus.ROTATED);
                    existing.setRotatedAt(Instant.now());
                    certRepo.save(existing);
                });
    }

    private LoadedKeyMaterial toKeyMaterial(PeppolCertificate entity) {
        try {
            X509Certificate cert = parseCertificate(entity.getCertificatePem());
            PrivateKey key = parsePrivateKey(entity.getPrivateKeyPem());
            return new LoadedKeyMaterial(cert, key, entity);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to parse stored certificate/key for org " + entity.getOrganizationId(), e);
        }
    }

    static X509Certificate parseCertificate(String pem) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            String cleaned = pem
                    .replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(cleaned);
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse X.509 certificate", e);
        }
    }

    static PrivateKey parsePrivateKey(String pem) {
        try {
            String cleaned = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(cleaned);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse RSA private key (PKCS#8)", e);
        }
    }

    public record LoadedKeyMaterial(
            X509Certificate certificate,
            PrivateKey privateKey,
            PeppolCertificate entity
    ) {}
}
