package com.esolutions.massmailer.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Admin credentials loaded from environment variables / application.yml.
 * Set ADMIN_USERNAME and ADMIN_PASSWORD in the environment (used for bootstrap only).
 */
@Component
@ConfigurationProperties(prefix = "admin")
public class AdminProperties {

    private String username = "admin";
    private String password;
    private int tokenExpiryHours = 8;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int getTokenExpiryHours() { return tokenExpiryHours; }
    public void setTokenExpiryHours(int tokenExpiryHours) { this.tokenExpiryHours = tokenExpiryHours; }
}
