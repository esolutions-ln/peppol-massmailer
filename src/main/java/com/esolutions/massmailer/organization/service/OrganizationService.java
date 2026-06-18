package com.esolutions.massmailer.organization.service;

import com.esolutions.massmailer.billing.repository.RateProfileRepository;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.OrgUserDto;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.RegisterOrgRequest;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.RegisterOrgResponse;
import com.esolutions.massmailer.organization.exception.SlugAlreadyExistsException;
import com.esolutions.massmailer.organization.model.OrgMemberRole;
import com.esolutions.massmailer.organization.model.OrgUser;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrgUserRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.organization.service.OrgMemberService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.security.SecureRandom;

/**
 * Service for organisation self-registration.
 *
 * <p>Implements Requirements 1.1–1.8 and 12.4:
 * <ul>
 *   <li>Slug uniqueness validation (1.2)</li>
 *   <li>Cryptographically random 32-char hex API key (1.3)</li>
 *   <li>PEPPOL participant ID derivation from VAT / TIN (1.4, 1.5, 1.6)</li>
 *   <li>Default deliveryMode = EMAIL (1.8)</li>
 *   <li>Default rate profile assignment when a platform default exists (12.4)</li>
 *   <li>Persist OrgUser contact block alongside the Organisation</li>
 *   <li>Persist with status=ACTIVE and return RegisterOrgResponse (1.1, 1.7)</li>
 * </ul>
 */
@Service
public class OrganizationService {

    private static final int API_KEY_BYTES = 16; // 16 bytes → 32 hex chars

    private final OrganizationRepository orgRepo;
    private final OrgUserRepository orgUserRepo;
    private final RateProfileRepository rateProfileRepo;
    private final OrgMemberService orgMemberService;
    private final SecureRandom secureRandom;

    public OrganizationService(OrganizationRepository orgRepo,
                                OrgUserRepository orgUserRepo,
                                RateProfileRepository rateProfileRepo,
                                OrgMemberService orgMemberService) {
        this.orgRepo = orgRepo;
        this.orgUserRepo = orgUserRepo;
        this.rateProfileRepo = rateProfileRepo;
        this.orgMemberService = orgMemberService;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Registers a new organisation.
     *
     * @param request validated registration request
     * @return response containing id, slug, apiKey, peppolParticipantId, status
     * @throws SlugAlreadyExistsException if the slug is already taken (→ HTTP 409)
     */
    @Transactional
    public RegisterOrgResponse register(RegisterOrgRequest request) {
        // 1. Validate slug uniqueness (Requirement 1.2)
        String slug = request.slug().toLowerCase().trim();
        if (orgRepo.existsBySlug(slug)) {
            throw new SlugAlreadyExistsException(slug);
        }

        // 2. Generate cryptographically random 32-char hex API key (Requirement 1.3)
        String apiKey = generateApiKey();

        // 3. Derive peppolParticipantId (Requirements 1.4, 1.5, 1.6)
        String peppolParticipantId = derivePeppolParticipantId(request.vatNumber(), request.tinNumber());

        // 4. Default deliveryMode to EMAIL if not specified (Requirement 1.8)
        DeliveryMode deliveryMode = request.deliveryMode() != null ? request.deliveryMode() : DeliveryMode.EMAIL;

        // 5. Assign default rate profile if a platform default is configured (Requirement 12.4)
        //    Convention: the platform-default profile is named "Default"
        var defaultRateProfileId = rateProfileRepo.findByName("Default")
                .filter(rp -> rp.isActive())
                .map(rp -> rp.getId())
                .orElse(null);

        // 6. Build and persist Organization with status=ACTIVE (Requirement 1.1)
        Organization org = Organization.builder()
                .name(request.name())
                .slug(slug)
                .apiKey(apiKey)
                .senderEmail(request.senderEmail())
                .senderDisplayName(request.senderDisplayName())
                .companyName(request.name())
                .companyAddress(request.companyAddress())
                .accountsEmail(request.accountsEmail())
                .primaryErpSource(request.primaryErpSource())
                .erpTenantId(request.erpTenantId())
                .vatNumber(request.vatNumber())
                .tinNumber(request.tinNumber())
                .peppolParticipantId(peppolParticipantId)
                .deliveryMode(deliveryMode)
                .rateProfileId(defaultRateProfileId)
                .status(Organization.OrgStatus.ACTIVE)
                .build();

        org = orgRepo.save(org);

        // 7. Persist the OrgUser contact block (optional)
        OrgUser orgUser = null;
        if (request.user() != null) {
            orgUser = OrgUser.builder()
                    .organizationId(org.getId())
                    .firstName(request.user().firstName())
                    .lastName(request.user().lastName())
                    .jobTitle(request.user().jobTitle())
                    .email(request.user().emailAddress())
                    .build();
            orgUser = orgUserRepo.save(orgUser);

            // 7b. Bootstrap an ORG_ADMIN platform login if a password was provided.
            //     The API key (step 2) is still returned for ERP integration; this
            //     just gives the registering human a way to sign in via the UI too.
            String password = request.user().password();
            if (password != null && !password.isBlank()) {
                String displayName = (request.user().firstName() + " "
                        + request.user().lastName()).trim();
                orgMemberService.create(
                        org.getId(),
                        request.user().emailAddress(),
                        password,
                        displayName,
                        OrgMemberRole.ORG_ADMIN
                );
            }
        }

        // 8. Return RegisterOrgResponse — apiKey shown once only (Requirement 1.7)
        final OrgUser savedUser = orgUser;
        return new RegisterOrgResponse(
                org.getId(),
                org.getName(),
                org.getSlug(),
                apiKey,
                org.getPeppolParticipantId(),
                org.getDeliveryMode(),
                org.getVatNumber(),
                org.getTinNumber(),
                org.getStatus().name(),
                savedUser != null ? new OrgUserDto(
                        savedUser.getId(),
                        savedUser.getFirstName(),
                        savedUser.getLastName(),
                        savedUser.getJobTitle(),
                        savedUser.getEmail()
                ) : null
        );
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Rotates the API key for an organisation.
     * The old key is kept as {@code previousApiKey} for a 5-minute grace period.
     *
     * @param orgId organisation UUID
     * @return the new API key (shown once only)
     * @throws IllegalArgumentException if organisation not found
     */
    @Transactional
    public String rotateApiKey(UUID orgId) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + orgId));

