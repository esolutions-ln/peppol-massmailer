package com.esolutions.massmailer.peppol.as4;

/**
 * Result of an AS4 message delivery attempt, including MDN details.
 */
public record As4DeliveryResult(
        boolean success,
        String mdnMessageId,
        String mdnStatus,
        String rawMdnResponse,
        String errorDescription
) {

    /**
     * Factory method for a successful delivery.
     */
    public static As4DeliveryResult success(String mdnMessageId, String mdnStatus, String rawMdn) {
        return new As4DeliveryResult(true, mdnMessageId, mdnStatus, rawMdn, null);
    }

    /**
     * Factory method for a failed delivery.
     */
    public static As4DeliveryResult failure(String errorDescription) {
        return new As4DeliveryResult(false, null, null, null, errorDescription);
    }
}
