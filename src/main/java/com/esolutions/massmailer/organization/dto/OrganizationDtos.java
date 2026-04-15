package com.esolutions.massmailer.organization.dto;

import com.esolutions.massmailer.model.DeliveryMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * DTOs for the Organisation self-registration API.
 */
public class OrganizationDtos {

    /**
     * The primary contact user registering the organisation.
     * Mirrors the Sage Intacct AccessPointConnection {@code user} block.
     */
    public record OrgUserRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            String jobTitle,
            @NotBlank @Email String emailAddress
    ) {}

    /** Read-only view of the org contact — returned in responses. */
    public record OrgUserDto(
            UUID id,
            String firstName,
            String lastName,
            String jobTitle,
            String emailAddress
    ) {}

    /**
     * Request body for POST /api/v1/organizations.
     *
     * <ul>
     *   <li>{@code user} — the primary contact registering this organisation (required)</li>
     *   <li>{@code vatNumber} — Zimbabwe VAT number; derives peppolParticipantId as {@code 0190:ZW{vatNumber}}</li>
     *   <li>{@code tinNumber} — Zimbabwe TIN; used as fallback when vatNumber is null</li>
     *   <li>{@code deliveryMode} — defaults to {@code EMAIL} when omitted</li>
     * </ul>
     */
    public record RegisterOrgRequest(
            @Valid OrgUserRequest user,
            @NotBlank String name,
            @NotBlank String slug,
            @NotBlank @Email String senderEmail,
            @NotBlank String senderDisplayName,
            String accountsEmail,
            String companyAddress,
            String primaryErpSource,
            String erpTenantId,
            String vatNumber,
            String tinNumber,
            DeliveryMode deliveryMode
    ) {}

    /**
     * Response body returned on successful registration (HTTP 201).
     * The {@code apiKey} is shown exactly once — callers must persist it.
     */
    public record RegisterOrgResponse(
            UUID id,
            String name,
            String slug,
            String apiKey,
            String peppolParticipantId,
            DeliveryMode deliveryMode,
            String vatNumber,
            String tinNumber,
            String status,
            OrgUserDto user
    ) {}
}
