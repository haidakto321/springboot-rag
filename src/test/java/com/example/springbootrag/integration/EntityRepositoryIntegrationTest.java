package com.example.springbootrag.integration;

import com.example.springbootrag.repository.EntityRepository;
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
class EntityRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired EntityRepository repo;
    @Autowired JdbcTemplate jdbc;

    private long projectId() {
        return jdbc.queryForObject("SELECT id FROM projects ORDER BY id LIMIT 1", Long.class);
    }

    @Test
    void upsertMatchNeighborAndGc() {
        long p = projectId();
        long alice = repo.upsertEntity(p, "Alice", "team");
        long svc = repo.upsertEntity(p, "PaymentsService", "service");
        long sameAlice = repo.upsertEntity(p, "  alice ", "team");   // normalized dup
        assertThat(sameAlice).isEqualTo(alice);

        repo.insertEdge(p, alice, svc, "owns");
        assertThat(repo.matchEntityIds(p, List.of("Alice"), 1)).containsExactly(alice);
        assertThat(repo.neighborEntityIds(p, List.of(alice))).containsExactly(svc);

        // no chunk links -> both are orphans -> gc removes them
        repo.gcOrphanEntities(p);
        assertThat(repo.matchEntityIds(p, List.of("Alice"), 1)).isEmpty();
    }
}
