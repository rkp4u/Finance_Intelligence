package com.rkp.tenk.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tenkRagOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("10-K RAG API")
                        .description("Production-quality RAG system for SEC 10-K financial filings. "
                                + "Upload PDFs, create knowledge bases, and query them with natural language.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("RKP")));
    }
}
