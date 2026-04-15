package com.esolutions.massmailer.security;

import com.esolutions.massmailer.organization.model.Organization;

import java.util.UUID;

/**
 * Security principal representing an authenticated organization.
 * Injected into controllers via @AuthenticationPrincipal.
 */
public record OrgPrincipal(Organization org) {

    public UUID orgId() {
        return org.getId();
    }

    public String slug() {
        return org.getSlug();
    }

    public String name() {
        return org.getName();
    }
}
