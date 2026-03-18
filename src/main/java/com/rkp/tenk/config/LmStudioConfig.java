package com.rkp.tenk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Custom configuration for LM Studio compatibility.
 * LM Studio doesn't handle HTTP chunked transfer encoding properly,
 * so we provide a buffering (non-streaming) RestClient.Builder.
 */
@Configuration
@Profile("lmstudio")
public class LmStudioConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(10_000);
        requestFactory.setReadTimeout(120_000);

        return RestClient.builder()
                .requestFactory(requestFactory);
    }
}
