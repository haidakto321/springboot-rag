package com.example.springbootrag.repository;

import com.example.springbootrag.config.EmbeddingProperties;
import com.example.springbootrag.model.SearchHit;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.JsonWithInt.Value;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

@Repository
public class QdrantRepository {

    private static final Logger log = LoggerFactory.getLogger(QdrantRepository.class);

    private final QdrantClient client;
    private final EmbeddingProperties props;
    private final String collection;

    public QdrantRepository(QdrantClient client,
                            EmbeddingProperties props,
                            @org.springframework.beans.factory.annotation.Value("${app.qdrant.collection}") String collection) {
        this.client = client;
        this.props = props;
        this.collection = collection;
    }

    /**
     * Creates the collection if missing. Failures here (e.g. Qdrant down at startup) are
     * logged but do not abort context startup, so the app can still be used with the
     * Postgres-backed backends (FTS / pgvector). Qdrant calls made later will surface their
     * own errors.
     */
    @PostConstruct
    public void ensureCollection() {
        try {
            boolean exists = client.collectionExistsAsync(collection).get();
            if (!exists) {
                client.createCollectionAsync(CreateCollection.newBuilder()
                        .setCollectionName(collection)
                        .setVectorsConfig(VectorsConfig.newBuilder()
                                .setParams(VectorParams.newBuilder()
                                        .setSize(props.getDimension())
                                        .setDistance(Distance.Cosine)
                                        .build())
                                .build())
                        .build()).get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while ensuring Qdrant collection '{}'", collection, e);
        } catch (Exception e) {
            log.warn("Could not ensure Qdrant collection '{}' at startup (Qdrant reachable?). "
                    + "App will start; Qdrant-backed search will fail until it is available.", collection, e);
        }
    }

    /** Upserts one chunk with its project association into Qdrant. */
    public void upsert(long id, long projectId, String docId, int chunkIndex, String content,
                       String sourceFile, String headingPath, float[] embedding)
            throws ExecutionException, InterruptedException {
        Map<String, Value> payload = new HashMap<>();
        payload.put("project_id", value(projectId));
        payload.put("doc_id", value(docId));
        payload.put("chunk_index", value((long) chunkIndex));
        payload.put("content", value(content));
        if (sourceFile != null) {
            payload.put("source_file", value(sourceFile));
        }
        if (headingPath != null) {
            payload.put("heading_path", value(headingPath));
        }
        PointStruct point = PointStruct.newBuilder()
                .setId(id(id))
                .setVectors(vectors(embedding))
                .putAllPayload(payload)
                .build();
        client.upsertAsync(collection, List.of(point)).get();
    }

    /**
     * Vector search with optional project and doc filters.
     * Empty lists mean that filter is absent. Project uses integer match; doc uses keyword match.
     * Both active: must satisfy BOTH (AND); within each group it is an OR over the values.
     */
    public List<SearchHit> search(float[] queryEmbedding, int topK,
                                  List<Long> projectIds, List<String> docIds)
            throws ExecutionException, InterruptedException {
        List<Float> vec = new ArrayList<>(queryEmbedding.length);
        for (float f : queryEmbedding) vec.add(f);

        SearchPoints.Builder search = SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(vec)
                .setLimit(topK)
                .setWithPayload(enable(true));

        boolean hasProject = projectIds != null && !projectIds.isEmpty();
        boolean hasDoc = docIds != null && !docIds.isEmpty();
        if (hasProject || hasDoc) {
            io.qdrant.client.grpc.Points.Filter.Builder filter =
                    io.qdrant.client.grpc.Points.Filter.newBuilder();
            if (hasProject) {
                io.qdrant.client.grpc.Points.Filter.Builder pf =
                        io.qdrant.client.grpc.Points.Filter.newBuilder();
                for (Long pid : projectIds) pf.addShould(match("project_id", pid));
                filter.addMust(io.qdrant.client.grpc.Points.Condition.newBuilder()
                        .setFilter(pf.build()).build());
            }
            if (hasDoc) {
                io.qdrant.client.grpc.Points.Filter.Builder df =
                        io.qdrant.client.grpc.Points.Filter.newBuilder();
                for (String d : docIds) df.addShould(matchKeyword("doc_id", d));
                filter.addMust(io.qdrant.client.grpc.Points.Condition.newBuilder()
                        .setFilter(df.build()).build());
            }
            search.setFilter(filter.build());
        }

        List<ScoredPoint> points = client.searchAsync(search.build()).get();

        List<SearchHit> hits = new ArrayList<>();
        for (ScoredPoint p : points) {
            Map<String, Value> payload = p.getPayloadMap();
            hits.add(new SearchHit(
                    p.getId().getNum(),
                    payload.get("doc_id").getStringValue(),
                    (int) payload.get("chunk_index").getIntegerValue(),
                    payload.get("content").getStringValue(),
                    payload.containsKey("source_file") ? payload.get("source_file").getStringValue() : null,
                    payload.containsKey("heading_path") ? payload.get("heading_path").getStringValue() : null,
                    p.getScore(),
                    null));
        }
        return hits;
    }

    /** Deletes all Qdrant points for the given project+doc combination. */
    public void deleteByDocId(long projectId, String docId) throws ExecutionException, InterruptedException {
        client.deleteAsync(collection,
                io.qdrant.client.grpc.Points.Filter.newBuilder()
                        .addMust(match("project_id", projectId))
                        .addMust(matchKeyword("doc_id", docId))
                        .build()).get();
    }

    /** Deletes all Qdrant points belonging to the given project. */
    public void deleteByProject(long projectId) throws ExecutionException, InterruptedException {
        client.deleteAsync(collection,
                io.qdrant.client.grpc.Points.Filter.newBuilder()
                        .addMust(match("project_id", projectId))
                        .build()).get();
    }
}
