package com.esolutions.massmailer.customer.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contacts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_contact_email",
                columnNames = "email"),
        indexes = {
                @Index(name = "idx_contact_customer", columnList = "customerId"),
                @Index(name = "idx_contact_email", columnList = "email")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID customerId;

    @Column(nullable = false, unique = true)
    private String email;

    private String name;

    private String phone;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;
}
