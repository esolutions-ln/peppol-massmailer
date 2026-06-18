package com.esolutions.massmailer.security;

import com.esolutions.massmailer.organization.model.OrgMember;
import com.esolutions.massmailer.organization.repository.OrgSessionTokenRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Authenticates requests using the X-API-Key header.
 *
 * Lookup order:
 * 1. Check {@code admin_session_tokens} table for a valid, non-expired token → grant ROLE_ADMIN
 * 2. Check {@code org_session_tokens} table for a valid, non-expired token → grant
 *    ROLE_ORG (for ORG_ADMIN members) or ROLE_ORG_VIEWER (for ORG_VIEWER members)
 * 3. Check {@code organization.apiKey} → grant ROLE_ORG (integration credential)
 * 4. No match → 401
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final OrganizationRepository orgRepo;
    private final AdminSessionTokenRepository adminSessionTokenRepository;
    private final OrgSessionTokenRepository orgSessionTokenRepository;

    public ApiKeyAuthFilter(OrganizationRepository orgRepo,
                            AdminSessionTokenRepository adminSessionTokenRepository,
                            OrgSessionTokenRepository orgSessionTokenRepository) {
        this.orgRepo = orgRepo;
        this.adminSessionTokenRepository = adminSessionTokenRepository;
        this.orgSessionTokenRepository = orgSessionTokenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey != null && !apiKey.isBlank()) {
            String hashed = AdminTokens.hashToken(apiKey);

            // 1. Admin session tokens
            boolean adminAuthenticated = adminSessionTokenRepository
                    .findByTokenAndExpiresAtAfter(hashed, Instant.now())
                    .filter(t -> t.getAdminUser() != null && t.getAdminUser().isActive())
                    .map(t -> {
                        var auth = new UsernamePasswordAuthenticationToken(
                                t.getAdminUser().getUsername(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                        );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        return true;
                    })
                    .orElse(false);

            // 2. Org member session tokens
            if (!adminAuthenticated) {
                orgSessionTokenRepository.findByTokenAndExpiresAtAfter(hashed, Instant.now())
                        .filter(t -> t.getOrgMember() != null && t.getOrgMember().isActive())
                        .ifPresent(t -> {
                            OrgMember member = t.getOrgMember();
                            orgRepo.findById(member.getOrganizationId())
                                    .filter(org -> org.isActive())
                                    .ifPresent(org -> {
                                        var principal = new OrgPrincipal(org, member);
                                        String role = switch (member.getRole()) {
                                            case ORG_ADMIN -> "ROLE_ORG";
                                            case ORG_VIEWER -> "ROLE_ORG_VIEWER";
                                        };
                                        var auth = new UsernamePasswordAuthenticationToken(
                                                principal, null,
                                                List.of(new SimpleGrantedAuthority(role))
                                        );
                                        SecurityContextHolder.getContext().setAuthentication(auth);
                                    });
                        });
            }

            // 3. Org API key (legacy ERP integration credential)
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                orgRepo.findByApiKey(apiKey).ifPresent(org -> {
                    if (org.isActive()) {
                        var principal = new OrgPrincipal(org);
                        var auth = new UsernamePasswordAuthenticationToken(
                                principal, null,
                                List.of(new SimpleGrantedAuthority("ROLE_ORG"))
                        );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                });

                // 3b. Grace-period fallback: previous API key (5 min after rotation)
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    orgRepo.findByPreviousApiKey(apiKey).ifPresent(org -> {
                        if (org.isActive() && org.getApiKeyCreatedAt() != null
                                && org.getApiKeyCreatedAt().plusSeconds(300).isAfter(Instant.now())) {
                            var principal = new OrgPrincipal(org);
                            var auth = new UsernamePasswordAuthenticationToken(
                                    principal, null,
                                    List.of(new SimpleGrantedAuthority("ROLE_ORG"))
                            );
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    });
                }
            }

            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid or expired API key\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
