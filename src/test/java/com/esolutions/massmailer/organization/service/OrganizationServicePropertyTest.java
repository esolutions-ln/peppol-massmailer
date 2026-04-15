package com.esolutions.massmailer.organization.service;

import com.esolutions.massmailer.billing.repository.RateProfileRepository;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.OrgUserRequest;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.RegisterOrgRequest;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.RegisterOrgResponse;
import com.esolutions.massmailer.organization.model.OrgUser;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrgUserRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.WithNull;
import org.mockito.Mockito;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for {@link OrganizationService}.
 *
 * <p>Property 5: API Key Uniqueness — Validates: Requirements 1.3, 15.3
 * <p>Property 6: Participant ID Derivation Consistency — Validates: Requirements 1.4, 1.5
 */
class OrganizationServicePropertyTest {

    /**
     * Property 5: API Key Uniqueness
     *
     * Generate N organisations via OrganizationService.register() with distinct slugs;
     * assert all API keys are distinct.
     *
     * <p><b>Validates: Requirements 1.3, 15.3</b>
     */
    @Property(tries = 50)
    void apiKeysAreUniqueAcrossRegistrations(
            @ForAll("distinctSlugLists") List<String> slugs) {

        // Arrange: in-memory slug registry to simulate uniqueness check
        Set<String> registeredSlugs = new HashSet<>();
        Map<UUID, Organization> savedOrgs = new HashMap<>();

        OrganizationRepository mockRepo = Mockito.mock(OrganizationRepository.class);
        RateProfileRepository mockRateRepo = Mockito.mock(RateProfileRepository.class);
        OrgUserRepository mockOrgUserRepo = Mockito.mock(OrgUserRepository.class);

        when(mockRepo.existsBySlug(anyString()))
                .thenAnswer(inv -> registeredSlugs.contains(inv.getArgument(0, String.class)));

        when(mockRepo.save(any(Organization.class)))
                .thenAnswer(inv -> {
                    Organization org = inv.getArgument(0, Organization.class);
                    Organization saved = Organization.builder()
                            .id(UUID.randomUUID())
                            .name(org.getName())
                            .slug(org.getSlug())
                            .apiKey(org.getApiKey())
                            .senderEmail(org.getSenderEmail())
                            .senderDisplayName(org.getSenderDisplayName())
                            .deliveryMode(org.getDeliveryMode())
                            .status(Organization.OrgStatus.ACTIVE)
                            .build();
                    registeredSlugs.add(saved.getSlug());
                    savedOrgs.put(saved.getId(), saved);
                    return saved;
                });

        when(mockOrgUserRepo.save(any(OrgUser.class)))
                .thenAnswer(inv -> {
                    OrgUser u = inv.getArgument(0, OrgUser.class);
                    return OrgUser.builder()
                            .id(UUID.randomUUID())
                            .organizationId(u.getOrganizationId())
                            .firstName(u.getFirstName())
                            .lastName(u.getLastName())
                            .jobTitle(u.getJobTitle())
                            .email(u.getEmail())
                            .build();
                });

        when(mockRateRepo.findByName(anyString())).thenReturn(Optional.empty());

        OrganizationService service = new OrganizationService(mockRepo, mockOrgUserRepo, mockRateRepo);

        // Act: register one org per slug
        List<String> collectedApiKeys = new ArrayList<>();
        OrgUserRequest userReq = new OrgUserRequest("Jane", "Doe", "CFO", "jane@test.com");
        for (String slug : slugs) {
            RegisterOrgRequest request = new RegisterOrgRequest(
                    userReq,
                    "Org " + slug,
                    slug,
                    "sender@" + slug + ".com",
                    "Sender " + slug,
                    null, null, null, null, null, null, null
            );
            RegisterOrgResponse response = service.register(request);
            collectedApiKeys.add(response.apiKey());
        }

        // Assert: all API keys are distinct (Property 5)
        assertThat(collectedApiKeys)
                .as("API keys must be unique across all registered organisations")
                .doesNotHaveDuplicates();

        // Also assert each key is exactly 32 hex characters (Requirement 1.3)
        for (String apiKey : collectedApiKeys) {
            assertThat(apiKey)
                    .as("API key must be a 32-character hex string")
                    .matches("[0-9a-f]{32}");
        }
    }

    /**
     * Provides lists of 10–50 distinct lowercase slug strings.
     * Each slug is URL-safe (lowercase letters and digits only).
     */
    @Provide
    Arbitrary<List<String>> distinctSlugLists() {
        Arbitrary<String> slugArbitrary = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(4)
                .ofMaxLength(12);

        return slugArbitrary
                .list()
                .ofMinSize(10)
                .ofMaxSize(50)
                .map(list -> list.stream()
                        .distinct()
                        .collect(Collectors.toList()))
                .filter(list -> list.size() >= 10);
    }

    // ── Property 6: Participant ID Derivation Consistency ────────────────────

