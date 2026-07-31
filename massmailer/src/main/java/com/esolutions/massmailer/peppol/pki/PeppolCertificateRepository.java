package com.esolutions.massmailer.peppol.pki;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PeppolCertificateRepository extends JpaRepository<PeppolCertificate, UUID> {

    Optional<PeppolCertificate> findByOrganizationIdAndStatus(
            UUID organizationId, PeppolCertificate.CertStatus status);

    List<PeppolCertificate> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    boolean existsByOrganizationIdAndStatus(
            UUID organizationId, PeppolCertificate.CertStatus status);
}
