package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

/** Task id on embeddings and search results (plan PLAN-attachment-to-common-service §7.5). */
class AiSearchTaskIdTest {

    private static final String TASK = EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType();
    private static final String COMMENT = EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType();
    private static final String TASK_FILE = EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT.getSourceType();
    private static final String COMMENT_FILE = EmbeddingSourceTypeEnum.COMMENT_FILE_ATTACHMENT.getSourceType();

    private EcwsEmbeddingRepository embeddingRepository;
    private AiSearchService searchService;

    @BeforeEach
    void setUp() {
        embeddingRepository = mock(EcwsEmbeddingRepository.class);
        searchService = new AiSearchService(mock(ChatModel.class));
        ReflectionTestUtils.setField(searchService, "embeddingRepository", embeddingRepository);
    }

    private static AiSearchSyncDTO dto(String type, Long sourceId, Long parent, Long taskId) {
        return AiSearchSyncDTO.builder().sourceType(type).sourceId(sourceId)
            .parentSourceId(parent).taskId(taskId).build();
    }

    private static EcwsEmbedding emb(String type, Long sourceId, Long parent, Long taskId) {
        EcwsEmbedding e = new EcwsEmbedding();
        e.setSourceType(type);
        e.setSourceId(sourceId);
        e.setParentSourceId(parent);
        e.setTaskId(taskId);
        return e;
    }

    // ---- taskIdFor (write side) ----

    @Test
    void taskIdForDerivesFromTypeWhenNotSent() {
        assertEquals(7L, AiSyncService.taskIdFor(dto(TASK, 7L, null, null)));
        assertEquals(70L, AiSyncService.taskIdFor(dto(COMMENT, 423L, 70L, null)));
        assertEquals(70L, AiSyncService.taskIdFor(dto(TASK_FILE, 536L, 70L, null)));
    }

    @Test
    void taskIdForCommentFileNeedsCallerValue() {
        assertNull(AiSyncService.taskIdFor(dto(COMMENT_FILE, 500L, 404L, null)));
        assertEquals(97L, AiSyncService.taskIdFor(dto(COMMENT_FILE, 500L, 404L, 97L)));
    }

    @Test
    void taskIdForPrefersCallerValue() {
        assertEquals(5L, AiSyncService.taskIdFor(dto(COMMENT, 1L, 9L, 5L)));
    }

    @Test
    void taskIdForTaskFileWithoutParentIsNull() {
        assertNull(AiSyncService.taskIdFor(dto(TASK_FILE, 470L, null, null)));
    }

    // ---- resolveTaskId (read side) ----

    @Test
    void usesStoredTaskIdWithoutLookup() {
        assertEquals(97L, searchService.resolveTaskId(emb(COMMENT_FILE, 500L, 404L, 97L)));
        verify(embeddingRepository, never()).findBySourceTypeAndSourceId(anyString(), anyLong());
    }

    @Test
    void oldCommentFileRowResolvesThroughCommentEmbedding() {
        when(embeddingRepository.findBySourceTypeAndSourceId(COMMENT, 404L))
            .thenReturn(List.of(emb(COMMENT, 404L, 97L, null)));
        assertEquals(97L, searchService.resolveTaskId(emb(COMMENT_FILE, 500L, 404L, null)));
    }

    @Test
    void oldCommentFileRowWithoutCommentEmbeddingIsNullNotCommentId() {
        when(embeddingRepository.findBySourceTypeAndSourceId(COMMENT, 404L)).thenReturn(List.of());
        assertNull(searchService.resolveTaskId(emb(COMMENT_FILE, 500L, 404L, null)));
    }

    @Test
    void oldTaskFileRowWithoutParentIsNullNotFileId() {
        assertNull(searchService.resolveTaskId(emb(TASK_FILE, 470L, null, null)));
    }

    @Test
    void oldRowsWithParentStillResolve() {
        assertEquals(7L, searchService.resolveTaskId(emb(TASK, 7L, null, null)));
        assertEquals(70L, searchService.resolveTaskId(emb(COMMENT, 423L, 70L, null)));
        assertEquals(70L, searchService.resolveTaskId(emb(TASK_FILE, 536L, 70L, null)));
    }

    // ---- search ----

    @Test
    void searchSkipsRowsWithoutTaskAndStillFillsResults() {
        OllamaService ollama = mock(OllamaService.class);
        when(ollama.getOllamaEmbedding(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        ReflectionTestUtils.setField(searchService, "ollamaService", ollama);
        ReflectionTestUtils.setField(searchService, "searchHistoryRepository",
            mock(com.dodaso.ecosystem.ai.repository.EcwsSearchHistoryRepository.class));

        // 12 closest rows have no task (old task-file rows without a parent), then 12 tasks
        List<EcwsEmbedding> rows = new java.util.ArrayList<>();
        for (long i = 1; i <= 12; i++) {
            rows.add(emb(TASK_FILE, 400 + i, null, null));
        }
        for (long i = 1; i <= 12; i++) {
            rows.add(emb(TASK, i, null, null));
        }
        when(embeddingRepository.findSimilarAll(org.mockito.ArgumentMatchers.eq(1L), anyString(),
            org.mockito.ArgumentMatchers.anyInt())).thenReturn(rows);

        List<com.dodaso.ecosystem.ai.dto.AiSearchResultDTO> results = searchService.search(1L, 2L, "q");

        assertEquals(10, results.size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L),
            results.stream().map(com.dodaso.ecosystem.ai.dto.AiSearchResultDTO::getTaskId).toList());
    }

    @Test
    void unknownTypeIsNull() {
        assertNull(searchService.resolveTaskId(emb("something_else", 1L, null, null)));
    }
}
