package com.esolutions.massmailer.organization.service;

import com.esolutions.massmailer.organization.model.OrgMember;
import com.esolutions.massmailer.organization.model.OrgSessionToken;
import com.esolutions.massmailer.organization.repository.OrgMemberRepository;
import com.esolutions.massmailer.organization.repository.OrgSessionTokenRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.security.AdminTokens;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class OrgAuthService {

    private static final int TOKEN_EXPIRY_HOURS = 8;

    private final OrgMemberRepository members;
    private final OrgSessionTokenRepository tokens;
    private final OrganizationRepository orgs;
    private final BCryptPasswordEncoder encoder;

    public OrgAuthService(OrgMemberRepository members,
                          OrgSessionTokenRepository tokens,
                          OrganizationRepository orgs,
                          BCryptPasswordEncoder encoder) {
        this.members = members;
        this.tokens = tokens;
        this.orgs = orgs;
        this.encoder = encoder;
    }

    public record LoginResponse(
            String token,
            UUID orgId,
            String orgSlug,
            UUID memberId,
            String email,
            String displayName,
            String role
    ) {}

    @Transactional
    public LoginResponse login(String orgSlug, String email, String password) {
        var org = orgs.findBySlug(orgSlug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid organisation, email, or password"));

        OrgMember member = members.findByOrganizationIdAndEmail(org.getId(),
                        email == null ? "" : email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid organisation, email, or password"));

        if (!member.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is deactivated");
        }
        if (!encoder.matches(password, member.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid organisation, email, or password");
        }

        String rawToken = AdminTokens.generateRawToken();
        var session = OrgSessionToken.builder()
                .token(AdminTokens.hashToken(rawToken))
                .orgMember(member)
                .expiresAt(Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS))
                .build();
        tokens.save(session);

        member.setLastLoginAt(Instant.now());
        members.save(member);

        return new LoginResponse(rawToken, org.getId(), org.getSlug(),
                member.getId(), member.getEmail(), member.getDisplayName(),
                member.getRole().name());
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        tokens.deleteByToken(AdminTokens.hashToken(rawToken));
    }
}
