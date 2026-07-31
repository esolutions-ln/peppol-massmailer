package com.esolutions.massmailer.invitation.repository;

import com.esolutions.massmailer.invitation.model.InvitationStatus;
import com.esolutions.massmailer.invitation.model.PeppolInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvitationRepository extends JpaRepository<PeppolInvitation, UUID> {

    Optional<PeppolInvitation> findByToken(String token);

    List<PeppolInvitation> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<PeppolInvitation> findByOrganizationIdAndCustomerContactIdAndStatus(
            UUID organizationId, UUID customerContactId, InvitationStatus status);

    List<PeppolInvitation> findByStatusAndExpiresAtBefore(InvitationStatus status, Instant now);
}
