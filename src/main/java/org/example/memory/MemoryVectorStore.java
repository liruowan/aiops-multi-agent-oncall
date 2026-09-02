package org.example.memory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.example.service.VectorEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class MemoryVectorStore {
    private static final Logger logger = LoggerFactory.getLogger(MemoryVectorStore.class);

    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;
    private final MemoryProperties properties;
    private final Gson gson = new Gson();

    public MemoryVectorStore(MilvusServiceClient milvusClient,
                             VectorEmbeddingService embeddingService,
                             MemoryProperties properties) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    public void index(MemoryEntry entry) {
        try {
            List<Float> vector = embeddingService.generateEmbedding(entry.getContent());
            JsonObject metadata = gson.toJsonTree(entry.getMetadata()).getAsJsonObject();
            List<InsertParam.Field> fields = List.of(
                    new InsertParam.Field("id", Collections.singletonList(entry.getId())),
                    new InsertParam.Field("session_id", Collections.singletonList(entry.getSessionId())),
                    new InsertParam.Field("memory_type", Collections.singletonList(entry.getType().name())),
                    new InsertParam.Field("content", Collections.singletonList(entry.getContent())),
                    new InsertParam.Field("vector", Collections.singletonList(vector)),
                    new InsertParam.Field("created_at", Collections.singletonList(entry.getCreatedAt())),
                    new InsertParam.Field("metadata", Collections.singletonList(metadata))
            );
            R<MutationResult> response = milvusClient.insert(InsertParam.newBuilder()
                    .withCollectionName(properties.getCollectionName())
                    .withFields(fields)
                    .build());
            if (response.getStatus() != 0) {
                throw new IllegalStateException(response.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Memory vector indexing unavailable for entry {}: {}", entry.getId(), e.getMessage());
        }
    }

    public List<VectorCandidate> search(String sessionId, String query, int topK) {
        try {
            List<Float> queryVector = embeddingService.generateQueryVector(query);
            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(properties.getCollectionName())
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(topK)
                    .withMetricType(MetricType.L2)
                    .withExpr("session_id == \"" + escape(sessionId) + "\"")
                    .withOutFields(List.of("id", "session_id", "memory_type", "content", "created_at", "metadata"))
                    .withParams("{\"nprobe\":10}")
                    .build();
            R<SearchResults> response = milvusClient.search(param);
            if (response.getStatus() != 0) {
                throw new IllegalStateException(response.getMessage());
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<VectorCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                MemoryEntry entry = new MemoryEntry();
                entry.setId(String.valueOf(wrapper.getFieldData("id", 0).get(i)));
                entry.setSessionId(String.valueOf(wrapper.getFieldData("session_id", 0).get(i)));
                entry.setType(MemoryType.valueOf(String.valueOf(wrapper.getFieldData("memory_type", 0).get(i))));
                entry.setContent(String.valueOf(wrapper.getFieldData("content", 0).get(i)));
                entry.setCreatedAt(((Number) wrapper.getFieldData("created_at", 0).get(i)).longValue());
                Object metadata = wrapper.getFieldData("metadata", 0).get(i);
                if (metadata != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> values = gson.fromJson(metadata.toString(), Map.class);
                    entry.setMetadata(values);
                }
                float l2Distance = wrapper.getIDScore(0).get(i).getScore();
                candidates.add(new VectorCandidate(entry, 1.0 / (1.0 + Math.max(0.0, l2Distance))));
            }
            return candidates;
        } catch (Exception e) {
            logger.warn("Memory vector search unavailable; keyword fallback will be used: {}", e.getMessage());
            return List.of();
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record VectorCandidate(MemoryEntry entry, double similarity) {}
}
