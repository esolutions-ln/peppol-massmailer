package com.esolutions.massmailer.peppol.repository;

import com.esolutions.massmailer.peppol.model.PeppolParticipantLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PeppolParticipantLinkRepository extends JpaRepository<PeppolParticipantLink, UUID> {

    Optional<PeppolParticipantLink> findByOrganizationIdAndCustomerContactId(
            UUID organizationId, UUID customerContactId);

    List<PeppolParticipantLink> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<PeppolParticipantLink> findByParticipantId(String participantId);
}
