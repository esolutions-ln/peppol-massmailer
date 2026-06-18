package com.esolutions.massmailer.controller;

import com.esolutions.massmailer.model.EmailTemplate;
import com.esolutions.massmailer.repository.EmailTemplateRepository;
import com.esolutions.massmailer.security.OrgPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for the authenticated org's customisable email body templates.
 * Templates are used by SingleMailController when sending invoices —
 * an explicit templateId on the request, or the org's default, will be applied.
 */
@RestController
@RequestMapping("/api/v1/my/email-templates")
@Tag(name = "Email Templates")
@SecurityRequirement(name = "ApiKeyAuth")
public class EmailTemplateController {

    private final EmailTemplateRepository repo;

    public EmailTemplateController(EmailTemplateRepository repo) {
        this.repo = repo;
    }

    public record TemplateDto(
            UUID id,
            String name,
            String subject,
            String body,
            boolean isDefault,
            String createdAt,
            String updatedAt
    ) {
        static TemplateDto from(EmailTemplate t) {
            return new TemplateDto(
                    t.getId(), t.getName(), t.getSubject(), t.getBody(),
                    t.isDefault(),
                    t.getCreatedAt() != null ? t.getCreatedAt().toString() : null,
                    t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null
            );
        }
    }

    public record TemplateUpsertRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 300) String subject,
            @NotBlank String body,
            Boolean isDefault
    ) {}

    @Operation(summary = "List email templates for the authenticated organisation")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<TemplateDto>> list(@AuthenticationPrincipal OrgPrincipal principal) {
        var orgId = principal.org().getId();
        var items = repo.findByOrganizationIdOrderByCreatedAtDesc(orgId)
                .stream().map(TemplateDto::from).toList();
        return ResponseEntity.ok(items);
    }

    @Operation(summary = "Create a new email template")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<TemplateDto> create(
            @AuthenticationPrincipal OrgPrincipal principal,
            @Valid @RequestBody TemplateUpsertRequest req) {
        var orgId = principal.org().getId();
        boolean wantDefault = Boolean.TRUE.equals(req.isDefault());
        if (wantDefault) clearExistingDefault(orgId);

        var t = EmailTemplate.builder()
                .organizationId(orgId)
                .name(req.name())
                .subject(req.subject())
                .body(req.body())
                .isDefault(wantDefault)
                .build();
        repo.save(t);
        return ResponseEntity.ok(TemplateDto.from(t));
    }

    @Operation(summary = "Update an email template")
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<TemplateDto> update(
            @AuthenticationPrincipal OrgPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody TemplateUpsertRequest req) {
        var orgId = principal.org().getId();
        var t = repo.findByIdAndOrganizationId(id, orgId).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();

        boolean wantDefault = Boolean.TRUE.equals(req.isDefault());
        if (wantDefault && !t.isDefault()) clearExistingDefault(orgId);

        t.setName(req.name());
        t.setSubject(req.subject());
        t.setBody(req.body());
        t.setDefault(wantDefault);
        t.setUpdatedAt(Instant.now());
        repo.save(t);
        return ResponseEntity.ok(TemplateDto.from(t));
    }

    @Operation(summary = "Delete an email template")
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal OrgPrincipal principal,
            @PathVariable UUID id) {
        var orgId = principal.org().getId();
        var t = repo.findByIdAndOrganizationId(id, orgId).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();
        repo.delete(t);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark a template as the org default (unsets any other default)")
    @PostMapping(value = "/{id}/set-default", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<TemplateDto> setDefault(
            @AuthenticationPrincipal OrgPrincipal principal,
            @PathVariable UUID id) {
        var orgId = principal.org().getId();
        var t = repo.findByIdAndOrganizationId(id, orgId).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();
        clearExistingDefault(orgId);
        t.setDefault(true);
        t.setUpdatedAt(Instant.now());
        repo.save(t);
        return ResponseEntity.ok(TemplateDto.from(t));
    }

    private void clearExistingDefault(UUID orgId) {
        repo.findFirstByOrganizationIdAndIsDefaultTrue(orgId).ifPresent(existing -> {
            existing.setDefault(false);
            existing.setUpdatedAt(Instant.now());
            repo.save(existing);
        });
    }
}
