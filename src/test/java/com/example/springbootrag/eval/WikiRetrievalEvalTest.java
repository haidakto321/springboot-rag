package com.example.springbootrag.eval;

import com.example.springbootrag.repository.ProjectRepository;
import com.example.springbootrag.service.SearchService;
import com.example.springbootrag.web.dto.ProjectSummary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality report for the imported wiki corpus (golden-wiki.yaml).
 *
 * <p>Unlike {@code RetrievalEvalTest} this builds NO corpus of its own: the wiki clone is private
 * and re-embedding 7,536 chunks per run costs hours. It therefore declares no Testcontainers, so
 * Spring uses application.yml and queries the LIVE local stack, reading whatever is already
 * imported.
 *
 * <p>READ-ONLY BY CONSTRUCTION: only SearchService and ProjectRepository are injected, so there is
 * no code path here that can write or delete. Do NOT add IngestService to this class.
 *
 * <p>Prereqs: Postgres 5432 + Qdrant 6334 up, Ollama 11434 with nomic-embed-text pulled, and the
 * wiki already imported into a project named "docmaster" (override with -Deval.wiki.project=NAME).
 *
 * <p>Run: ./mvnw test "-Dgroups=eval-wiki" "-DexcludedGroups="
 */
@SpringBootTest
@Tag("eval-wiki")
@TestPropertySource(properties = "spring.sql.init.mode=never")
class WikiRetrievalEvalTest {

    static final String GOLDEN = "/eval/golden-wiki.yaml";
    static final int TOP_K = 10;
    static final List<String> BACKENDS =
            List.of("fts", "pgvector", "qdrant", "hybrid", "rerank", "graph");

    @Autowired SearchService searchService;
    @Autowired ProjectRepository projects;

    @Test
    void wikiRetrievalReport() {
        ProjectSummary project = requireCorpus();
        List<GoldenEntry> golden = GoldenSet.load(GOLDEN);
        assertThat(golden).isNotEmpty();

        System.out.printf("%nWiki retrieval eval: project=%s (id=%d, %d docs, %d chunks), "
                        + "%d questions, topK=%d%n",
                project.name(), project.id(), project.docCount(), project.chunkCount(),
                golden.size(), TOP_K);
    }

    /**
     * Resolves the corpus project by NAME (never a hardcoded id) and SKIPS the test when it is
     * absent. A fresh clone of this repo can never have the private wiki, so a hard failure there
     * would be permanent and meaningless.
     */
    private ProjectSummary requireCorpus() {
        String name = System.getProperty("eval.wiki.project", "docmaster");

        List<ProjectSummary> all;
        try {
            all = projects.listWithCounts();
        } catch (DataAccessException e) {
            return Assumptions.abort(
                    "Postgres is not reachable - start the stack before running the wiki eval. "
                            + "Looking for project '" + name + "'. Cause: " + e.getMessage());
        }

        Optional<ProjectSummary> found = all.stream()
                .filter(p -> p.name().equalsIgnoreCase(name))
                .findFirst();
        Assumptions.assumeTrue(found.isPresent(),
                "no project named '" + name + "' - import the wiki first, or pass "
                        + "-Deval.wiki.project=<name>. Projects present: "
                        + all.stream().map(ProjectSummary::name).toList());

        ProjectSummary project = found.get();
        Assumptions.assumeTrue(project.chunkCount() > 0,
                "project '" + name + "' has 0 chunks - nothing to evaluate");
        return project;
    }
}
