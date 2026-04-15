<?xml version="1.0" encoding="UTF-8"?>
<!--
  STUB FILE — NOT FOR PRODUCTION USE

  This is a placeholder for the PEPPOL EN16931 UBL Schematron rules.
  The actual pre-compiled XSLT stylesheet should be obtained from:
    https://github.com/OpenPEPPOL/peppol-bis-invoice-3/tree/master/rules/ubl

  Replace this file with the real PEPPOL-EN16931-UBL.xslt (pre-compiled XSLT 2.0)
  before deploying to production. The SchematronValidatorImpl will gracefully degrade
  (return valid=true with no violations) if this file cannot be compiled as XSLT.

  Expected file: PEPPOL-EN16931-UBL.xslt from the OpenPEPPOL BIS Billing 3.0 release.
-->
<schema xmlns="http://purl.oclc.org/dsdl/schematron"
        xmlns:u="utils"
        schemaVersion="iso"
        queryBinding="xslt2">
  <title>PEPPOL BIS Billing 3.0 EN16931 (STUB)</title>
  <ns prefix="ubl" uri="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"/>
  <ns prefix="cbc" uri="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"/>
  <ns prefix="cac" uri="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"/>
  <!-- No rules defined in stub — all documents pass validation -->
  <pattern id="stub">
    <rule context="/">
      <!-- Stub: no assertions -->
    </rule>
  </pattern>
</schema>
