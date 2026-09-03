package com.esolutions.massmailer.security.repository;

import com.esolutions.massmailer.invitation.model.InvitationStatus;
import com.esolutions.massmailer.security.model.AdminInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminInvitationRepository extends JpaRepository<AdminInvitation, UUID> {

    Optional<AdminInvitation> findByToken(String token);

    List<AdminInvitation> findByEmailAndStatus(String email, InvitationStatus status);

    List<AdminInvitation> findByStatusAndExpiresAtBefore(InvitationStatus status, Instant before);

    List<AdminInvitation> findAllByOrderByCreatedAtDesc();
}
