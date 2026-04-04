package com.rkp.tenk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Docling Serve integration.
 * Bind via application.yml under the "docling" key.
 */
@ConfigurationProperties(prefix = "docling")
public record DoclingProperties(
        boolean enabled,
        String baseUrl,
        int timeoutSeconds,
        int maxPagesPerRequest
) {
    public DoclingProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:5001";
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }
        if (maxPagesPerRequest <= 0) {
            maxPagesPerRequest = 10;
        }
    }
}
