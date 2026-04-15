package com.esolutions.massmailer.security;

import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final OrganizationRepository orgRepo;
    private final AdminSessionTokenRepository adminSessionTokenRepository;

    public SecurityConfig(OrganizationRepository orgRepo,
                          AdminSessionTokenRepository adminSessionTokenRepository) {
        this.orgRepo = orgRepo;
        this.adminSessionTokenRepository = adminSessionTokenRepository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new ApiKeyAuthFilter(orgRepo, adminSessionTokenRepository),
                    UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Public — health, swagger, PEPPOL inbound, org registration, rate profiles
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/info",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    "/peppol/**",
                    "/webhooks/**",                    // ERP webhooks (Sage, etc.)
                    "/api/v1/organizations",           // POST — register new org (public)
                    "/api/v1/organizations/by-slug/*", // GET — lookup by slug (public)
                    "/api/v1/billing/rate-profiles",   // GET — view plans before signing up
                    "/api/v1/billing/estimate",        // POST — cost estimate before signing up
                    "/api/v1/admin/login"              // POST — admin authentication (public)
                ).permitAll()
                // Public invitation token endpoints (no auth — accessed by unauthenticated customers)
                .requestMatchers("/api/v1/invitations/**").permitAll()
                // Org dashboard — requires valid API key
                .requestMatchers("/api/v1/my/**").hasRole("ORG")
                // Admin-only routes
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/organizations/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/billing/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/eregistry/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/campaigns/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/dashboard/**").hasAnyRole("ORG", "ADMIN")
                // All other API routes — require API key
                .requestMatchers("/api/**").hasAnyRole("ORG", "ADMIN")
                .anyRequest().permitAll()
            );

        return http.build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
