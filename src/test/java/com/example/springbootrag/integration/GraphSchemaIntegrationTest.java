package com.example.springbootrag.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class GraphSchemaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void docEdgeTableAndUpdatedAtColumnExist() {
        Integer edgeCols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'doc_edge'", Integer.class);
        assertThat(edgeCols).isGreaterThanOrEqualTo(6);

        Integer updatedAt = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                "WHERE table_name = 'chunks' AND column_name = 'updated_at'", Integer.class);
        assertThat(updatedAt).isEqualTo(1);
    }
}
