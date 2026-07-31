package com.esolutions.massmailer.organization.service;

import com.esolutions.massmailer.organization.dto.OrganizationDtos.RegisterOrgRequest;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrgUserRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for API key rotation.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceRotationTest {

    @Mock OrganizationRepository orgRepo;
    @Mock OrgUserRepository orgUserRepo;
    @InjectMocks OrganizationService orgService;

    @Test
    void rotateApiKeyGeneratesNewKeyAndPreservesOldKey() {
        String oldKey = "old-key-1234567890123456789012345678";
        var org = Organization.builder()
                .id(java.util.UUID.randomUUID())
                .name("Test Org")
                .slug("test-org")
                .apiKey(oldKey)
                .senderEmail("test@test.com")
                .senderDisplayName("Test")
                .build();

        when(orgRepo.findById(org.getId())).thenReturn(Optional.of(org));
        when(orgRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String newKey = orgService.rotateApiKey(org.getId());

        assertThat(newKey).isNotNull().hasSize(32);
        assertThat(newKey).isNotEqualTo(oldKey);
        assertThat(org.getApiKey()).isEqualTo(newKey);
        assertThat(org.getPreviousApiKey()).isEqualTo(oldKey);
        assertThat(org.getApiKeyCreatedAt()).isNotNull();
    }

    @Test
    void generatedApiKeyIsHexString() {
        String key = orgService.generateApiKey();
        assertThat(key).matches("^[0-9a-f]{32}$");
    }
}
