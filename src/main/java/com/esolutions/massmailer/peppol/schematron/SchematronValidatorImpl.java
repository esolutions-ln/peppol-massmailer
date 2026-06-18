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
 * <p>Compiles the PEPPOL EN16931 Schematron rules (pre-compiled XSLT 2.0) from the
 * classpath once and caches per profileId. The XSLT transform is run against the
 * UBL DOM and the resulting SVRL document is parsed for {@code svrl:failed-assert}
 * elements.
 *
 * <p><b>Fail-closed semantics:</b> if the schematron resource is missing, is a known
 * placeholder/stub, or fails to compile, validation refuses to operate. Every
 * {@link #validate} call returns a {@link SchematronResult} containing a fatal
 * {@code PEPPOL-RULES-NOT-INSTALLED} violation — the caller (e.g. PeppolDeliveryService)
 * then fails the delivery rather than silently accepting an unvalidated document.
 */
@Component
public class SchematronValidatorImpl implements SchematronValidator {

    private static final Logger log = LoggerFactory.getLogger(SchematronValidatorImpl.class);

    private static final String SCH_CLASSPATH = "schematron/PEPPOL-EN16931-UBL.sch";
    private static final String SVRL_NS = "http://purl.oclc.org/dsdl/svrl";
    private static final String FAILED_ASSERT = "failed-assert";
    private static final String STUB_MARKER = "STUB FILE";

    /** Cache of compiled XSLT transforms keyed by profileId. */
    private final ConcurrentHashMap<String, Templates> transformCache = new ConcurrentHashMap<>();

    /** Saxon-HE TransformerFactory — supports XSLT 2.0. */
    private final TransformerFactory transformerFactory = new TransformerFactoryImpl();

    /** Marker that the configured rules file is a placeholder (no real assertions). */
    private final boolean rulesAreStub;

    public SchematronValidatorImpl() {
        this.rulesAreStub = detectStub();
        if (rulesAreStub) {
            log.error("PEPPOL Schematron ruleset at classpath:{} is a STUB. All inbound and outbound " +
                    "PEPPOL documents will FAIL validation until the real OpenPEPPOL EN16931 XSLT is " +
                    "installed. Download from https://github.com/OpenPEPPOL/peppol-bis-invoice-3/releases.",
                    SCH_CLASSPATH);
        }
    }

    @Override
    public SchematronResult validate(String ublXml, String profileId) {
        if (rulesAreStub) {
            return rulesMissingResult();
        }
        Templates templates = transformCache.computeIfAbsent(profileId, this::compileSchematron);
        if (templates == null) {
            return rulesMissingResult();
        }
        return executeValidation(templates, ublXml);
    }

    // -------------------------------------------------------------------------
    // Stub detection / fail-closed result
    // -------------------------------------------------------------------------

    private boolean detectStub() {
        ClassPathResource resource = new ClassPathResource(SCH_CLASSPATH);
        if (!resource.exists()) {
            log.error("Schematron ruleset not present on classpath: {}", SCH_CLASSPATH);
            return true;
        }
        try (InputStream is = resource.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Real OpenPEPPOL XSLT contains thousands of <assert> nodes;
            // the stub has none and carries an explicit marker.
            if (content.contains(STUB_MARKER) || !content.contains("<assert")) {
                return true;
            }
        } catch (Exception e) {
            log.error("Could not read Schematron resource {}: {}", SCH_CLASSPATH, e.getMessage());
            return true;
        }
        return false;
    }

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

    // -------------------------------------------------------------------------
    // Compilation and execution
    // -------------------------------------------------------------------------

    private Templates compileSchematron(String profileId) {
        ClassPathResource resource = new ClassPathResource(SCH_CLASSPATH);
        if (!resource.exists()) {
            log.error("Schematron file not found on classpath: {}", SCH_CLASSPATH);
            return null;
        }
        try (InputStream is = resource.getInputStream()) {
            StreamSource source = new StreamSource(is, resource.getURL().toExternalForm());
            Templates compiled = transformerFactory.newTemplates(source);
            log.info("Compiled Schematron rules for profileId={}", profileId);
            return compiled;
        } catch (Exception e) {
            log.error("Could not compile Schematron rules for {}: {}", profileId, e.getMessage());
            return null;
        }
    }

    private SchematronResult executeValidation(Templates templates, String ublXml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document ublDoc = db.parse(new ByteArrayInputStream(ublXml.getBytes(StandardCharsets.UTF_8)));

            Transformer transformer = templates.newTransformer();
            DOMResult svrlResult = new DOMResult();
            transformer.transform(new DOMSource(ublDoc), svrlResult);

            Document svrlDoc = (Document) svrlResult.getNode();
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
}
