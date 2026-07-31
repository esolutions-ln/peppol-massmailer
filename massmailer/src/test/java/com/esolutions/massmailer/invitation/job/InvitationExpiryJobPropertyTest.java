package com.esolutions.massmailer.invitation.job;

// Feature: peppol-customer-invitation, Property 14: Expiry Cleanup Job Transitions Stale Invitations

import com.esolutions.massmailer.invitation.model.InvitationStatus;
import com.esolutions.massmailer.invitation.model.PeppolInvitation;
import com.esolutions.massmailer.invitation.repository.InvitationRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for {@link InvitationExpiryJob}.
 */
class InvitationExpiryJobPropertyTest {

    /**
     * P14 — expiryJobTransitionsStaleInvitations
     *
     * <p>For any set of PENDING invitations where some have {@code expiresAt} in the past,
     * after the expiry job runs:
     * <ul>
     *   <li>All invitations with {@code expiresAt} in the past must have {@code status=EXPIRED}</li>
     *   <li>Invitations with {@code expiresAt} in the future must remain {@code PENDING}</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 6.3</b>
     */
    @Property(tries = 200)
    void expiryJobTransitionsStaleInvitations(
            @ForAll @IntRange(min = 0, max = 5) int expiredCount,
            @ForAll @IntRange(min = 0, max = 5) int pendingCount) {

        Instant now = Instant.now();

        // Build stale (past expiresAt) PENDING invitations
        List<PeppolInvitation> staleInvitations = new ArrayList<>();
        for (int i = 0; i < expiredCount; i++) {
            staleInvitations.add(PeppolInvitation.builder()
                    .id(UUID.randomUUID())
                    .organizationId(UUID.randomUUID())
                    .customerContactId(UUID.randomUUID())
                    .customerEmail("stale" + i + "@example.com")
                    .token(UUID.randomUUID().toString())
                    .status(InvitationStatus.PENDING)
                    .createdAt(now.minusSeconds(72 * 3600 + 3600))
                    .expiresAt(now.minusSeconds(3600))  // in the past
                    .build());
        }

        // Build still-valid PENDING invitations (future expiresAt)
        List<PeppolInvitation> validInvitations = new ArrayList<>();
        for (int i = 0; i < pendingCount; i++) {
            validInvitations.add(PeppolInvitation.builder()
                    .id(UUID.randomUUID())
                    .organizationId(UUID.randomUUID())
                    .customerContactId(UUID.randomUUID())
                    .customerEmail("valid" + i + "@example.com")
                    .token(UUID.randomUUID().toString())
                    .status(InvitationStatus.PENDING)
                    .createdAt(now.minusSeconds(3600))
                    .expiresAt(now.plusSeconds(72 * 3600))  // in the future
                    .build());
        }

        // Track all saved invitations
        List<PeppolInvitation> savedInvitations = new ArrayList<>();

        InvitationRepository invitationRepo = mock(InvitationRepository.class);

        // The job queries for PENDING invitations with expiresAt before now
        when(invitationRepo.findByStatusAndExpiresAtBefore(
                eq(InvitationStatus.PENDING), any(Instant.class)))
                .thenReturn(staleInvitations);

        when(invitationRepo.save(any(PeppolInvitation.class)))
                .thenAnswer(inv -> {
                    PeppolInvitation saved = inv.getArgument(0);
                    savedInvitations.add(saved);
                    return saved;
                });

        InvitationExpiryJob job = new InvitationExpiryJob(invitationRepo);

        // Act
        job.expireStaleInvitations();

        // Assert: all stale invitations must have been saved with EXPIRED status
        assertThat(savedInvitations)
                .as("Number of saved invitations must equal the number of stale invitations")
                .hasSize(expiredCount);

        for (PeppolInvitation stale : staleInvitations) {
            assertThat(stale.getStatus())
                    .as("Stale invitation %s must have status=EXPIRED after job runs", stale.getId())
                    .isEqualTo(InvitationStatus.EXPIRED);
        }

        // Assert: valid (future) invitations were never touched by the job
        for (PeppolInvitation valid : validInvitations) {
            assertThat(valid.getStatus())
                    .as("Valid invitation %s must remain PENDING after job runs", valid.getId())
                    .isEqualTo(InvitationStatus.PENDING);
        }

        // Assert: save was called exactly once per stale invitation
        verify(invitationRepo, times(expiredCount)).save(any(PeppolInvitation.class));
    }
}
