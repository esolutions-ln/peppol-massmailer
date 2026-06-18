package com.esolutions.massmailer.organization.service;

import com.esolutions.massmailer.organization.model.OrgMember;
import com.esolutions.massmailer.organization.model.OrgMemberRole;
import com.esolutions.massmailer.organization.repository.OrgMemberRepository;
import com.esolutions.massmailer.organization.repository.OrgSessionTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class OrgMemberService {

    private final OrgMemberRepository members;
    private final OrgSessionTokenRepository tokens;
    private final BCryptPasswordEncoder encoder;

    public OrgMemberService(OrgMemberRepository members,
                            OrgSessionTokenRepository tokens,
                            BCryptPasswordEncoder encoder) {
        this.members = members;
        this.tokens = tokens;
        this.encoder = encoder;
    }

    @Transactional
    public OrgMember create(UUID organizationId, String email, String password,
                            String displayName, OrgMemberRole role) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
        }
        if (password == null || password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "password must be at least 8 characters");
        }
        String normalized = email.trim().toLowerCase();
        if (members.existsByOrganizationIdAndEmail(organizationId, normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A member with that email already exists in this organisation");
        }
        var m = OrgMember.builder()
                .organizationId(organizationId)
                .email(normalized)
                .passwordHash(encoder.encode(password))
                .displayName(displayName == null ? null : displayName.trim())
                .role(role == null ? OrgMemberRole.ORG_VIEWER : role)
                .build();
        return members.save(m);
    }

    @Transactional(readOnly = true)
    public List<OrgMember> list(UUID organizationId) {
        return members.findByOrganizationIdOrderByCreatedAtAsc(organizationId);
    }

    @Transactional
    public OrgMember updateRole(UUID organizationId, UUID memberId, OrgMemberRole role) {
        var m = loadScoped(organizationId, memberId);
        m.setRole(role);
        return members.save(m);
    }

    @Transactional
    public OrgMember setActive(UUID organizationId, UUID memberId, boolean active) {
        var m = loadScoped(organizationId, memberId);
        m.setActive(active);
        if (!active) {
            tokens.deleteByOrgMemberId(memberId);
        }
        return members.save(m);
    }

    @Transactional
    public void resetPassword(UUID organizationId, UUID memberId, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "password must be at least 8 characters");
        }
        var m = loadScoped(organizationId, memberId);
        m.setPasswordHash(encoder.encode(newPassword));
        members.save(m);
        tokens.deleteByOrgMemberId(memberId);
    }

    @Transactional
    public void delete(UUID organizationId, UUID memberId) {
        var m = loadScoped(organizationId, memberId);
        tokens.deleteByOrgMemberId(memberId);
        members.delete(m);
    }

    private OrgMember loadScoped(UUID organizationId, UUID memberId) {
        var m = members.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (!m.getOrganizationId().equals(organizationId)) {
            // Don't leak existence across orgs.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        }
        return m;
    }
}
