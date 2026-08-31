package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync;
import com.dodaso.ecosystem.ai.entity.EcwsSearchHistory;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingSyncRepository;
import com.dodaso.ecosystem.ai.repository.EcwsSearchHistoryRepository;
import com.dodaso.ecosystem.ai.util.VectorUtil;
import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.container.RESTReqContainer;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.ecws.container.CollaborationTaskDTOContainer;
import com.dodaso.ecosystem.ecws.dto.CollaborationTaskDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.springframework.ai.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Slf4j
public class AiSearchService {

    @Autowired
    private EcwsEmbeddingRepository embeddingRepository;

    @Autowired
    private EcwsEmbeddingSyncRepository syncRepository;

    @Autowired
    private EcwsSearchHistoryRepository searchHistoryRepository;

    @Autowired
    OllamaService ollamaService;

    @Autowired
    RESTServiceClient restServiceClient;

    @Autowired
    AiRagService aiRagService;

    @Autowired
    VectorStore vectorStore;

    // Self-injection to ensure Spring proxy is used (fixes @Transactional self-invocation)
    @Lazy
    @Autowired
    private AiSearchService self;
    //private final RestTemplate ollamaRestTemplate = new RestTemplate();

    private static final Long SYSTEM_USER_ID = 1L; // Reserved system user ID

  // private final OllamaChatModel chatModel;
//
   //  public AiSearchService(OllamaChatModel chatModel) {
    //   this.chatModel = chatModel;
  // }
   private final ChatModel chatModel;
private final ChatClient chatClient = null;

  public AiSearchService(ChatModel chatModel) {
    this.chatModel = chatModel;
  }


    public List<AiSearchResultDTO> search(Long projectId, Long workspaceId, String query) {
        log.info("Searching for: '{}' in project: {}, workspace: {}", query, projectId, workspaceId);
        long start = System.currentTimeMillis();

        // 1. Get embedding for query
        float[] queryVector = ollamaService.getOllamaEmbedding(query);

        // 2. Find similar embeddings across all source types
        List<EcwsEmbedding> similar = embeddingRepository.findSimilarAll(projectId, VectorUtil.toString(queryVector), 10);

        // 3. Map to DTOs
        List<AiSearchResultDTO> results = new ArrayList<>();
        List<Long> sourceIds = new ArrayList<>();
        for (EcwsEmbedding emb : similar) {
            Long taskId = resolveTaskId(emb);
            results.add(AiSearchResultDTO.builder()
                .sourceId(emb.getSourceId())
                .sourceType(emb.getSourceType())
                .parentSourceId(emb.getParentSourceId())
                .taskId(taskId)
                .chunkText(emb.getChunkText())
                .score(0.95) // Placeholder score
                .build());
            sourceIds.add(emb.getSourceId());
        }

        // 4. Log search history
        EcwsSearchHistory history = new EcwsSearchHistory();
        history.setQueryText(query);
        history.setResultIds(sourceIds.toString());
        history.setResultCount(results.size());
        history.setSearchLatencyMs(System.currentTimeMillis() - start);
        history.setProjectId(projectId);
        history.setTopK(10);
        history.setWorkspaceId(workspaceId);
        history.setCreatedByUserId(1L);  // placeholder
        history.setUpdatedByUserId(1L);  // placeholder
        history.setUserId(1L); // Placeholder for current user, could be passed in search method
        searchHistoryRepository.save(history);

        return results;
    }



    private Long resolveTaskId(EcwsEmbedding emb) {
        String sourceType = emb.getSourceType();
        if (EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType().equals(sourceType)) {
            return emb.getSourceId();
        }
        if (EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType().equals(sourceType)
                || EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT.getSourceType().equals(sourceType)) {
            return emb.getParentSourceId() != null ? emb.getParentSourceId() : emb.getSourceId();
        }
        if (EmbeddingSourceTypeEnum.COMMENT_FILE_ATTACHMENT.getSourceType().equals(sourceType)
                && emb.getParentSourceId() != null) {
            List<EcwsEmbedding> parentComments = embeddingRepository.findBySourceTypeAndSourceId(
                EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType(), emb.getParentSourceId());
            if (!parentComments.isEmpty() && parentComments.get(0).getParentSourceId() != null) {
                return parentComments.get(0).getParentSourceId();
            }
            return emb.getParentSourceId(); // fallback: comment ID
        }
        return emb.getSourceId(); // last resort fallback
    }

    private float[] getPlaceholderEmbedding(String text) {
        float[] vector = new float[768];
        Random random = new Random(text.hashCode());
        for (int i = 0; i < 768; i++) {
            vector[i] = random.nextFloat();
        }
        return vector;
    }


}
