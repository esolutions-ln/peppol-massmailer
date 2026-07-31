package com.esolutions.massmailer.organization.controller;

import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.OrgUserDto;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.RegisterOrgRequest;
import com.esolutions.massmailer.organization.dto.OrganizationDtos.RegisterOrgResponse;
import com.esolutions.massmailer.organization.exception.SlugAlreadyExistsException;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrgUserRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.organization.service.OrganizationService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for Organisation self-registration and management.
 *
 * <p>Implements Requirements 1.1, 1.2, 1.7, 1.9, 15.4:
 * <ul>
 *   <li>POST /api/v1/organizations — public, rate-limited (10 req/IP/hour)</li>
 *   <li>GET  /api/v1/organizations/by-slug/{slug} — requires X-API-Key auth (Req 15.4)</li>
 *   <li>GET  /api/v1/organizations/{id} — admin endpoint, no apiKey in response</li>
 *   <li>PATCH /api/v1/organizations/{id}/rate-profile</li>
 *   <li>PATCH /api/v1/organizations/{id}/delivery-mode</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organization Registry")
public class OrganizationController {

    // ── Rate limiting: configurable POST requests per IP per hour (Requirement 1.9) ──
    private final ConcurrentHashMap<String, Bucket> rateLimitBuckets = new ConcurrentHashMap<>();

    @Value("${massmailer.registration-rate-limit:10}")
    private int registrationRateLimit;

    private final OrganizationService orgService;
    private final OrganizationRepository orgRepo;
    private final OrgUserRepository orgUserRepo;

