package com.example.springbootrag.integration;

import com.example.springbootrag.model.SearchHit;
import com.example.springbootrag.repository.PgVectorRepository;
import com.example.springbootrag.security.TestContexts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class UpdatedAtIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired PgVectorRepository repo;
    @Autowired JdbcTemplate jdbc;

    @Test
    void insertAndReadBackUpdatedAt() {
        long p = jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
        Instant when = Instant.parse("2026-06-01T00:00:00Z");
        float[] vec = new float[768];
        repo.insert(p, "doc-recency", 0, "hello world", "doc-recency.md", null, vec, when,
                TestContexts.publicLabel());

        List<SearchHit> hits = repo.search(TestContexts.PUBLIC, vec, 5, List.of(p), List.of("doc-recency"));
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).updatedAt()).isEqualTo(when);
    }
}