        String newKey = generateApiKey();
        org.setPreviousApiKey(org.getApiKey());
        org.setApiKey(newKey);
        org.setApiKeyCreatedAt(Instant.now());
        orgRepo.save(org);

        return newKey;
    }

    /**
     * Full update of an organisation's editable fields. Non-null fields overwrite;
     * null fields are left unchanged. Slug uniqueness is enforced when the slug changes.
     * The PEPPOL participant ID is re-derived if VAT or TIN changes and no explicit
     * participantId was supplied.
     */
    @Transactional
    public Organization update(UUID id,
                               String name, String slug,
                               String senderEmail, String senderDisplayName,
                               String accountsEmail, String companyAddress,
                               String primaryErpSource, String erpTenantId,
                               String vatNumber, String tinNumber,
                               String peppolParticipantId,
                               DeliveryMode deliveryMode,
                               Organization.OrgStatus status) {
        Organization org = orgRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + id));

        if (slug != null && !slug.isBlank() && !slug.equalsIgnoreCase(org.getSlug())) {
            String normalized = slug.toLowerCase().trim();
            if (orgRepo.existsBySlug(normalized)) {
                throw new SlugAlreadyExistsException(normalized);
            }
            org.setSlug(normalized);
        }

        if (name != null && !name.isBlank()) org.setName(name.trim());
        if (senderEmail != null && !senderEmail.isBlank()) org.setSenderEmail(senderEmail.trim());
        if (senderDisplayName != null && !senderDisplayName.isBlank()) org.setSenderDisplayName(senderDisplayName.trim());
        if (accountsEmail != null) org.setAccountsEmail(accountsEmail.isBlank() ? null : accountsEmail.trim());
        if (companyAddress != null) org.setCompanyAddress(companyAddress.isBlank() ? null : companyAddress.trim());
        if (primaryErpSource != null) org.setPrimaryErpSource(primaryErpSource.isBlank() ? null : primaryErpSource.trim());
        if (erpTenantId != null) org.setErpTenantId(erpTenantId.isBlank() ? null : erpTenantId.trim());

        boolean taxChanged = false;
        if (vatNumber != null) { org.setVatNumber(vatNumber.isBlank() ? null : vatNumber.trim()); taxChanged = true; }
        if (tinNumber != null) { org.setTinNumber(tinNumber.isBlank() ? null : tinNumber.trim()); taxChanged = true; }

        if (peppolParticipantId != null && !peppolParticipantId.isBlank()) {
            org.setPeppolParticipantId(peppolParticipantId.trim());
        } else if (taxChanged) {
            org.setPeppolParticipantId(derivePeppolParticipantId(org.getVatNumber(), org.getTinNumber()));
        }

        if (deliveryMode != null) org.setDeliveryMode(deliveryMode);
        if (status != null) {
            org.setStatus(status);
            if (status == Organization.OrgStatus.DEACTIVATED || status == Organization.OrgStatus.SUSPENDED) {
                org.setSuspendedAt(Instant.now());
            } else if (status == Organization.OrgStatus.ACTIVE) {
                org.setSuspendedAt(null);
            }
        }

        return orgRepo.save(org);
    }

    /**
     * Soft-deletes an organisation by flipping its status to DEACTIVATED.
     * Dependent records (customers, campaigns, usage) are retained for audit.
     */
    @Transactional
    public Organization deactivate(UUID id) {
        Organization org = orgRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + id));
        org.setStatus(Organization.OrgStatus.DEACTIVATED);
        org.setSuspendedAt(Instant.now());
        return orgRepo.save(org);
    }

    /**
     * Generates a 32-character lowercase hex string using SecureRandom.
     * 16 random bytes → 32 hex characters.
     */
    public String generateApiKey() {
        byte[] bytes = new byte[API_KEY_BYTES];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Derives the PEPPOL participant ID from VAT or TIN number.
     *
     * <ul>
     *   <li>Non-null vatNumber → {@code 0190:ZW{vatNumber}}</li>
     *   <li>Null vatNumber + non-null tinNumber → {@code 0190:ZW{tinNumber}}</li>
     *   <li>Both null → {@code null}</li>
     * </ul>
     */
    String derivePeppolParticipantId(String vatNumber, String tinNumber) {
        if (vatNumber != null && !vatNumber.isBlank()) {
            return "0190:ZW" + vatNumber.trim();
        }
        if (tinNumber != null && !tinNumber.isBlank()) {
            return "0190:ZW" + tinNumber.trim();
        }
        return null;
    }
}
