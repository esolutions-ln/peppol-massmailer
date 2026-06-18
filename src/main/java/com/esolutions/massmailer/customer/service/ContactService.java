package com.esolutions.massmailer.customer.service;

import com.esolutions.massmailer.customer.model.Contact;
import com.esolutions.massmailer.customer.repository.ContactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private final ContactRepository repo;

    public ContactService(ContactRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Contact upsert(UUID customerId, String email, String name, String phone) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required to create a contact");
        }
        String normalizedEmail = email.trim().toLowerCase();
        var existing = repo.findByEmail(normalizedEmail);
        if (existing.isPresent()) {
            var c = existing.get();
            if (name != null) c.setName(name.trim());
            if (phone != null) c.setPhone(phone.trim());
            c.setUpdatedAt(java.time.Instant.now());
            return repo.save(c);
        }
        var contact = Contact.builder()
                .customerId(customerId)
                .email(normalizedEmail)
                .name(name != null ? name.trim() : null)
                .phone(phone != null ? phone.trim() : null)
                .build();
        log.info("Registered new contact: {} [customer={}]", normalizedEmail, customerId);
        return repo.save(contact);
    }

    @Transactional(readOnly = true)
    public Optional<Contact> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return repo.findByEmail(email.trim().toLowerCase());
    }

    @Transactional(readOnly = true)
    public List<Contact> findByCustomerId(UUID customerId) {
        return repo.findByCustomerId(customerId);
    }
}
