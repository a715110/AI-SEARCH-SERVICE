package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.constant.EcwsProjectEnum;
import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingSyncRepository;
import com.dodaso.ecosystem.ai.util.VectorUtil;
import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.container.RESTReqContainer;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.ecws.container.CollaborationTaskDTOContainer;
import com.dodaso.ecosystem.ecws.dto.CollaborationTaskDTO;
import com.dodaso.ecosystem.ecws.dto.TaskCommentDTO;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class AiSyncService {

    private static final Long SYSTEM_USER_ID = 1L;

    @Autowired
    private EcwsEmbeddingRepository embeddingRepository;

    @Autowired
    private EcwsEmbeddingSyncRepository syncRepository;

    @Autowired
    private OllamaService ollamaService;

    @Autowired
    private RESTServiceClient restServiceClient;

    @Lazy
    @Autowired
    private AiSyncService self;

    @Lazy
    @Autowired
    private AiFileSyncService aiFileSyncService;

    @Async
    //@Transactional
    public void syncAsync(AiSearchSyncDTO syncDto) {
        log.info("syncAsync called for {} ID: {}", syncDto.getSourceType(), syncDto.getSourceId());
        if (syncDto.getSourceId() == null) {
            log.error("Cannot sync — sourceId is null. DTO: {}", syncDto);
            return;
        }
        self.sync(syncDto); // Use proxy to honour @Transactional on sync()
    }


    @Transactional(noRollbackFor = Exception.class)
    public void sync(AiSearchSyncDTO syncDto) {
        log.info("Synchronizing {} ID: {}", syncDto.getSourceType(), syncDto.getSourceId());

        if (syncDto.getSourceId() == null) {
            throw new IllegalArgumentException("sourceId must not be null for sync operation");
        }

        Long effectiveUserId = syncDto.getUserId() != null ? syncDto.getUserId() : SYSTEM_USER_ID;

        EcwsEmbeddingSync syncRecord = syncRepository.findBySourceTypeAndSourceId(
                syncDto.getSourceType(), syncDto.getSourceId())
            .orElse(new EcwsEmbeddingSync());

        syncRecord.setSourceType(syncDto.getSourceType());
        syncRecord.setSourceId(syncDto.getSourceId());
        syncRecord.setProjectId(syncDto.getProjectId());
        syncRecord.setWorkspaceId(syncDto.getWorkspaceId());
        syncRecord.setUserId(effectiveUserId);
        syncRecord.setStatus("PENDING");
        syncRecord = syncRepository.save(syncRecord);
        syncRepository.flush();

        try {
            // 1. Generate searchable context
            String context = buildContext(syncDto);

            // 2. Get Embedding (Call Ollama)
            float[] vector = ollamaService.getOllamaEmbedding(context);

            // 3. Save Embedding and updating logic,
            // the old vector data is fully replaced each time sync() is called
            embeddingRepository.deleteBySourceTypeAndSourceId(syncDto.getSourceType(),
                syncDto.getSourceId());
            embeddingRepository.flush();

            EcwsEmbedding embedding = new EcwsEmbedding();
            embedding.setSourceType(syncDto.getSourceType());
            embedding.setSourceId(syncDto.getSourceId());
            embedding.setChunkIndex(0);
            embedding.setChunkText(context);
            embedding.setChunkTokens(context != null ? context.split("\\s+").length : 0);
            embedding.setEmbedding(VectorUtil.toString(vector));
            embedding.setSummary(syncDto.getSummary());
            embedding.setStatus(syncDto.getStatus());
            embedding.setPriority(syncDto.getPriority());
            embedding.setRequestor(syncDto.getRequestor());
            embedding.setDueDate(syncDto.getDueDate());
            embedding.setScheduledDate(syncDto.getScheduledDate());
            embedding.setParentSourceId(syncDto.getParentSourceId());
            embedding.setProjectId(syncDto.getProjectId() != null ? syncDto.getProjectId() : EcwsProjectEnum.ECWS.getProjectId());
            embedding.setWorkspaceId(syncDto.getWorkspaceId() != null ? syncDto.getWorkspaceId() : EmbeddingSourceTypeEnum.COLLABORATION_TASK.getWorkspaceId());
            embedding.setUserId(effectiveUserId);
            embedding.setCreatedByUserId(effectiveUserId);
            embedding.setUpdatedByUserId(effectiveUserId);
            embedding.setSourceUpdatedAt(syncDto.getSourceUpdatedAt());

            embeddingRepository.saveAndFlush(embedding);
            // 3-1. Advisor's VectorStore
//            vectorStore.add(List.of(
//                new Document(
//                    context,
//                    Map.of(
//                        "projectId", syncDto.getProjectId(),
//                        "workspaceId", syncDto.getWorkspaceId(),
//                        "sourceId", syncDto.getSourceId(),
//                        "sourceType", syncDto.getSourceType()
//                    )
//                )
//            ));

            // 4. Update Sync Status
            syncRecord.setStatus("COMPLETED");
            syncRecord.setProcessedAt(Instant.now());
            syncRecord.setErrorMessage(null);
            syncRepository.saveAndFlush(syncRecord);

            log.info("Successfully synchronized {} ID: {}", syncDto.getSourceType(),
                syncDto.getSourceId());
        } catch (Exception e) {
            log.error("Failed to synchronize {} ID: {}", syncDto.getSourceType(),
                syncDto.getSourceId(), e);
            syncRecord.setStatus("FAILED");
            syncRecord.setErrorMessage(e.getMessage());
            syncRecord.setRetryCount(syncRecord.getRetryCount() + 1);
            syncRecord.setNextRetryAt(Instant.now().plusSeconds(60L * syncRecord.getRetryCount()));
            syncRepository.save(syncRecord);
            // Re-throw so refreshCollaborationTasks() can count failures
            throw e;
        }
    }

    public void refreshSync() {
        log.info("Starting refresh sync from source databases");
        refreshCollaborationTasks();
        refreshTaskComments();
        aiFileSyncService.refreshFileSyncs();
    }


    private void refreshCollaborationTasks() {
        try {
            log.info("Fetching collaboration tasks for sync");

            CollaborationTaskDTOContainer requestContainer = new CollaborationTaskDTOContainer();

            RESTReqContainer<CollaborationTaskDTOContainer> restReqContainer =
                new RESTReqContainer<>(
                    ServiceDiscoveryEnum.ecws_service.getServiceDiscoveryName(),
                    "/collaborationTaskController/findCollaborationTasks",
                    requestContainer,
                    new ParameterizedTypeReference<CollaborationTaskDTOContainer>() {
                    },
                    HttpMethod.POST
                );

            CollaborationTaskDTOContainer responseContainer =
                restServiceClient.callRESTService(restReqContainer);

            if (responseContainer != null
                && responseContainer.getCollaborationTaskDTOList() != null) {
                List<CollaborationTaskDTO> tasks = responseContainer.getCollaborationTaskDTOList();
                log.info("Found {} collaboration tasks total", tasks.size());

                int syncedCount = 0;
                int skippedCount = 0;
                int failedCount = 0;

                for (CollaborationTaskDTO task : tasks) {
                    try {
                        if (isAlreadySynced(task)) {
                            skippedCount++;
                            continue;
                        }

                        AiSearchSyncDTO syncDto = AiSearchSyncDTO.builder()
                            .sourceId((long) task.getId())
                            .sourceType(EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType())
                            .workspaceId(EmbeddingSourceTypeEnum.COLLABORATION_TASK.getWorkspaceId())
                            .summary(task.getSummary())
                            .status(task.getStatus())
                            .description(task.getDescription())
                            .priority(task.getPriority())
                            .requestor(task.getRequestor())
                            .dueDate(task.getDueDate())
                            .scheduledDate(task.getScheduledDate())
                            .projectId(EcwsProjectEnum.ECWS.getProjectId())
                            .sourceUpdatedAt(task.getUpdatedAt() != null
                                ? task.getUpdatedAt()
                                : Instant.now())
                            .build();

                        self.sync(syncDto);
                        syncedCount++;

                    } catch (Exception e) {
                        failedCount++;
                        log.error("Failed to sync task ID: {} - {}", task.getId(), e.getMessage());
                    }
                }

                log.info("Refresh sync completed: {} synced, {} skipped, {} failed, {} total",
                    syncedCount, skippedCount, failedCount, tasks.size());

            } else {
                log.warn("No collaboration tasks found from ecws_service");
            }

        } catch (Exception e) {
            log.error("Failed to refresh collaboration tasks for AI sync", e);
        }
    }


    @Transactional
    public void processPendingSyncs() {
        List<EcwsEmbeddingSync> pendingList = syncRepository.findByStatusAndNextRetryAtBefore(
            "FAILED", Instant.now());
        if (pendingList.isEmpty())
            return;

        log.info("Processing {} failed sync tasks", pendingList.size());
        for (EcwsEmbeddingSync sync : pendingList) {
            switch (sync.getSourceType()) {
                case "collaboration_task" -> retryCollaborationTaskSync(sync);
                case "task_comment"       -> retryTaskCommentSync(sync);
                case "task_file_attachment",
                     "comment_file_attachment" -> log.warn(
                         "Retry for file attachment ID: {} not yet implemented — re-upload required",
                         sync.getSourceId());
                default -> log.warn("Unknown sourceType for retry: {}", sync.getSourceType());
            }
        }
    }


    private void retryCollaborationTaskSync(EcwsEmbeddingSync sync) {
        try {
            CollaborationTaskDTO dto = new CollaborationTaskDTO();
            dto.setId(sync.getSourceId().intValue());

            CollaborationTaskDTOContainer requestContainer = new CollaborationTaskDTOContainer();
            requestContainer.setCollaborationTaskDTO(dto);

            RESTReqContainer<CollaborationTaskDTOContainer> restReqContainer =
                new RESTReqContainer<>(
                    ServiceDiscoveryEnum.ecws_service.getServiceDiscoveryName(),
                    "/collaborationTaskController/findCollaborationTaskById",
                    requestContainer,
                    new ParameterizedTypeReference<CollaborationTaskDTOContainer>() {
                    },
                    HttpMethod.POST
                );

            CollaborationTaskDTOContainer responseContainer =
                restServiceClient.callRESTService(restReqContainer);

            CollaborationTaskDTO task = responseContainer.getCollaborationTaskDTO();
            if (task == null)
                return;

            AiSearchSyncDTO syncDto = AiSearchSyncDTO.builder()
                .sourceId((long) task.getId())
                .sourceType(EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType())
                .workspaceId(EmbeddingSourceTypeEnum.COLLABORATION_TASK.getWorkspaceId())
                .summary(task.getSummary())
                .status(task.getStatus())
                .description(task.getDescription())
                .priority(task.getPriority())
                .requestor(task.getRequestor())
                .dueDate(task.getDueDate())
                .scheduledDate(task.getScheduledDate())
                .projectId(EcwsProjectEnum.ECWS.getProjectId())
                .userId(sync.getUserId())
                .sourceUpdatedAt(task.getUpdatedAt() != null ? task.getUpdatedAt() : Instant.now())
                .build();

            sync(syncDto);

        } catch (Exception e) {
            log.error("Failed to retry sync for task ID: {}", sync.getSourceId(), e);
        }
    }


    private void refreshTaskComments() {
        try {
            log.info("Fetching active task comments for sync");

            CollaborationTaskDTOContainer requestContainer = new CollaborationTaskDTOContainer();
            RESTReqContainer<CollaborationTaskDTOContainer> restReqContainer =
                new RESTReqContainer<>(
                    ServiceDiscoveryEnum.ecws_service.getServiceDiscoveryName(),
                    "/collaborationTaskController/findAllActiveComments",
                    requestContainer,
                    new ParameterizedTypeReference<CollaborationTaskDTOContainer>() {},
                    HttpMethod.POST
                );

            CollaborationTaskDTOContainer response = restServiceClient.callRESTService(restReqContainer);

            if (response == null || response.getTaskCommentDTOList() == null) {
                log.warn("No active task comments found from ecws_service");
                return;
            }

            List<TaskCommentDTO> comments = response.getTaskCommentDTOList();
            log.info("Found {} active task comments total", comments.size());

            int syncedCount = 0, skippedCount = 0, failedCount = 0;

            for (TaskCommentDTO comment : comments) {
                try {
                    if (comment.getId() == null) {
                        log.warn("Skipping comment with null ID");
                        skippedCount++;
                        continue;
                    }
                    Optional<EcwsEmbeddingSync> existingSync =
                        syncRepository.findBySourceTypeAndSourceId(
                            EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType(),
                            comment.getId().longValue());
                    if (existingSync.isPresent() && "COMPLETED".equals(existingSync.get().getStatus())
                        && comment.getUpdatedAt() != null
                        && !comment.getUpdatedAt().isAfter(existingSync.get().getProcessedAt())) {
                        skippedCount++;
                        continue;
                    }

                    Long parentTaskId = (comment.getCollaborationTaskDTO() != null)
                        ? (long) comment.getCollaborationTaskDTO().getId()
                        : null;

                    AiSearchSyncDTO syncDto = AiSearchSyncDTO.builder()
                        .sourceId(comment.getId().longValue())
                        .sourceType(EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType())
                        .workspaceId(EmbeddingSourceTypeEnum.TASK_COMMENT.getWorkspaceId())
                        .description(comment.getComment())
                        .requestor(comment.getCreatedBy())
                        .parentSourceId(parentTaskId)
                        .projectId(EcwsProjectEnum.ECWS.getProjectId())
                        .sourceUpdatedAt(comment.getUpdatedAt() != null
                            ? comment.getUpdatedAt() : Instant.now())
                        .build();

                    self.sync(syncDto);
                    syncedCount++;
                } catch (Exception e) {
                    failedCount++;
                    log.error("Failed to sync comment ID: {} - {}", comment.getId(), e.getMessage());
                }
            }

            log.info("Comment sync completed: {} synced, {} skipped, {} failed, {} total",
                syncedCount, skippedCount, failedCount, comments.size());

        } catch (Exception e) {
            log.error("Failed to refresh task comments for AI sync", e);
        }
    }


    private void retryTaskCommentSync(EcwsEmbeddingSync sync) {
        try {
            CollaborationTaskDTOContainer requestContainer = new CollaborationTaskDTOContainer();
            RESTReqContainer<CollaborationTaskDTOContainer> restReqContainer =
                new RESTReqContainer<>(
                    ServiceDiscoveryEnum.ecws_service.getServiceDiscoveryName(),
                    "/collaborationTaskController/findAllActiveComments",
                    requestContainer,
                    new ParameterizedTypeReference<CollaborationTaskDTOContainer>() {},
                    HttpMethod.POST
                );

            CollaborationTaskDTOContainer response = restServiceClient.callRESTService(restReqContainer);
            if (response == null || response.getTaskCommentDTOList() == null) return;

            response.getTaskCommentDTOList().stream()
                .filter(c -> c.getId() != null && sync.getSourceId() != null
                    && Long.valueOf(c.getId().longValue()).equals(sync.getSourceId()))
                .findFirst()
                .ifPresent(comment -> {
                    Long parentTaskId = (comment.getCollaborationTaskDTO() != null)
                        ? (long) comment.getCollaborationTaskDTO().getId()
                        : null;
                    AiSearchSyncDTO syncDto = AiSearchSyncDTO.builder()
                        .sourceId(comment.getId().longValue())
                        .sourceType(EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType())
                        .workspaceId(EmbeddingSourceTypeEnum.TASK_COMMENT.getWorkspaceId())
                        .description(comment.getComment())
                        .requestor(comment.getCreatedBy())
                        .parentSourceId(parentTaskId)
                        .projectId(EcwsProjectEnum.ECWS.getProjectId())
                        .userId(sync.getUserId())
                        .sourceUpdatedAt(comment.getUpdatedAt() != null
                            ? comment.getUpdatedAt() : Instant.now())
                        .build();
                    sync(syncDto);
                });
        } catch (Exception e) {
            log.error("Failed to retry sync for comment ID: {}", sync.getSourceId(), e);
        }
    }


    /**
     * Checks if a task has already been synced and hasn't changed since last sync. Uses a separate
     * read-only check to avoid polluting the transaction.
     */
    private boolean isAlreadySynced(CollaborationTaskDTO task) {
        try {
            Optional<EcwsEmbeddingSync> existingSync = syncRepository.findBySourceTypeAndSourceId(
                "collaboration_task", (long) task.getId());

            if (existingSync.isEmpty()) {
                return false;
            }

            EcwsEmbeddingSync syncRecord = existingSync.get();

            if (!"COMPLETED".equals(syncRecord.getStatus())) {
                return false;
            }

            if (task.getUpdatedAt() != null && syncRecord.getProcessedAt() != null) {
                return !task.getUpdatedAt().isAfter(syncRecord.getProcessedAt());
            }

            return syncRecord.getProcessedAt() != null;
        } catch (Exception e) {
            log.warn("Error checking sync status for task ID: {}, will re-sync", task.getId());
            return false; // If check fails, re-sync to be safe
        }
    }


    private String buildContext(AiSearchSyncDTO dto) {
        return switch (dto.getSourceType()) {
            case "task_comment" -> String.format("Comment: %s | Author: %s",
                nullSafe(dto.getDescription()), nullSafe(dto.getRequestor()));
            case "task_file_attachment", "comment_file_attachment" -> String.format(
                "File: %s | Content: %s",
                nullSafe(dto.getSummary()), nullSafe(dto.getDescription()));
            default -> String.format(
                "Summary: %s | Status: %s | Priority: %s | Requestor: %s | Due: %s | Scheduled: %s | Description: %s",
                nullSafe(dto.getSummary()), nullSafe(dto.getStatus()),
                nullSafe(dto.getPriority()), nullSafe(dto.getRequestor()),
                dto.getDueDate(), dto.getScheduledDate(),
                nullSafe(dto.getDescription()));
        };
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}

