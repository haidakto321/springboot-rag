package com.example.springbootrag.integration;

import com.example.springbootrag.repository.DocEdgeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class DocEdgeRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired DocEdgeRepository repo;
    @Autowired JdbcTemplate jdbc;

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    void insertsNeighborsAndDeletesBySrc() {
        long p = projectId();
        repo.insertLink(p, "A", "B");
        repo.insertLink(p, "A", "B");            // idempotent, no duplicate
        repo.insertHierarchy(p, "A", "C");

        assertThat(repo.neighbors(p, List.of("A")))
                .containsExactlyInAnyOrder("B", "C");
        assertThat(repo.neighbors(p, List.of())).isEmpty();

        repo.deleteBySrcDoc(p, "A");
        assertThat(repo.neighbors(p, List.of("A"))).isEmpty();
    }
}
