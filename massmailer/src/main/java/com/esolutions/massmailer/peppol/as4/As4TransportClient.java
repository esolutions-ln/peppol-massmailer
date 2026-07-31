package com.esolutions.massmailer.peppol.as4;

/**
 * Port for AS4 ebMS 3.0 transport — wraps, signs, encrypts, and delivers
 * a UBL XML payload to a remote PEPPOL Access Point.
 */
public interface As4TransportClient {

    /**
     * Send an AS4 message and return the delivery result.
     *
     * @param message the message to send (payload + certificates + endpoint)
     * @return result containing MDN details on success or an error description on failure
     * @throws As4TransportException on unrecoverable network or protocol errors
     */
    As4DeliveryResult send(As4Message message);
}
