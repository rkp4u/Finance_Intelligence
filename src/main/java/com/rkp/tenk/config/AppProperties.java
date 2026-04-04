package com.rkp.tenk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String modelName
) {
    public AppProperties {
        if (modelName == null || modelName.isBlank()) modelName = "unknown";
    }
}
