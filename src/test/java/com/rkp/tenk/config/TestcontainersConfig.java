package com.rkp.tenk.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("tenkrag_test")
                .withUsername("test")
                .withPassword("test");
    }
}
