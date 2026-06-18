package com.esolutions.massmailer.controller;

import com.esolutions.massmailer.dto.MailDtos.*;
import com.esolutions.massmailer.model.CampaignStatus;
import com.esolutions.massmailer.model.MailCampaign;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.security.OrgPrincipal;
import com.esolutions.massmailer.service.CampaignOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for campaign access control.
 */
@ExtendWith(MockitoExtension.class)
class CampaignControllerAccessControlTest {

    @Mock CampaignOrchestrator orchestrator;
    @InjectMocks CampaignController controller;

    @Test
    void getStatusReturnsCampaignWhenOrgOwnsIt() {
        UUID campaignId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        var org = Organization.builder().id(orgId).name("Test").slug("test")
                .apiKey("key").senderEmail("a@b.com").senderDisplayName("Test").build();
        var principal = new OrgPrincipal(org);

        when(orchestrator.campaignBelongsToOrg(campaignId, orgId)).thenReturn(true);
        when(orchestrator.getCampaignStatus(campaignId)).thenReturn(
                new CampaignResponse(campaignId, "Test", "COMPLETED", 1, 1, 0, 0,
                        "2026-01-01T00:00:00Z", null, null));

        var response = controller.getStatus(principal, campaignId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getStatusReturns404WhenOrgDoesNotOwnCampaign() {
        UUID campaignId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        var org = Organization.builder().id(orgId).name("Test").slug("test")
                .apiKey("key").senderEmail("a@b.com").senderDisplayName("Test").build();
        var principal = new OrgPrincipal(org);

        when(orchestrator.campaignBelongsToOrg(campaignId, orgId)).thenReturn(false);

        var response = controller.getStatus(principal, campaignId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createCampaignOverridesOrgIdForOrgUsers() {
        UUID orgId = UUID.randomUUID();
        var org = Organization.builder().id(orgId).name("Test").slug("test")
                .apiKey("key").senderEmail("a@b.com").senderDisplayName("Test").build();
        var principal = new OrgPrincipal(org);

        var request = new CampaignRequest("Test", "Subject", "invoice",
                null, UUID.randomUUID(), null, List.of());

        var campaign = MailCampaign.builder()
                .id(UUID.randomUUID())
                .name("Test")
                .subject("Subject")
                .templateName("invoice")
                .totalRecipients(0)
                .organizationId(orgId)
                .status(CampaignStatus.QUEUED)
                .build();

        when(orchestrator.createCampaign(any())).thenAnswer(inv -> {
            CampaignRequest req = inv.getArgument(0);
            assertThat(req.organizationId()).isEqualTo(orgId);
            return campaign;
        });

        controller.createAndDispatch(principal, request);
    }
}
