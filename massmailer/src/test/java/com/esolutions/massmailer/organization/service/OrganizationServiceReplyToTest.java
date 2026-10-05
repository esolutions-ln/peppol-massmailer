package com.esolutions.massmailer.organization.service;

import com.esolutions.massmailer.billing.repository.RateProfileRepository;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.RegisterOrgRequest;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrgUserRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** replyToEmail is persisted on registration and editable via update (blank clears it). */
class OrganizationServiceReplyToTest {

    private OrganizationRepository orgRepo;
    private OrganizationService service;

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            return o;
        });
        service = new OrganizationService(orgRepo, mock(OrgUserRepository.class),
                mock(RateProfileRepository.class), mock(OrgMemberService.class));
    }

    @Test
    void registerPersistsReplyToEmail() {
        service.register(new RegisterOrgRequest(null, "Acme", "acme",
                "noreply@acme.test", "Acme", "accounts@acme.test", "replies@acme.test",
                null, null, null, null, null, null));

        var captor = ArgumentCaptor.forClass(Organization.class);
        verify(orgRepo).save(captor.capture());
        assertThat(captor.getValue().getReplyToEmail()).isEqualTo("replies@acme.test");
    }

    @Test
    void updateSetsTrimsAndClearsReplyToEmail() {
        UUID id = UUID.randomUUID();
        Organization org = Organization.builder().id(id).slug("acme")
                .senderEmail("noreply@acme.test").senderDisplayName("Acme").build();
        when(orgRepo.findById(id)).thenReturn(Optional.of(org));

        service.update(id, null, null, null, null, null, "  replies@acme.test ",
                null, null, null, null, null, null, null, null);
        assertThat(org.getReplyToEmail()).isEqualTo("replies@acme.test");

        // null leaves it untouched
        service.update(id, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        assertThat(org.getReplyToEmail()).isEqualTo("replies@acme.test");

        // blank clears it, so Reply-To falls back to accountsEmail / senderEmail
        service.update(id, null, null, null, null, null, "",
                null, null, null, null, null, null, null, null);
        assertThat(org.getReplyToEmail()).isNull();
    }
}
