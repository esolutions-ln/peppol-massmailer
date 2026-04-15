package com.esolutions.massmailer.model;

/**
 * Invoice delivery channel for an organization or individual customer.
 *
 * <ul>
 *   <li>{@code EMAIL} — Send invoice as a PDF email attachment (SMTP/Gmail)</li>
 *   <li>{@code AS4}   — Send via PEPPOL network (UBL 2.1 BIS 3.0) to the customer's ERP</li>
 *   <li>{@code BOTH}  — Send both email AND PEPPOL (useful during transition)</li>
 * </ul>
 *
 * Set at the Organization level as the default for all customers.
 * Can be overridden per CustomerContact for customers that only support one channel.
 */
public enum DeliveryMode {
    EMAIL,
    AS4,
    BOTH
}
