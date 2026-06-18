package com.esolutions.massmailer.security;

import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.model.OrgMember;

import java.util.UUID;

/**
 * Security principal representing an authenticated organization.
 * Injected into controllers via @AuthenticationPrincipal.
 *
 * The optional {@code member} is set when the principal was authenticated
 * via an {@link OrgMember} session token (email + password login). It is
 * {@code null} for legacy API-key authentication, which still represents
 * a fully-trusted org-level integration credential.
 */
public record OrgPrincipal(Organization org, OrgMember member) {

    public OrgPrincipal(Organization org) {
        this(org, null);
    }

    public UUID orgId() {
        return org.getId();
    }

    public String slug() {
        return org.getSlug();
    }

    public String name() {
        return org.getName();
    }

    public boolean isMemberLogin() {
        return member != null;
    }
}
