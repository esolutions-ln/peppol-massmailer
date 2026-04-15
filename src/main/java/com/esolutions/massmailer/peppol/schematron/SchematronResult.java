package com.esolutions.massmailer.peppol.schematron;

import java.util.List;

/**
 * Result of a Schematron validation run.
 *
 * @param valid      true iff there are zero fatal violations
 * @param violations all violations (fatal + warning) found during validation
 */
public record SchematronResult(
        boolean valid,
        List<SchematronViolation> violations
) {
    /** Returns true if any violation has severity "fatal". */
    public boolean hasFatalViolations() {
        return violations.stream().anyMatch(SchematronViolation::isFatal);
    }

    /** Returns only the warning-level violations. */
    public List<SchematronViolation> getWarnings() {
        return violations.stream()
                .filter(v -> !v.isFatal())
                .toList();
    }
}
