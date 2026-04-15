package com.esolutions.massmailer.invitation.controller;

import com.esolutions.massmailer.invitation.service.InvitationService;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.security.ApiKeyAuthFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.jqwik.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Property-based tests for {@link InvitationController} — HTTP security layer.
 *
 * <p><b>Property 18: Management Endpoints Require Authentication</b>
 *
 * <p>For any request to the invitation management endpoints without a valid
 * {@code X-API-Key} header, the system must return a 401 or 403 response.
 *
 * <pre>
 * ∀ request to {POST /api/v1/my/invitations, GET /api/v1/my/invitations,
 *               DELETE /api/v1/my/invitations/{id}}
 *   WHERE X-API-Key is absent or invalid:
 *   response.status ∈ {401, 403}
 * </pre>
 *
 * <p><b>Validates: Requirements 1.6, 5.5</b>
 *
 * <p>Uses a standalone MockMvc setup with the production {@link ApiKeyAuthFilter}
 * and a role-enforcement filter that mirrors the production {@code SecurityConfig}
 * rule: {@code /api/v1/my/**} requires {@code ROLE_ORG}.
 */
// Feature: peppol-customer-invitation, Property 18: Management Endpoints Require Authentication
class InvitationControllerPropertyTest {

    /**
     * Builds a standalone MockMvc with:
     * <ol>
     *   <li>The production {@link ApiKeyAuthFilter} (no valid keys → no auth set)</li>
     *   <li>A role-enforcement filter that returns 401 for unauthenticated requests
     *       to {@code /api/v1/my/**}</li>
     * </ol>
     */
    private MockMvc buildMockMvc() {
        InvitationService mockService = mock(InvitationService.class);
        InvitationController controller = new InvitationController(mockService);

        // No API key is valid — every lookup returns empty
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findByApiKey(anyString())).thenReturn(Optional.empty());

        com.esolutions.massmailer.security.repository.AdminSessionTokenRepository adminSessionTokenRepository =
                mock(com.esolutions.massmailer.security.repository.AdminSessionTokenRepository.class);
        when(adminSessionTokenRepository.findByTokenAndExpiresAtAfter(anyString(), any()))
                .thenReturn(java.util.Optional.empty());

        ApiKeyAuthFilter apiKeyFilter = new ApiKeyAuthFilter(orgRepo, adminSessionTokenRepository);

        // Role-enforcement filter: mirrors SecurityConfig rule for /api/v1/my/**
        Filter roleEnforcementFilter = new OrgRoleEnforcementFilter();

        return MockMvcBuilders
                .standaloneSetup(controller)
                .addFilters(apiKeyFilter, roleEnforcementFilter)
                .build();
    }

    /**
     * Enforces {@code ROLE_ORG} on {@code /api/v1/my/**}, mirroring the production
     * {@code SecurityConfig} rule. Returns HTTP 401 if the security context does not
     * contain an authenticated principal with {@code ROLE_ORG}.
     */
    static class OrgRoleEnforcementFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response,
                             FilterChain chain) throws IOException, ServletException {
            HttpServletRequest httpReq = (HttpServletRequest) request;
            HttpServletResponse httpResp = (HttpServletResponse) response;

            String path = httpReq.getRequestURI();
            if (path.startsWith("/api/v1/my/")) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                boolean hasOrgRole = auth != null
                        && auth.isAuthenticated()
                        && auth.getAuthorities().stream()
                                .anyMatch(a -> a.getAuthority().equals("ROLE_ORG"));

                if (!hasOrgRole) {
                    httpResp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 18: Management Endpoints Require Authentication
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 18: Management Endpoints Require Authentication</b>
     *
     * <p>For any request to the management endpoints without a valid {@code X-API-Key}
     * header, the system must return a 401 or 403 response.
     *
     * <p><b>Validates: Requirements 1.6, 5.5</b>
     */
    @Property(tries = 200)
    void managementEndpointsRequireAuth(
            @ForAll("managementEndpointRequests") ManagementRequest request) throws Exception {

        MockMvc mockMvc = buildMockMvc();

        var builder = switch (request.method()) {
            case "POST" -> post(request.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"customerEmail\":\"test@example.com\"}");
            case "GET" -> get(request.path());
            case "DELETE" -> delete(request.path());
            default -> throw new IllegalArgumentException("Unknown method: " + request.method());
        };

        // No X-API-Key header — request is unauthenticated
        int status = mockMvc.perform(builder)
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status)
                .as("Unauthenticated request to %s %s must return 401 or 403",
                        request.method(), request.path())
                .isIn(401, 403);
    }

    /**
     * Generates management endpoint requests (method + path combinations).
     * Covers all three management endpoints, with random UUIDs for the DELETE path.
     * Uses string-based UUID generation to ensure jqwik uses random (not exhaustive) mode.
     */
    @Provide
    Arbitrary<ManagementRequest> managementEndpointRequests() {
        // Generate random UUID-like strings for the DELETE path to force random generation mode
        Arbitrary<String> uuidStrings = Arbitraries.strings()
                .withCharRange('a', 'f')
                .ofLength(8)
                .map(s -> s + "-" + UUID.randomUUID().toString().substring(9));

        Arbitrary<ManagementRequest> postInvitations =
                Arbitraries.just(new ManagementRequest("POST", "/api/v1/my/invitations"));

        Arbitrary<ManagementRequest> getInvitations =
                Arbitraries.just(new ManagementRequest("GET", "/api/v1/my/invitations"));

        Arbitrary<ManagementRequest> deleteInvitation =
                uuidStrings.map(id -> new ManagementRequest("DELETE",
                        "/api/v1/my/invitations/" + id));

        return Arbitraries.oneOf(postInvitations, getInvitations, deleteInvitation);
    }

    /**
     * Simple value type representing an HTTP method + path pair.
     */
    record ManagementRequest(String method, String path) {}
}
