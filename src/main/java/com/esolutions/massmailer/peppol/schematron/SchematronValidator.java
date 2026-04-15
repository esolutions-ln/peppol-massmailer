package com.esolutions.massmailer.peppol.schematron;

/**
 * Validates UBL 2.1 XML against PEPPOL EN16931 Schematron rules.
 */
public interface SchematronValidator {

    /**
     * Validates the given UBL XML document against the Schematron rules for the specified profile.
     *
     * @param ublXml    well-formed UBL 2.1 XML string (non-null, non-blank)
     * @param profileId PEPPOL profile identifier, e.g. {@code urn:fdc:peppol.eu:2017:poacc:billing:01:1.0}
     * @return {@link SchematronResult} with {@code valid=true} iff zero fatal violations
     */
    SchematronResult validate(String ublXml, String profileId);
}
