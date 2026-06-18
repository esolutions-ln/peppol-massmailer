package com.esolutions.massmailer.customer.repository;

import com.esolutions.massmailer.customer.model.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContactRepository extends JpaRepository<Contact, UUID> {

    Optional<Contact> findByEmail(String email);

    List<Contact> findByCustomerId(UUID customerId);

    boolean existsByEmail(String email);
}
