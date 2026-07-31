package com.esolutions.massmailer.infrastructure.adapters.common;

import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;
import com.esolutions.massmailer.domain.ports.ErpIntegrationException;
import com.esolutions.massmailer.domain.ports.ErpInvoicePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry that discovers all {@link ErpInvoicePort} adapter beans
 * and indexes them by {@link ErpSource}.
 *
 * <p>New ERP adapters are automatically registered — no factory modification needed.
 * This is the Open/Closed principle at the infrastructure level:
 * add a new {@code @Component} implementing {@code ErpInvoicePort} and it appears here.</p>
 *
 * <p>Usage:
 * <pre>
 *   ErpInvoicePort adapter = registry.getAdapter(ErpSource.SAGE_INTACCT);
 *   List&lt;CanonicalInvoice&gt; invoices = adapter.fetchInvoices(tenantId, ids);
 * </pre>
 */
@Component
public class ErpAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ErpAdapterRegistry.class);

    private final Map<ErpSource, ErpInvoicePort> adapterMap;

    /**
     * Spring injects all ErpInvoicePort beans automatically.
     */
    public ErpAdapterRegistry(List<ErpInvoicePort> adapters) {
        this.adapterMap = new EnumMap<>(ErpSource.class);
        for (var adapter : adapters) {
            adapterMap.put(adapter.supports(), adapter);
            log.info("Registered ERP adapter: {} → {}", adapter.supports(),
                    adapter.getClass().getSimpleName());
        }
        log.info("ERP Adapter Registry initialised with {} adapter(s): {}",
                adapterMap.size(), adapterMap.keySet());
    }

    /**
     * Returns the adapter for the given ERP source.
     *
     * @throws ErpIntegrationException if no adapter is registered for the source
     */
    public ErpInvoicePort getAdapter(ErpSource source) {
        var adapter = adapterMap.get(source);
        if (adapter == null) {
            throw new ErpIntegrationException(source,
                    "No adapter registered for ERP source: " + source
                            + ". Available adapters: " + adapterMap.keySet());
        }
        return adapter;
    }

    /**
     * Checks if an adapter is available for the given source.
     */
    public boolean hasAdapter(ErpSource source) {
        return adapterMap.containsKey(source);
    }

    /**
     * Returns all registered ERP sources (for Swagger documentation / health checks).
     */
    public java.util.Set<ErpSource> registeredSources() {
        return java.util.Collections.unmodifiableSet(adapterMap.keySet());
    }
}
