package com.dodaso.ecosystem.ai.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingSyncRepository;
import com.dodaso.ecosystem.baseline.common.container.RESTReqContainer;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.ecws.container.CollaborationTaskDTOContainer;
import com.dodaso.ecosystem.ecws.dto.CollaborationTaskDTO;
import com.dodaso.ecosystem.ecws.dto.TaskCommentDTO;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Task and comment embedding cleanup in refreshSync (plan PLAN-attachment-to-common-service §7.3).
 */
class AiSyncServiceCleanupTest {

    private static final String TASK = EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType();
    private static final String COMMENT = EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType();

    private static final String TASKS_EP = "/collaborationTaskController/findCollaborationTasks";
    private static final String ACTIVE_COMMENTS_EP = "/collaborationTaskController/findAllActiveComments";
    private static final String LIVE_COMMENT_IDS_EP = "/collaborationTaskController/findLiveCommentIds";

    private EcwsEmbeddingRepository embeddingRepository;
    private EcwsEmbeddingSyncRepository syncRepository;
    private RESTServiceClient restServiceClient;
    private AiSyncService service;

    /** endpoint → response; a RuntimeException value is thrown instead of returned */
    private final Map<String, Object> responses = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        embeddingRepository = mock(EcwsEmbeddingRepository.class);
        syncRepository = mock(EcwsEmbeddingSyncRepository.class);
        restServiceClient = mock(RESTServiceClient.class);

        service = new AiSyncService();
        ReflectionTestUtils.setField(service, "embeddingRepository", embeddingRepository);
        ReflectionTestUtils.setField(service, "syncRepository", syncRepository);
        ReflectionTestUtils.setField(service, "restServiceClient", restServiceClient);
        ReflectionTestUtils.setField(service, "ollamaService", mock(OllamaService.class));
        ReflectionTestUtils.setField(service, "aiFileSyncService", mock(AiFileSyncService.class));
        ReflectionTestUtils.setField(service, "self", service);

        // every live task counts as already synced, so refresh never embeds
        EcwsEmbeddingSync completed = new EcwsEmbeddingSync();
        completed.setStatus("COMPLETED");
        completed.setProcessedAt(Instant.now());
        when(syncRepository.findBySourceTypeAndSourceId(anyString(), anyLong()))
            .thenReturn(Optional.of(completed));

        // nothing to embed from the active-comment list
        responses.put(ACTIVE_COMMENTS_EP, comments());
        when(restServiceClient.callRESTService(any(RESTReqContainer.class))).thenAnswer(inv -> {
            RESTReqContainer<?> req = inv.getArgument(0);
            Object r = responses.get(req.getServiceCommandEndPoint());
            if (r instanceof RuntimeException e) {
                throw e;
            }
            return r;
        });
        stored(TASK, List.of(), List.of());
        stored(COMMENT, List.of(), List.of());
    }

    private void stored(String sourceType, List<Long> embeddingIds, List<Long> syncIds) {
        when(embeddingRepository.findDistinctSourceIdsBySourceTypeIn(List.of(sourceType))).thenReturn(embeddingIds);
        when(syncRepository.findDistinctSourceIdsBySourceTypeIn(List.of(sourceType))).thenReturn(syncIds);
    }

    private static CollaborationTaskDTOContainer tasks(Integer... ids) {
        CollaborationTaskDTOContainer c = new CollaborationTaskDTOContainer();
        c.setCollaborationTaskDTOList(Arrays.stream(ids).map(id -> {
            CollaborationTaskDTO t = new CollaborationTaskDTO();
            t.setId(id);
            return t;
        }).toList());
        return c;
    }

    private static CollaborationTaskDTOContainer comments(Integer... ids) {
        CollaborationTaskDTOContainer c = new CollaborationTaskDTOContainer();
        c.setTaskCommentDTOList(Arrays.stream(ids).map(id -> {
            TaskCommentDTO t = new TaskCommentDTO();
            t.setId(id);
            return t;
        }).toList());
        return c;
    }

    private void verifyDeleted(String sourceType, long id) {
        verify(embeddingRepository).deleteBySourceTypeAndSourceId(sourceType, id);
        verify(syncRepository).deleteBySourceTypeAndSourceId(sourceType, id);
    }

    private void verifyNothingDeleted(String sourceType) {
        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(eq(sourceType), anyLong());
        verify(syncRepository, never()).deleteBySourceTypeAndSourceId(eq(sourceType), anyLong());
    }

    private void verifyNotDeleted(String sourceType, long id) {
        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(sourceType, id);
        verify(syncRepository, never()).deleteBySourceTypeAndSourceId(sourceType, id);
    }

    // ---- tasks ----

    @Test
    void removesTasksMissingFromEcws() {
        stored(TASK, List.of(1L, 2L, 3L), List.of(3L, 4L));
        responses.put(TASKS_EP, tasks(1, 3));
        responses.put(LIVE_COMMENT_IDS_EP, comments());

        service.refreshSync();

        verifyDeleted(TASK, 2L);   // embedding only
        verifyDeleted(TASK, 4L);   // sync record only
        verifyNotDeleted(TASK, 1L);
        verifyNotDeleted(TASK, 3L);
        verifyNothingDeleted(COMMENT);
    }

    @Test
    void keepsTasksWhenEcwsReturnsNoTasks() {
        stored(TASK, List.of(1L, 2L), List.of());
        responses.put(TASKS_EP, tasks());

        service.refreshSync();

        verifyNothingDeleted(TASK);
    }

    @Test
    void keepsTasksWhenTaskCallFails() {
        stored(TASK, List.of(1L, 2L), List.of());
        responses.put(TASKS_EP, new RuntimeException("ecws down"));

        service.refreshSync();

        verifyNothingDeleted(TASK);
    }

    // ---- comments ----

    @Test
    void removesCommentsMissingFromLiveIds() {
        responses.put(TASKS_EP, tasks(1));
        stored(COMMENT, List.of(10L, 11L), List.of(12L));
        // 10 isn't in the active list (NULL active_ind) but still exists → kept
        responses.put(LIVE_COMMENT_IDS_EP, comments(10));

        service.refreshSync();

        verifyDeleted(COMMENT, 11L);
        verifyDeleted(COMMENT, 12L);
        verifyNotDeleted(COMMENT, 10L);
        verifyNothingDeleted(TASK);
    }

    @Test
    void keepsCommentsWhenLiveIdListIsEmpty() {
        responses.put(TASKS_EP, tasks(1));
        stored(COMMENT, List.of(10L), List.of());
        responses.put(LIVE_COMMENT_IDS_EP, comments());

        service.refreshSync();

        verifyNothingDeleted(COMMENT);
    }

    @Test
    void keepsCommentsWhenLiveIdCallReturnsNull() {
        responses.put(TASKS_EP, tasks(1));
        stored(COMMENT, List.of(10L), List.of());
        responses.put(LIVE_COMMENT_IDS_EP, null);

        service.refreshSync();

        verifyNothingDeleted(COMMENT);
    }

    @Test
    void commentCallFailureDoesNotStopTaskCleanup() {
        stored(TASK, List.of(1L, 2L), List.of());
        responses.put(TASKS_EP, tasks(1));
        stored(COMMENT, List.of(10L), List.of());
        responses.put(LIVE_COMMENT_IDS_EP, new RuntimeException("old ecws without endpoint"));

        service.refreshSync();

        verifyDeleted(TASK, 2L);
        verifyNothingDeleted(COMMENT);
    }
}
