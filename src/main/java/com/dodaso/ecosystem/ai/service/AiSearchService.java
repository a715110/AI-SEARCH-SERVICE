package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiAlertDTO;
import com.dodaso.ecosystem.ai.dto.AiChatFileDTO;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.dto.AiGeneratedFileDTO;
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
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
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
    EarsAlertService earsAlertService;

    @Autowired
    FileTextExtractorService fileTextExtractorService;

    @Autowired
    AiDocumentService aiDocumentService;

    @Autowired
    VectorStore vectorStore;

    // Self-injection to ensure Spring proxy is used (fixes @Transactional self-invocation)
    @Lazy
    @Autowired
    private AiSearchService self;
    //private final RestTemplate ollamaRestTemplate = new RestTemplate();

    private static final Long SYSTEM_USER_ID = 1L; // Reserved system user ID

    /** Results returned per search (unchanged from before the chunk fix). */
    private static final int RESULT_LIMIT = 10;
    /** Rows fetched per result slot, so chunks of the same file can be collapsed (plan §7.4). */
    private static final int CANDIDATE_FACTOR = 5;

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

        List<AiSearchResultDTO> results = findResults(projectId, query);
        List<Long> sourceIds = new ArrayList<>();
        for (AiSearchResultDTO result : results) {
            sourceIds.add(result.getSourceId());
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

    /**
     * Answers a prompt from the same results search() returns for its search text (the prompt
     * when none is sent), and returns those results as the answer's sources so the page can link
     * the [n] citations. Earlier turns in the request's history are passed to the model.
     */
    public AiGenerateDTO generate(AiGenerateDTO request) {
        if (request == null || request.getGeneratePrompt() == null
            || request.getGeneratePrompt().trim().isEmpty()) {
            throw new IllegalArgumentException("Prompt is empty");
        }
        if (request.getProjectId() == null) {
            throw new IllegalArgumentException("Project is required");
        }
        String prompt = request.getGeneratePrompt().trim();
        long start = System.currentTimeMillis();

        // List<AiSearchResultDTO> sources = findResults(request.getProjectId(), prompt);
        String searchText = request.getSearchText() != null && !request.getSearchText().trim().isEmpty()
            ? request.getSearchText().trim() : prompt;
        // A question with attached files is about those files: no task search and no alerts,
        // unless the user asked to search tasks too
        // boolean fileQuestion = request.getFiles() != null && !request.getFiles().isEmpty();
        boolean fileQuestion = request.getFiles() != null && !request.getFiles().isEmpty()
            && !Boolean.TRUE.equals(request.getSearchTasksWithFiles());
        // List<AiSearchResultDTO> sources = findResults(request.getProjectId(), searchText);
        // List<AiAlertDTO> alerts = findAlerts(request.getLoginId(), sources);
        List<AiSearchResultDTO> sources = fileQuestion ? new ArrayList<>()
            : findResults(request.getProjectId(), searchText);
        List<AiAlertDTO> alerts = fileQuestion ? new ArrayList<>()
            : findAlerts(request.getLoginId(), sources);
        // String answer = aiRagService.answer(prompt, sources);
        // String answer = aiRagService.answer(prompt, sources, request.getHistory());
        // String answer = aiRagService.answer(prompt, sources, request.getHistory(), alerts);
        // String answer = aiRagService.answer(prompt, sources, request.getHistory(), alerts,
        //     request.getLoginId());
        List<AiRagService.AttachedFile> files = extractFiles(request.getFiles());
        // String answer = aiRagService.answer(prompt, sources, request.getHistory(), alerts,
        //     request.getLoginId(), files);
        AiRagService.AiAnswer reply = aiRagService.answerWithFile(prompt, sources, request.getHistory(),
            alerts, request.getLoginId(), files);
        String answer = reply.text();
        List<AiGeneratedFileDTO> generatedFiles = new ArrayList<>();
        if (reply.file() != null && request.getLoginId() != null && !request.getLoginId().isBlank()) {
            try {
                AiDocumentService.Created created = aiDocumentService.create(reply.file(),
                    request.getLoginId(), sources);
                if (created.file() != null) {
                    generatedFiles.add(created.file());
                }
                if (created.note() != null) {
                    answer = answer + "\n\n" + created.note();
                }
            } catch (Exception e) {
                log.error("Could not make the file the answer asked for", e);
                answer = answer + "\n\n" + FILE_FAILED;
            }
        }
        // log.info("Generated answer from {} source(s) in {} ms for project {}",
        //     sources.size(), System.currentTimeMillis() - start, request.getProjectId());
        // log.info("Generated answer from {} source(s) and {} alert(s) in {} ms for project {}",
        //     sources.size(), alerts.size(), System.currentTimeMillis() - start, request.getProjectId());
        log.info("Generated answer from {} source(s), {} alert(s) and {} attached file(s) in {} ms for project {}",
            sources.size(), alerts.size(), files.size(), System.currentTimeMillis() - start,
            request.getProjectId());

        AiGenerateDTO response = new AiGenerateDTO();
        response.setGeneratePrompt(prompt);
        response.setGeneratedText(answer);
        response.setProjectId(request.getProjectId());
        response.setSources(sources);
        response.setAlerts(alerts);
        response.setGeneratedFiles(generatedFiles);
        return response;
    }

    static final String FILE_FAILED = "The file couldn't be made; please ask again.";

    /**
     * The text of the files attached to a question. A file that can't be read (an image, an
     * unsupported type, a broken document) is kept with null text, so the answer can say so.
     */
    List<AiRagService.AttachedFile> extractFiles(List<AiChatFileDTO> files) {
        List<AiRagService.AttachedFile> extracted = new ArrayList<>();
        if (files == null) {
            return extracted;
        }
        for (AiChatFileDTO file : files) {
            if (file == null || file.getFileName() == null || file.getContent() == null) {
                continue;
            }
            String text = null;
            try {
                text = fileTextExtractorService.extract(
                    new ByteArrayInputStream(file.getContent()), file.getFileName());
            } catch (Exception e) {
                log.warn("Could not read attached file {}: {}", file.getFileName(), e.getMessage());
            }
            extracted.add(new AiRagService.AttachedFile(file.getFileName(), text));
        }
        return extracted;
    }

    /**
     * EARS alerts for an answer: the user's unread past-due ones and newest ones (when the request
     * has a login id), then those on the source tasks, once each, in that order.
     */
    List<AiAlertDTO> findAlerts(String loginId, List<AiSearchResultDTO> sources) {
        Map<Integer, AiAlertDTO> byId = new LinkedHashMap<>();
        // List<AiAlertDTO> found = new ArrayList<>(earsAlertService.findUserAlerts(loginId));
        List<AiAlertDTO> found = new ArrayList<>(earsAlertService.findUserPastDueAlerts(loginId));
        found.addAll(earsAlertService.findUserAlerts(loginId));
        found.addAll(earsAlertService.findTaskAlerts(
            sources.stream().map(AiSearchResultDTO::getTaskId).collect(Collectors.toList())));
        for (AiAlertDTO alert : found) {
            if (alert != null) {
                byId.putIfAbsent(alert.getId(), alert);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Closest results for a query, at most RESULT_LIMIT, each linked to its task. Used by search
     * and by generate, which answers from these same results. Doesn't write search history.
     */
    List<AiSearchResultDTO> findResults(Long projectId, String query) {
        // 1. Get embedding for query
        float[] queryVector = ollamaService.getOllamaEmbedding(query);

        // 2. Find similar embeddings across all source types
        // ATTACH-CS: was the top 10 rows; a file now has one row per chunk, so one file could take
        // several result slots (plan §7.4). Fetch more rows and keep each source's best chunk.
        // List<EcwsEmbedding> similar = embeddingRepository.findSimilarAll(projectId, VectorUtil.toString(queryVector), 10);
        // ATTACH-CS: was cut to RESULT_LIMIT here, before results without a task are skipped below,
        // so a query whose closest rows all lacked a task returned nothing (plan §7.5). All
        // candidates are kept; the loop below stops at RESULT_LIMIT.
        // List<EcwsEmbedding> similar = bestChunkPerSource(
        //     embeddingRepository.findSimilarAll(projectId, VectorUtil.toString(queryVector),
        //         RESULT_LIMIT * CANDIDATE_FACTOR),
        //     RESULT_LIMIT);
        List<EcwsEmbedding> similar = bestChunkPerSource(
            embeddingRepository.findSimilarAll(projectId, VectorUtil.toString(queryVector),
                RESULT_LIMIT * CANDIDATE_FACTOR),
            RESULT_LIMIT * CANDIDATE_FACTOR);

        // 3. Map to DTOs
        List<AiSearchResultDTO> results = new ArrayList<>();
        for (EcwsEmbedding emb : similar) {
            if (results.size() >= RESULT_LIMIT) {
                break;
            }
            Long taskId = resolveTaskId(emb);
            // Every result is shown as a link to its task; without one it can't be opened (§7.5).
            if (taskId == null) {
                log.warn("Skipping search result {} {}: owning task unknown",
                    emb.getSourceType(), emb.getSourceId());
                continue;
            }
            results.add(AiSearchResultDTO.builder()
                .sourceId(emb.getSourceId())
                .sourceType(emb.getSourceType())
                .parentSourceId(emb.getParentSourceId())
                .taskId(taskId)
                .chunkText(emb.getChunkText())
                .score(0.95) // Placeholder score
                .build());
        }
        return results;
    }



    /**
     * Keeps the first (closest) row per source_type + source_id, in similarity order, up to limit.
     */
    private static List<EcwsEmbedding> bestChunkPerSource(List<EcwsEmbedding> rows, int limit) {
        Map<String, EcwsEmbedding> best = new LinkedHashMap<>();
        for (EcwsEmbedding emb : rows) {
            if (best.size() >= limit) {
                break;
            }
            best.putIfAbsent(emb.getSourceType() + ":" + emb.getSourceId(), emb);
        }
        return new ArrayList<>(best.values());
    }

    /**
     * Task a result opens (plan §7.5). Uses task_id when set; rows written before that column
     * existed fall back to the old lookups. Returns null when the task can't be known: the old
     * fallbacks used a comment or file id as a task id and opened the wrong task.
     */
    Long resolveTaskId(EcwsEmbedding emb) {
        if (emb.getTaskId() != null) {
            return emb.getTaskId();
        }
        String sourceType = emb.getSourceType();
        if (EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType().equals(sourceType)) {
            return emb.getSourceId();
        }
        if (EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType().equals(sourceType)
                || EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT.getSourceType().equals(sourceType)) {
            // ATTACH-CS: was the source id when parent_source_id is null, i.e. a comment or file id
            // used as a task id (plan §7.5).
            // return emb.getParentSourceId() != null ? emb.getParentSourceId() : emb.getSourceId();
            return emb.getParentSourceId();
        }
        if (EmbeddingSourceTypeEnum.COMMENT_FILE_ATTACHMENT.getSourceType().equals(sourceType)
                && emb.getParentSourceId() != null) {
            List<EcwsEmbedding> parentComments = embeddingRepository.findBySourceTypeAndSourceId(
                EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType(), emb.getParentSourceId());
            if (!parentComments.isEmpty() && parentComments.get(0).getParentSourceId() != null) {
                return parentComments.get(0).getParentSourceId();
            }
            // ATTACH-CS: was the comment id used as a task id (plan §7.5).
            // return emb.getParentSourceId(); // fallback: comment ID
            return null;
        }
        // ATTACH-CS: was the source id as a last resort (plan §7.5).
        // return emb.getSourceId(); // last resort fallback
        return null;
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