    /**
     * Property 6a: Non-blank vatNumber → peppolParticipantId = "0190:ZW" + vatNumber.trim()
     *
     * <p><b>Validates: Requirements 1.4</b>
     */
    @Property(tries = 200)
    void nonBlankVatNumberDeterminesParticipantId(
            @ForAll("nonBlankStrings") String vatNumber,
            @ForAll("nullOrBlankStrings") String tinNumber) {

        OrganizationService service = buildServiceWithNoConflicts();

        String result = service.derivePeppolParticipantId(vatNumber, tinNumber);

        assertThat(result)
                .as("peppolParticipantId must be '0190:ZW' + vatNumber.trim() when vatNumber is non-blank")
                .isEqualTo("0190:ZW" + vatNumber.trim());
    }

    /**
     * Property 6b: null vatNumber + non-blank tinNumber → peppolParticipantId = "0190:ZW" + tinNumber.trim()
     *
     * <p><b>Validates: Requirements 1.5</b>
     */
    @Property(tries = 200)
    void nullVatWithNonBlankTinDeterminesParticipantId(
            @ForAll("nonBlankStrings") String tinNumber) {

        OrganizationService service = buildServiceWithNoConflicts();

        String result = service.derivePeppolParticipantId(null, tinNumber);

        assertThat(result)
                .as("peppolParticipantId must be '0190:ZW' + tinNumber.trim() when vatNumber is null and tinNumber is non-blank")
                .isEqualTo("0190:ZW" + tinNumber.trim());
    }

    /**
     * Property 6c: Both vatNumber and tinNumber null/blank → peppolParticipantId is null.
     *
     * <p><b>Validates: Requirements 1.4, 1.5</b>
     */
    @Property(tries = 100)
    void bothNullOrBlankYieldsNullParticipantId(
            @ForAll("nullOrBlankStrings") String vatNumber,
            @ForAll("nullOrBlankStrings") String tinNumber) {

        OrganizationService service = buildServiceWithNoConflicts();

        String result = service.derivePeppolParticipantId(vatNumber, tinNumber);

        assertThat(result)
                .as("peppolParticipantId must be null when both vatNumber and tinNumber are null/blank")
                .isNull();
    }

    /** Generates non-blank strings (may contain whitespace, but not all-whitespace). */
    @Provide
    Arbitrary<String> nonBlankStrings() {
        // Generate a non-blank core, optionally surrounded by spaces
        Arbitrary<String> core = Arbitraries.strings()
                .withCharRange('A', 'Z')
                .ofMinLength(1)
                .ofMaxLength(20);
        // Optionally pad with leading/trailing spaces to test trim() behaviour
        Arbitrary<String> spaces = Arbitraries.strings()
                .withChars(' ')
                .ofMinLength(0)
                .ofMaxLength(3);
        return Combinators.combine(spaces, core, spaces)
                .as((pre, mid, post) -> pre + mid + post);
    }

    /** Generates null or all-whitespace strings. */
    @Provide
    Arbitrary<String> nullOrBlankStrings() {
        Arbitrary<String> blank = Arbitraries.strings()
                .withChars(' ', '\t')
                .ofMinLength(0)
                .ofMaxLength(5);
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                blank
        );
    }

    /** Builds an {@link OrganizationService} backed by mocks that never conflict. */
    private OrganizationService buildServiceWithNoConflicts() {
        OrganizationRepository mockRepo = Mockito.mock(OrganizationRepository.class);
        OrgUserRepository mockOrgUserRepo = Mockito.mock(OrgUserRepository.class);
        RateProfileRepository mockRateRepo = Mockito.mock(RateProfileRepository.class);
        when(mockRepo.existsBySlug(anyString())).thenReturn(false);
        when(mockRepo.save(any(Organization.class))).thenAnswer(inv -> {
            Organization org = inv.getArgument(0, Organization.class);
            return Organization.builder()
                    .id(UUID.randomUUID())
                    .name(org.getName())
                    .slug(org.getSlug())
                    .apiKey(org.getApiKey())
                    .peppolParticipantId(org.getPeppolParticipantId())
                    .deliveryMode(org.getDeliveryMode())
                    .status(Organization.OrgStatus.ACTIVE)
                    .build();
        });
        when(mockOrgUserRepo.save(any(OrgUser.class))).thenAnswer(inv -> {
            OrgUser u = inv.getArgument(0, OrgUser.class);
            return OrgUser.builder().id(UUID.randomUUID())
                    .organizationId(u.getOrganizationId())
                    .firstName(u.getFirstName()).lastName(u.getLastName())
                    .jobTitle(u.getJobTitle()).email(u.getEmail()).build();
        });
        when(mockRateRepo.findByName(anyString())).thenReturn(Optional.empty());
        return new OrganizationService(mockRepo, mockOrgUserRepo, mockRateRepo);
    }
}
