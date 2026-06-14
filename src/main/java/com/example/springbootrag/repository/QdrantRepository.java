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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
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

    public void upsert(long id, String docId, int chunkIndex, String content, float[] embedding)
            throws ExecutionException, InterruptedException {
        PointStruct point = PointStruct.newBuilder()
                .setId(id(id))
                .setVectors(vectors(embedding))
                .putAllPayload(Map.of(
                        "doc_id", value(docId),
                        "chunk_index", value((long) chunkIndex),
                        "content", value(content)))
                .build();
        client.upsertAsync(collection, List.of(point)).get();
    }

    public List<SearchHit> search(float[] queryEmbedding, int topK)
            throws ExecutionException, InterruptedException {
        List<Float> vec = new ArrayList<>(queryEmbedding.length);
        for (float f : queryEmbedding) vec.add(f);

        List<ScoredPoint> points = client.searchAsync(SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(vec)
                .setLimit(topK)
                .setWithPayload(enable(true))
                .build()).get();

        List<SearchHit> hits = new ArrayList<>();
        for (ScoredPoint p : points) {
            Map<String, Value> payload = p.getPayloadMap();
            hits.add(new SearchHit(
                    p.getId().getNum(),
                    payload.get("doc_id").getStringValue(),
                    (int) payload.get("chunk_index").getIntegerValue(),
                    payload.get("content").getStringValue(),
                    p.getScore()));
        }
        return hits;
    }

    public void deleteByDocId(String docId) throws ExecutionException, InterruptedException {
        client.deleteAsync(collection,
                io.qdrant.client.grpc.Points.Filter.newBuilder()
                        .addMust(matchKeyword("doc_id", docId))
                        .build()).get();
    }
}
