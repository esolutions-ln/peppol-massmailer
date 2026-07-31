package com.esolutions.massmailer.peppol.schematron;

/**
 * Represents a single Schematron rule violation from SVRL output.
 *
 * @param ruleId   the rule identifier, e.g. "BR-01", "BR-CO-15"
 * @param severity "fatal" (blocks transmission) or "warning" (informational)
 * @param message  human-readable description of the violation
 * @param location XPath location in the UBL document where the violation occurred
 */
public record SchematronViolation(
        String ruleId,
        String severity,
        String message,
        String location
) {
    public boolean isFatal() {
        return "fatal".equalsIgnoreCase(severity);
    }
}
