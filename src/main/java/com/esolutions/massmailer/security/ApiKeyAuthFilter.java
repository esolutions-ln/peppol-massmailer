package com.esolutions.massmailer.security;

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
 * 2. Check {@code organization.apiKey} → grant ROLE_ORG
 * 3. No match → proceed unauthenticated
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final OrganizationRepository orgRepo;
    private final AdminSessionTokenRepository adminSessionTokenRepository;

    public ApiKeyAuthFilter(OrganizationRepository orgRepo,
                            AdminSessionTokenRepository adminSessionTokenRepository) {
        this.orgRepo = orgRepo;
        this.adminSessionTokenRepository = adminSessionTokenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey != null && !apiKey.isBlank()) {
            // 1. Check admin session tokens
            boolean adminAuthenticated = adminSessionTokenRepository
                    .findByTokenAndExpiresAtAfter(apiKey, Instant.now())
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

            if (!adminAuthenticated) {
                // 2. Fall back to org API key lookup
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
            }
        }

        chain.doFilter(request, response);
    }
}
