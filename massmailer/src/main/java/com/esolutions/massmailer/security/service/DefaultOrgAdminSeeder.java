package com.esolutions.massmailer.security.service;

import com.esolutions.massmailer.organization.model.OrgMember;
import com.esolutions.massmailer.organization.model.OrgMemberRole;
import com.esolutions.massmailer.organization.repository.OrgMemberRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.security.DefaultOrgAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DefaultOrgAdminSeeder {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrgAdminSeeder.class);

    private final OrganizationRepository orgRepo;
    private final OrgMemberRepository memberRepo;
    private final DefaultOrgAdminProperties props;
    private final BCryptPasswordEncoder passwordEncoder;

    public DefaultOrgAdminSeeder(OrganizationRepository orgRepo,
                                 OrgMemberRepository memberRepo,
                                 DefaultOrgAdminProperties props,
                                 BCryptPasswordEncoder passwordEncoder) {
        this.orgRepo = orgRepo;
        this.memberRepo = memberRepo;
        this.props = props;
        this.passwordEncoder = passwordEncoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaultOrgAdmins() {
        String email = props.getEmail();
        String password = props.getPassword();

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.warn("Default org admin credentials not configured — skipping. " +
                     "Set DEFAULT_ORG_ADMIN_EMAIL and DEFAULT_ORG_ADMIN_PASSWORD to enable.");
            return;
        }

        String normalizedEmail = email.trim().toLowerCase();
        String passwordHash = passwordEncoder.encode(password);
        int seeded = 0;

        var orgs = orgRepo.findAll();
        for (var org : orgs) {
            if (memberRepo.existsByOrganizationIdAndEmail(org.getId(), normalizedEmail)) {
                continue;
            }

            var member = OrgMember.builder()
                    .organizationId(org.getId())
                    .email(normalizedEmail)
                    .passwordHash(passwordHash)
                    .displayName(props.getDisplayName())
                    .role(OrgMemberRole.ORG_ADMIN)
                    .build();

            memberRepo.save(member);
            seeded++;
            log.info("Seeded default org admin '{}' for organization '{}' [slug={}]",
                    normalizedEmail, org.getName(), org.getSlug());
        }

        if (seeded > 0) {
            log.info("Default org admin '{}' seeded for {} organization(s)", normalizedEmail, seeded);
        } else {
            log.debug("Default org admin '{}' already present in all organizations", normalizedEmail);
        }
    }
}
