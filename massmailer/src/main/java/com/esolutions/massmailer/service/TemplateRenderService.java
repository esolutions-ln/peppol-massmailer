package com.esolutions.massmailer.service;

import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * Renders HTML email bodies from Thymeleaf templates.
 * Templates live under src/main/resources/templates/email/
 */
@Service
public class TemplateRenderService {

    private final TemplateEngine templateEngine;

    public TemplateRenderService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /**
     * Merges campaign-level + per-recipient variables into a Thymeleaf template.
     *
     * @param templateName  template file name (without path/extension), e.g. "welcome"
     * @param campaignVars  shared variables across all recipients
     * @param mergeFields   per-recipient overrides / personalisation
     * @return rendered HTML string
     */
    public String render(String templateName, Map<String, Object> campaignVars, Map<String, Object> mergeFields) {
        var ctx = new Context();

        // Campaign-level variables first (lower precedence)
        if (campaignVars != null) {
            ctx.setVariables(campaignVars);
        }
        // Per-recipient merge fields override campaign vars
        if (mergeFields != null) {
            ctx.setVariables(mergeFields);
        }

        return templateEngine.process("email/" + templateName, ctx);
    }
}
