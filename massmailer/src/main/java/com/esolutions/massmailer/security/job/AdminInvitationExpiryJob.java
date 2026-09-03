package com.esolutions.massmailer.security.job;

import com.esolutions.massmailer.invitation.model.InvitationStatus;
import com.esolutions.massmailer.security.model.AdminInvitation;
import com.esolutions.massmailer.security.repository.AdminInvitationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled job that transitions PENDING admin invitations past their {@code expiresAt}
 * timestamp to {@code EXPIRED} status. Mirrors {@code InvitationExpiryJob} (PEPPOL).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminInvitationExpiryJob {

    private final AdminInvitationRepository invitationRepo;

    @Scheduled(fixedDelay = 3600000)
    public void expireStaleInvitations() {
        List<AdminInvitation> stale = invitationRepo
                .findByStatusAndExpiresAtBefore(InvitationStatus.PENDING, Instant.now());

        for (AdminInvitation invitation : stale) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            invitationRepo.save(invitation);
        }

        log.info("AdminInvitationExpiryJob: expired {} stale invitation(s)", stale.size());
    }
}
