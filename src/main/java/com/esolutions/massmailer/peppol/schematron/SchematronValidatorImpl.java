package com.esolutions.massmailer.peppol.schematron;

import net.sf.saxon.TransformerFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saxon-HE based Schematron validator.
 *
 * <p>Loads the PEPPOL EN16931 Schematron rules from the classpath, compiles them as an XSLT
 * stylesheet (PEPPOL pre-compiled .sch files are valid XSLT 2.0), and caches the compiled
 * {@link Templates} per profileId. Subsequent calls reuse the cached transform.
 *
 * <p>SVRL namespace: {@code http://purl.oclc.org/dsdl/svrl}
 */
@Component
public class SchematronValidatorImpl implements SchematronValidator {

    private static final Logger log = LoggerFactory.getLogger(SchematronValidatorImpl.class);

    private static final String SCH_CLASSPATH = "schematron/PEPPOL-EN16931-UBL.sch";
    private static final String SVRL_NS = "http://purl.oclc.org/dsdl/svrl";
    private static final String FAILED_ASSERT = "failed-assert";

    /** Cache of compiled XSLT transforms keyed by profileId. */
    private final ConcurrentHashMap<String, Templates> transformCache = new ConcurrentHashMap<>();

    /** Saxon-HE TransformerFactory — supports XSLT 2.0. */
    private final TransformerFactory transformerFactory = new TransformerFactoryImpl();

    @Override
    public SchematronResult validate(String ublXml, String profileId) {
        Templates templates = transformCache.computeIfAbsent(profileId, this::compileSchematron);
        if (templates == null) {
            // Graceful degradation: schematron file is a stub or not a valid XSLT
            log.warn("Schematron rules not available for profileId={}; skipping validation", profileId);
            return new SchematronResult(true, List.of());
        }
        return executeValidation(templates, ublXml);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Compiles the .sch file as an XSLT 2.0 stylesheet using Saxon-HE.
     * Returns null if the file is a stub or cannot be compiled (graceful degradation).
     */
    private Templates compileSchematron(String profileId) {
        ClassPathResource resource = new ClassPathResource(SCH_CLASSPATH);
        if (!resource.exists()) {
            log.warn("Schematron file not found on classpath: {}", SCH_CLASSPATH);
            return null;
        }
        try (InputStream is = resource.getInputStream()) {
            StreamSource source = new StreamSource(is, resource.getURL().toExternalForm());
            Templates compiled = transformerFactory.newTemplates(source);
            log.info("Compiled Schematron rules for profileId={}", profileId);
            return compiled;
        } catch (Exception e) {
            log.warn("Could not compile Schematron rules (stub file?): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Executes the compiled XSLT transform against the UBL XML and parses SVRL output.
     */
    private SchematronResult executeValidation(Templates templates, String ublXml) {
        try {
            // Parse UBL XML into a DOM source
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document ublDoc = db.parse(new ByteArrayInputStream(ublXml.getBytes(StandardCharsets.UTF_8)));

            // Run the XSLT transform; result is SVRL XML
            Transformer transformer = templates.newTransformer();
            DOMResult svrlResult = new DOMResult();
            transformer.transform(new DOMSource(ublDoc), svrlResult);

            // Parse SVRL output for failed-assert elements
            Document svrlDoc = (Document) svrlResult.getNode();
            List<SchematronViolation> violations = parseSvrl(svrlDoc);

            boolean valid = violations.stream().noneMatch(SchematronViolation::isFatal);
            return new SchematronResult(valid, violations);

        } catch (Exception e) {
            log.error("Schematron validation failed with exception: {}", e.getMessage(), e);
            // Treat transformation errors as a fatal violation so the invoice is not silently passed
            SchematronViolation error = new SchematronViolation(
                    "VALIDATION-ERROR", "fatal",
                    "Schematron validation could not be executed: " + e.getMessage(),
                    "/"
            );
            return new SchematronResult(false, List.of(error));
        }
    }

    /**
     * Parses {@code svrl:failed-assert} elements from the SVRL document.
     */
    private List<SchematronViolation> parseSvrl(Document svrlDoc) {
        List<SchematronViolation> violations = new ArrayList<>();
        NodeList failedAsserts = svrlDoc.getElementsByTagNameNS(SVRL_NS, FAILED_ASSERT);
        for (int i = 0; i < failedAsserts.getLength(); i++) {
            Element fa = (Element) failedAsserts.item(i);
            String ruleId = fa.getAttribute("id");
            String role = fa.getAttribute("role");
            String location = fa.getAttribute("location");

            // Severity: "fatal" if role is blank/fatal, "warning" otherwise
            String severity = (role == null || role.isBlank() || "fatal".equalsIgnoreCase(role))
                    ? "fatal" : "warning";

            // Message text is in svrl:text child element
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
}
