package com.esolutions.massmailer.peppol.schematron;

import java.util.List;

/**
 * Thrown when a UBL document fails Schematron validation with one or more fatal violations.
 * The delivery is blocked and a {@code PeppolDeliveryRecord} with {@code status=FAILED} is persisted.
 */
public class SchematronValidationException extends RuntimeException {

    private final List<SchematronViolation> violations;

    public SchematronValidationException(List<SchematronViolation> violations) {
        super(buildMessage(violations));
        this.violations = violations;
    }

    public List<SchematronViolation> getViolations() {
        return violations;
    }

    private static String buildMessage(List<SchematronViolation> violations) {
        if (violations == null || violations.isEmpty()) {
            return "Schematron validation failed with fatal violations";
        }
        StringBuilder sb = new StringBuilder("Schematron validation failed: ");
        for (int i = 0; i < violations.size(); i++) {
            SchematronViolation v = violations.get(i);
            if (v.isFatal()) {
                if (i > 0) sb.append("; ");
                sb.append("[").append(v.ruleId()).append("] ").append(v.message());
            }
        }
        return sb.toString();
    }
}
