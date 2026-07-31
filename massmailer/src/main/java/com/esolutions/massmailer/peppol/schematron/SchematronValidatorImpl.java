package com.esolutions.massmailer.peppol.schematron;

import com.helger.schematron.sch.SchematronResourceSCH;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ISO Schematron validator using ph-schematron-xslt (proper 3-phase pipeline).
 *
 * <p>Compiles the PEPPOL EN16931 Schematron .sch file to XSLT via the
 * iso-schematron-xslt2 pipeline (include resolution &rarr; abstract expand &rarr;
 * SVRL compile). The compiled XSLT is cached internally by {@link SchematronResourceSCH}.
 *
 * <p><b>Fail-closed semantics:</b> if compilation fails, all validate() calls
 * return a fatal {@code PEPPOL-RULES-NOT-INSTALLED} violation.
 */
@Component
public class SchematronValidatorImpl implements SchematronValidator {

    private static final Logger log = LoggerFactory.getLogger(SchematronValidatorImpl.class);

    private static final String SCH_CLASSPATH = "schematron/PEPPOL-EN16931-UBL.sch";
    private static final String SVRL_NS = "http://purl.oclc.org/dsdl/svrl";
    private static final String FAILED_ASSERT = "failed-assert";
    private static final String STUB_MARKER = "STUB FILE";

    private final SchematronResourceSCH schematron;
    private final boolean rulesAvailable;

    public SchematronValidatorImpl() {
        ClassPathResource resource = new ClassPathResource(SCH_CLASSPATH);
        if (!resource.exists()) {
            log.error("Schematron ruleset not present on classpath: {}", SCH_CLASSPATH);
            schematron = null;
            rulesAvailable = false;
            return;
        }

        // Check for stub
        boolean stub = false;
        try (InputStream is = resource.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (content.contains(STUB_MARKER) || !content.contains("<assert")) {
                log.error("PEPPOL Schematron ruleset at classpath:{} is a STUB. " +
                        "All PEPPOL documents will FAIL validation.", SCH_CLASSPATH);
                stub = true;
            }
        } catch (Exception e) {
            log.error("Could not read Schematron resource {}: {}", SCH_CLASSPATH, e.getMessage());
            schematron = null;
            rulesAvailable = false;
            return;
        }

        if (stub) {
            this.schematron = null;
            this.rulesAvailable = false;
            return;
        }

        // Initialize ph-schematron resource
        SchematronResourceSCH compiled = null;
        boolean ok = false;
        try {
            compiled = SchematronResourceSCH.fromClassPath(SCH_CLASSPATH);
            compiled.setUseCache(true);

            // Warm up: force 3-phase compilation at startup
            String dummy = "<?xml version='1.0'?><dummy/>";
            compiled.applySchematronValidation(new StreamSource(new StringReader(dummy)));
            int count = countAssertions(resource);
            log.info("Successfully compiled PEPPOL EN16931 Schematron rules ({} assertions present).", count);
            ok = true;
        } catch (Exception e) {
            log.error("Failed to compile Schematron rules: {}", e.getMessage(), e);
        }
        this.schematron = compiled;
        this.rulesAvailable = ok;
    }

    @Override
    public SchematronResult validate(String ublXml, String profileId) {
        if (!rulesAvailable || schematron == null) {
            return rulesMissingResult();
        }
        return executeValidation(ublXml);
    }

    // -------------------------------------------------------------------------
    // Implementation
    // -------------------------------------------------------------------------

    private SchematronResult rulesMissingResult() {
        SchematronViolation v = new SchematronViolation(
                "PEPPOL-RULES-NOT-INSTALLED",
                "fatal",
                "PEPPOL EN16931 Schematron ruleset is not installed on the server. " +
                "Refusing to validate to avoid silently passing non-conforming documents.",
                "/"
        );
        return new SchematronResult(false, List.of(v));
    }

    private SchematronResult executeValidation(String ublXml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document ublDoc = db.parse(new ByteArrayInputStream(ublXml.getBytes(StandardCharsets.UTF_8)));

            Document svrlDoc = schematron.applySchematronValidation(new DOMSource(ublDoc));

            List<SchematronViolation> violations = parseSvrl(svrlDoc);

            boolean valid = violations.stream().noneMatch(SchematronViolation::isFatal);
            return new SchematronResult(valid, violations);

        } catch (Exception e) {
            log.error("Schematron validation failed with exception: {}", e.getMessage(), e);
            SchematronViolation error = new SchematronViolation(
                    "VALIDATION-ERROR", "fatal",
                    "Schematron validation could not be executed: " + e.getMessage(),
                    "/"
            );
            return new SchematronResult(false, List.of(error));
        }
    }

    private List<SchematronViolation> parseSvrl(Document svrlDoc) {
        List<SchematronViolation> violations = new ArrayList<>();
        NodeList failedAsserts = svrlDoc.getElementsByTagNameNS(SVRL_NS, FAILED_ASSERT);
        for (int i = 0; i < failedAsserts.getLength(); i++) {
            Element fa = (Element) failedAsserts.item(i);
            String ruleId = fa.getAttribute("id");
            String role = fa.getAttribute("role");
            String location = fa.getAttribute("location");

            String severity = (role == null || role.isBlank() || "fatal".equalsIgnoreCase(role))
                    ? "fatal" : "warning";

            String message = extractText(fa);

            violations.add(new SchematronViolation(ruleId, severity, message, location));
        }
        return violations;
    }

    private String extractText(Element element) {
        NodeList textNodes = element.getElementsByTagNameNS(SVRL_NS, "text");
        if (textNodes.getLength() > 0) {
            return textNodes.item(0).getTextContent().trim();
        }
        return element.getTextContent().trim();
    }

    private int countAssertions(ClassPathResource resource) {
        try (InputStream is = resource.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            int idx = 0, count = 0;
            while ((idx = content.indexOf("<assert", idx)) != -1) {
                count++;
                idx += 7;
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }
}
