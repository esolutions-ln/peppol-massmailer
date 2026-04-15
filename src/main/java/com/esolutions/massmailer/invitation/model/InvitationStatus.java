package com.esolutions.massmailer.invitation.model;

public enum InvitationStatus {
    /** Awaiting customer action */
    PENDING,
    /** Customer has registered */
    COMPLETED,
    /** Cancelled by the supplier */
    CANCELLED,
    /** Past expiresAt without completion */
    EXPIRED
}
