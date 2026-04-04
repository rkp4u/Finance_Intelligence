package com.rkp.tenk.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DoclingProperties.class)
public class DoclingConfig {
}
