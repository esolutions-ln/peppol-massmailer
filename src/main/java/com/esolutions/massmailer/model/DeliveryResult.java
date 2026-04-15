package com.esolutions.massmailer.model;

/**
 * Algebraic data type for invoice email delivery outcomes.
 * Sealed interface + records = exhaustive pattern matching in Java 25.
 *
 * Every variant carries invoiceNumber so campaign tracking
 * can correlate delivery status back to a specific fiscal document.
 */
public sealed interface DeliveryResult {

    String recipientEmail();
    String invoiceNumber();

    record Delivered(
            String recipientEmail,
            String invoiceNumber,
            String messageId,
            long attachmentSizeBytes
    ) implements DeliveryResult {}

    record Failed(
            String recipientEmail,
            String invoiceNumber,
            String errorMessage,
            boolean retryable
    ) implements DeliveryResult {}

    record Skipped(
            String recipientEmail,
            String invoiceNumber,
            String reason
    ) implements DeliveryResult {}
}
