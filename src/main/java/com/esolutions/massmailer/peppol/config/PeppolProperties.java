package com.esolutions.massmailer.peppol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "peppol")
public class PeppolProperties {

    private Smp smp = new Smp();
    private Sml sml = new Sml();

    public Smp getSmp() { return smp; }
    public void setSmp(Smp smp) { this.smp = smp; }

    public Sml getSml() { return sml; }
    public void setSml(Sml sml) { this.sml = sml; }

    public static class Smp {
        private String baseUrl = "https://smp.peppoltest.org";
        private int cacheTtlSeconds = 3600;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public int getCacheTtlSeconds() { return cacheTtlSeconds; }
        public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    }

    public static class Sml {
        private String domain = "sml.peppoltest.org";
        private String dnsPrefix = "b";

        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }

        public String getDnsPrefix() { return dnsPrefix; }
        public void setDnsPrefix(String dnsPrefix) { this.dnsPrefix = dnsPrefix; }
    }
}
