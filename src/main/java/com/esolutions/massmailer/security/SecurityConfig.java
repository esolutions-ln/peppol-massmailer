package com.esolutions.massmailer.security;

import com.esolutions.massmailer.organization.repository.OrgSessionTokenRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
    private final OrgSessionTokenRepository orgSessionTokenRepository;

    public SecurityConfig(OrganizationRepository orgRepo,
                          AdminSessionTokenRepository adminSessionTokenRepository,
                          OrgSessionTokenRepository orgSessionTokenRepository) {
        this.orgRepo = orgRepo;
        this.adminSessionTokenRepository = adminSessionTokenRepository;
        this.orgSessionTokenRepository = orgSessionTokenRepository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new ApiKeyAuthFilter(orgRepo, adminSessionTokenRepository,
                            orgSessionTokenRepository),
                    UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Public — health, swagger, PEPPOL inbound, org registration, rate profiles
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/info",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    "/peppol/as4/receive",
                    "/peppol/as4/health",
                    "/webhooks/**",
                    "/api/v1/organizations",
                    "/api/v1/organizations/by-slug/*",
                    "/api/v1/billing/rate-profiles",
                    "/api/v1/billing/estimate",
                    "/api/v1/admin/login",
                    "/api/v1/org/login"
                ).permitAll()
                // PEPPOL inbox / queries — admin only
                .requestMatchers("/peppol/as4/inbox", "/peppol/as4/inbox/**").hasRole("ADMIN")
                .requestMatchers("/peppol/eregistry/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/peppol/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/invitations/**").permitAll()

                // ── Org-member management — requires full ORG role (admin members or API key) ──
                .requestMatchers("/api/v1/my/members/**").hasRole("ORG")

                // ── Read-only viewer access — ORG_VIEWER + ORG can both GET these ──
                .requestMatchers(HttpMethod.GET,
                        "/api/v1/my/invoices",
                        "/api/v1/my/invoices/**",
                        "/api/v1/my/campaigns",
                        "/api/v1/my/campaigns/**",
                        "/api/v1/my/customers",
                        "/api/v1/my/customers/**",
                        "/api/v1/my/dashboard",
                        "/api/v1/my/dashboard/**",
                        "/api/v1/my/email-templates",
                        "/api/v1/my/email-templates/**"
                ).hasAnyRole("ORG", "ORG_VIEWER")

                // Org dashboard mutations — requires full ORG role
                .requestMatchers("/api/v1/my/**").hasRole("ORG")

                // Admin-only routes
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/organizations/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/billing/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/eregistry/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/campaigns/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/dashboard/**").hasAnyRole("ORG", "ADMIN")
                .requestMatchers("/api/v1/mail/**").hasAnyRole("ORG", "ADMIN")
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
