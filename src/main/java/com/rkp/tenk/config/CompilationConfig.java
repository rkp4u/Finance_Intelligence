package com.rkp.tenk.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CompilationProperties.class, AppProperties.class})
public class CompilationConfig {
}
