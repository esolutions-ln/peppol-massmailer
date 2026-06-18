package com.esolutions.massmailer.customer.model;

import com.esolutions.massmailer.model.DeliveryMode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_customer_erp_id",
                columnNames = "erpCustomerId"),
        indexes = {
                @Index(name = "idx_customer_org", columnList = "organizationId"),
                @Index(name = "idx_customer_erp_id", columnList = "erpCustomerId"),
                @Index(name = "idx_customer_bpn", columnList = "bpn"),
                @Index(name = "idx_customer_vat", columnList = "vatNumber"),
                @Index(name = "idx_customer_tin", columnList = "tinNumber")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false, unique = true, length = 100)
    private String erpCustomerId;

    private String companyName;

    @Column(length = 255)
    private String tradingName;

    @Column(length = 30)
    private String erpSource;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private DeliveryMode deliveryMode;

    @Column(length = 100)
    private String peppolParticipantId;

    @Column(length = 50)
    private String vatNumber;

    @Column(length = 50)
    private String tinNumber;

    @Column(length = 50)
    private String bpn;

    @Column(length = 255)
    private String addressLine1;

    @Column(length = 255)
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String country;

    @Builder.Default
    private boolean unsubscribed = false;

    private String unsubscribeReason;

    @Builder.Default
    private long totalInvoicesSent = 0;

    @Builder.Default
    private long totalDeliveryFailures = 0;

    private Instant lastInvoiceSentAt;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    public void recordDelivery(boolean success) {
        if (success) {
            this.totalInvoicesSent++;
            this.lastInvoiceSentAt = Instant.now();
        } else {
            this.totalDeliveryFailures++;
        }
        this.updatedAt = Instant.now();
    }
}
