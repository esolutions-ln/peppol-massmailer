package com.esolutions.massmailer.invitation.job;

import com.esolutions.massmailer.invitation.model.InvitationStatus;
import com.esolutions.massmailer.invitation.model.PeppolInvitation;
import com.esolutions.massmailer.invitation.repository.InvitationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled job that transitions PENDING invitations past their {@code expiresAt}
 * timestamp to {@code EXPIRED} status.
 *
 * <p>Implements Requirement 6.3.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvitationExpiryJob {

    private final InvitationRepository invitationRepo;

    /**
     * Runs every hour (fixed delay after previous execution completes).
     * Finds all PENDING invitations whose expiresAt is in the past and marks them EXPIRED.
     */
    @Scheduled(fixedDelay = 3600000)
    public void expireStaleInvitations() {
        List<PeppolInvitation> stale = invitationRepo
                .findByStatusAndExpiresAtBefore(InvitationStatus.PENDING, Instant.now());

        for (PeppolInvitation invitation : stale) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            invitationRepo.save(invitation);
        }

        log.info("InvitationExpiryJob: expired {} stale invitation(s)", stale.size());
    }
}
