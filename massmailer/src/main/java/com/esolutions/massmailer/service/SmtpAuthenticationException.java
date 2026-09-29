package com.esolutions.massmailer.service;

import jakarta.mail.MessagingException;

/**
 * The mail provider refused to authenticate us — bad credentials, or (commonly) the
 * sending IP isn't allow-listed, e.g. {@code 525 5.7.1 Unauthorized IP address}.
 * Never retried: the same request will be refused again.
 */
public class SmtpAuthenticationException extends MessagingException {

    public SmtpAuthenticationException(String message, Exception cause) {
        super(message, cause);
    }
}
