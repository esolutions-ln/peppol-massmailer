package com.esolutions.massmailer.peppol.pki;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "peppol_certificates",
        indexes = {
                @Index(name = "idx_pki_org", columnList = "organizationId"),
                @Index(name = "idx_pki_status", columnList = "status")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeppolCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String certificatePem;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String privateKeyPem;

    @Column(length = 500)
    private String issuerDn;

    @Column(length = 500)
    private String subjectDn;

    @Column(length = 100)
    private String serialNumber;

    private Instant validFrom;

    private Instant validTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CertStatus status = CertStatus.ACTIVE;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant rotatedAt;

    @Column(length = 500)
    private String description;

    public enum CertStatus {
        ACTIVE,
        ROTATED,
        EXPIRED,
        REVOKED
    }

    public boolean isValid() {
        return status == CertStatus.ACTIVE
                && validFrom != null && validTo != null
                && Instant.now().isBefore(validTo)
                && Instant.now().isAfter(validFrom);
    }
}
