package com.esolutions.massmailer.peppol.as4;

/**
 * Thrown by {@link As4TransportClient} when a network or protocol-level error
 * prevents the AS4 message from being delivered (e.g. connection refused,
 * TLS handshake failure, malformed SOAP response).
 */
public class As4TransportException extends RuntimeException {

    public As4TransportException(String message) {
        super(message);
    }

    public As4TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