    public OrganizationController(OrganizationService orgService,
                                   OrganizationRepository orgRepo,
                                   OrgUserRepository orgUserRepo) {
        this.orgService = orgService;
        this.orgRepo = orgRepo;
        this.orgUserRepo = orgUserRepo;
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * Summary view of an Organisation — never includes the apiKey (Requirement 1.7).
     */
    public record OrgSummaryDto(
            UUID id,
            String name,
            String slug,
            String senderEmail,
            String senderDisplayName,
            String accountsEmail,
            String companyAddress,
            String primaryErpSource,
            String erpTenantId,
            String vatNumber,
            String tinNumber,
            String peppolParticipantId,
            DeliveryMode deliveryMode,
            UUID rateProfileId,
            String status,
            OrgUserDto user
    ) {}

    public record UpdateRateProfileRequest(@NotNull UUID rateProfileId) {}

    public record UpdateDeliveryModeRequest(@NotNull DeliveryMode deliveryMode) {}

    /** Full-update payload — every field is optional; nulls leave the existing value untouched. */
    public record UpdateOrgRequest(
            String name,
            String slug,
            String senderEmail,
            String senderDisplayName,
            String accountsEmail,
            String companyAddress,
            String primaryErpSource,
            String erpTenantId,
            String vatNumber,
            String tinNumber,
            String peppolParticipantId,
            DeliveryMode deliveryMode,
            Organization.OrgStatus status
    ) {}

    // ── POST /api/v1/organizations ──────────────────────────────────────────

    @Operation(
            summary = "Register a new organisation",
            description = """
                    Self-service registration. Returns HTTP 201 with the organisation ID, slug,
                    API key (shown once only), and derived PEPPOL participant ID.

                    Rate limited to 10 requests per IP per hour (HTTP 429 on breach).
                    Returns HTTP 409 if the slug is already taken.
                    """
    )
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> register(@Valid @RequestBody RegisterOrgRequest request,
                                      HttpServletRequest httpRequest) {
        // Rate limit by client IP (Requirement 1.9)
        String clientIp = resolveClientIp(httpRequest);
        Bucket bucket = rateLimitBuckets.computeIfAbsent(clientIp, this::newBucket);
        if (!bucket.tryConsume(1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded. Maximum 10 registrations per IP per hour."));
        }

        try {
            RegisterOrgResponse response = orgService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (SlugAlreadyExistsException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Slug already registered: " + ex.getSlug()));
        }
    }

    // ── GET /api/v1/organizations/by-slug/{slug} ────────────────────────────

    @Operation(
            summary = "Look up an organisation by slug",
            description = """
                    Returns the organisation summary — never includes the API key.
                    """
    )
    @GetMapping(value = "/by-slug/{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getBySlug(@PathVariable String slug) {
        return orgRepo.findBySlug(slug)
                .map(org -> ResponseEntity.ok(toSummary(org)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── GET /api/v1/organizations ───────────────────────────────────────────

    @Operation(
            summary = "List organisations (admin)",
            description = "Returns all organisations as summaries. The apiKey is never included (Requirement 1.7). Admin only."
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir) {
        if (!isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // Backward-compat: no pagination params -> return flat array as before.
        if (page == null && size == null && (search == null || search.isBlank())) {
            var summaries = orgRepo.findAll().stream().map(this::toSummary).toList();
            return ResponseEntity.ok(summaries);
        }
        int p = page != null ? Math.max(page, 0) : 0;
        int s = size != null ? Math.min(Math.max(size, 1), 200) : 20;
        String sortBy = (sort != null && !sort.isBlank()) ? sort : "name";
        Sort.Direction direction = "desc".equalsIgnoreCase(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        var pageable = PageRequest.of(p, s, Sort.by(direction, sortBy));
        var result = orgRepo.search(search, pageable);
        return ResponseEntity.ok(Map.of(
                "content", result.getContent().stream().map(this::toSummary).toList(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages()
        ));
    }

    // ── GET /api/v1/organizations/{id} ──────────────────────────────────────

    @Operation(
            summary = "Get organisation by ID (admin)",
            description = "Returns organisation details. The apiKey is never included in the response (Requirement 1.7). Admin only."
    )
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrgSummaryDto> getById(@PathVariable UUID id) {
        if (!isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return orgRepo.findById(id)
                .map(org -> ResponseEntity.ok(toSummary(org)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── PATCH /api/v1/organizations/{id}/rate-profile ───────────────────────

    @Operation(summary = "Update the rate profile for an organisation")
    @PatchMapping(value = "/{id}/rate-profile",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateRateProfile(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateRateProfileRequest body) {
        Organization org = orgRepo.findById(id).orElse(null);
        if (org == null) return ResponseEntity.notFound().build();
        org.setRateProfileId(body.rateProfileId());
        orgRepo.save(org);
        return ResponseEntity.ok(toSummary(org));
    }

    // ── PATCH /api/v1/organizations/{id}/delivery-mode ──────────────────────

    @Operation(summary = "Update the delivery mode for an organisation")
    @PatchMapping(value = "/{id}/delivery-mode",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateDeliveryMode(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateDeliveryModeRequest body) {
        Organization org = orgRepo.findById(id).orElse(null);
        if (org == null) return ResponseEntity.notFound().build();
        org.setDeliveryMode(body.deliveryMode());
        orgRepo.save(org);
        return ResponseEntity.ok(toSummary(org));
    }

    // ── PUT /api/v1/organizations/{id} ──────────────────────────────────────

    @Operation(summary = "Full update of an organisation (admin)",
            description = "Updates any subset of editable fields. Slug changes are checked for uniqueness; if VAT/TIN change without an explicit peppolParticipantId, the participant ID is re-derived.")
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> update(@PathVariable UUID id,
                                    @Valid @RequestBody UpdateOrgRequest body) {
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            Organization updated = orgService.update(id,
                    body.name(), body.slug(),
                    body.senderEmail(), body.senderDisplayName(),
                    body.accountsEmail(), body.companyAddress(),
                    body.primaryErpSource(), body.erpTenantId(),
                    body.vatNumber(), body.tinNumber(),
                    body.peppolParticipantId(),
                    body.deliveryMode(), body.status());
            return ResponseEntity.ok(toSummary(updated));
        } catch (SlugAlreadyExistsException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Slug already taken: " + ex.getSlug()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── DELETE /api/v1/organizations/{id} ───────────────────────────────────

    @Operation(summary = "Deactivate an organisation (admin)",
            description = "Soft delete: flips status to DEACTIVATED. Dependent records are retained for audit and the org can be reactivated via the update endpoint.")
    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deactivate(@PathVariable UUID id) {
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            Organization deactivated = orgService.deactivate(id);
            return ResponseEntity.ok(toSummary(deactivated));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Maps an Organization to a summary DTO — apiKey is intentionally excluded. */
    private OrgSummaryDto toSummary(Organization o) {
        OrgUserDto userDto = orgUserRepo.findByOrganizationId(o.getId())
                .map(u -> new OrgUserDto(u.getId(), u.getFirstName(), u.getLastName(), u.getJobTitle(), u.getEmail()))
                .orElse(null);
        return new OrgSummaryDto(
                o.getId(),
                o.getName(),
                o.getSlug(),
                o.getSenderEmail(),
                o.getSenderDisplayName(),
                o.getAccountsEmail(),
                o.getCompanyAddress(),
                o.getPrimaryErpSource(),
                o.getErpTenantId(),
                o.getVatNumber(),
                o.getTinNumber(),
                o.getPeppolParticipantId(),
                o.getDeliveryMode(),
                o.getRateProfileId(),
                o.getStatus().name(),
                userDto
        );
    }

    /** Creates a new Bucket4j bucket with configurable capacity, refilled every hour. */
    private Bucket newBucket(String ip) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(registrationRateLimit)
                .refillGreedy(registrationRateLimit, Duration.ofHours(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /** Resolves the real client IP, honouring X-Forwarded-For if present. */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN"));
    }
}
