package com.esolutions.massmailer.peppol.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.esolutions.massmailer.peppol.PeppolTestCertificates;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PeppolCredentialStoreTest {

    private static final UUID ORG_ID = UUID.randomUUID();

    private PeppolCertificateRepository certRepo;
    private PeppolCredentialStore store;

    @BeforeEach
    void setUp() {
        certRepo = mock(PeppolCertificateRepository.class);
        store = new PeppolCredentialStore(certRepo);
    }

    @Test
    void parseCertificate_acceptsValidPem() {
        X509Certificate cert = PeppolCredentialStore.parseCertificate(PeppolTestCertificates.PEM_CERT);
        assertThat(cert).isNotNull();
        assertThat(cert.getSubjectX500Principal().getName()).contains("Test PEPPOL AP");
    }

    @Test
    void parseCertificate_throwsOnGarbage() {
        assertThatThrownBy(() -> PeppolCredentialStore.parseCertificate("not-a-cert"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsePrivateKey_acceptsValidPem() {
        var key = PeppolCredentialStore.parsePrivateKey(PeppolTestCertificates.PEM_KEY);
        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void parsePrivateKey_throwsOnGarbage() {
        assertThatThrownBy(() -> PeppolCredentialStore.parsePrivateKey("not-a-key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storeCertificate_savesAndReturnsEntity() {
        when(certRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PeppolCertificate saved = store.storeCertificate(
                ORG_ID, PeppolTestCertificates.PEM_CERT,
                PeppolTestCertificates.PEM_KEY, "test cert");

        assertThat(saved.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(saved.getCertificatePem()).isEqualTo(PeppolTestCertificates.PEM_CERT);
        assertThat(saved.getStatus()).isEqualTo(PeppolCertificate.CertStatus.ACTIVE);
        assertThat(saved.getIssuerDn()).isNotBlank();
        assertThat(saved.getSubjectDn()).contains("Test PEPPOL AP");
        assertThat(saved.getValidFrom()).isBefore(Instant.now());
        assertThat(saved.getValidTo()).isAfter(Instant.now());
    }

    @Test
    void storeCertificate_marksExistingAsRotated() {
        PeppolCertificate existing = PeppolCertificate.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .status(PeppolCertificate.CertStatus.ACTIVE)
                .build();
        when(certRepo.findByOrganizationIdAndStatus(ORG_ID, PeppolCertificate.CertStatus.ACTIVE))
                .thenReturn(Optional.of(existing));
        when(certRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.rotateCertificate(ORG_ID, PeppolTestCertificates.PEM_CERT,
                PeppolTestCertificates.PEM_KEY, "rotated cert");

        assertThat(existing.getStatus()).isEqualTo(PeppolCertificate.CertStatus.ROTATED);
        assertThat(existing.getRotatedAt()).isNotNull();
    }

    @Test
    void loadActive_returnsEmptyWhenNoneActive() {
        when(certRepo.findByOrganizationIdAndStatus(ORG_ID, PeppolCertificate.CertStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(store.loadActive(ORG_ID)).isEmpty();
    }

    @Test
    void loadActive_returnsEmptyWhenExpired() {
        PeppolCertificate expired = PeppolCertificate.builder()
                .certificatePem(PeppolTestCertificates.PEM_CERT)
                .privateKeyPem(PeppolTestCertificates.PEM_KEY)
                .status(PeppolCertificate.CertStatus.ACTIVE)
                .validFrom(Instant.now().minusSeconds(86400 * 400)) // over a year ago
                .validTo(Instant.now().minusSeconds(1))             // already expired
                .build();
        when(certRepo.findByOrganizationIdAndStatus(ORG_ID, PeppolCertificate.CertStatus.ACTIVE))
                .thenReturn(Optional.of(expired));

        assertThat(store.loadActive(ORG_ID)).isEmpty();
    }

    @Test
    void loadActive_returnsKeyMaterialWhenValid() {
        PeppolCertificate valid = PeppolCertificate.builder()
                .certificatePem(PeppolTestCertificates.PEM_CERT)
                .privateKeyPem(PeppolTestCertificates.PEM_KEY)
                .status(PeppolCertificate.CertStatus.ACTIVE)
                .validFrom(Instant.now().minusSeconds(3600))
                .validTo(Instant.now().plusSeconds(86400 * 30))
                .build();
        when(certRepo.findByOrganizationIdAndStatus(ORG_ID, PeppolCertificate.CertStatus.ACTIVE))
                .thenReturn(Optional.of(valid));

        Optional<PeppolCredentialStore.LoadedKeyMaterial> loaded = store.loadActive(ORG_ID);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().certificate()).isNotNull();
        assertThat(loaded.get().privateKey()).isNotNull();
    }

    @Test
    void rotateCertificate_expiresOldAndStoresNew() {
        PeppolCertificate existing = PeppolCertificate.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .status(PeppolCertificate.CertStatus.ACTIVE)
                .build();
        when(certRepo.findByOrganizationIdAndStatus(ORG_ID, PeppolCertificate.CertStatus.ACTIVE))
                .thenReturn(Optional.of(existing));
        when(certRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PeppolCertificate rotated = store.rotateCertificate(
                ORG_ID, PeppolTestCertificates.PEM_CERT,
                PeppolTestCertificates.PEM_KEY, "after rotation");

        assertThat(existing.getStatus()).isEqualTo(PeppolCertificate.CertStatus.ROTATED);
        assertThat(rotated.getStatus()).isEqualTo(PeppolCertificate.CertStatus.ACTIVE);
        assertThat(rotated.getDescription()).isEqualTo("after rotation");
    }
}
