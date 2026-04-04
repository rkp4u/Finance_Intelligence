package com.rkp.tenk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledge-compilation")
public record CompilationProperties(
        boolean enabled,
        int maxChunksPerSection,
        int maxSummaryChars
) {
    public CompilationProperties {
        if (maxChunksPerSection <= 0) maxChunksPerSection = 10;
        if (maxSummaryChars <= 0) maxSummaryChars = 8000;
    }
}
