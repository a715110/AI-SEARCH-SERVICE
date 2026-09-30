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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
            embedding.setTaskId(taskIdFor(syncDto));
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

    /**
     * File-level sync: stores every chunk of one source as its own embedding row (chunkIndex
     * 0..n-1). sync() replaces all rows of a source on each call, so calling it per chunk kept
     * only the last chunk (plan PLAN-attachment-to-common-service §7.4, C12b). All vectors are
     * generated before the old rows are deleted, so a failed embedding call leaves the previous
     * embeddings in place. The sync record is updated once for the whole file.
     */
    @Transactional(noRollbackFor = Exception.class)
    public void syncChunks(String sourceType, Long sourceId, List<AiSearchSyncDTO> chunks) {
        log.info("Synchronizing {} ID: {} ({} chunks)", sourceType, sourceId,
            chunks == null ? 0 : chunks.size());

        if (sourceId == null) {
            throw new IllegalArgumentException("sourceId must not be null for sync operation");
        }
        if (chunks == null || chunks.isEmpty()) {
            log.warn("No chunks to sync for {} ID: {}", sourceType, sourceId);
            return;
        }

        AiSearchSyncDTO first = chunks.get(0);
        Long effectiveUserId = first.getUserId() != null ? first.getUserId() : SYSTEM_USER_ID;

        EcwsEmbeddingSync syncRecord = syncRepository.findBySourceTypeAndSourceId(
                sourceType, sourceId)
            .orElse(new EcwsEmbeddingSync());

        syncRecord.setSourceType(sourceType);
        syncRecord.setSourceId(sourceId);
        syncRecord.setProjectId(first.getProjectId());
        syncRecord.setWorkspaceId(first.getWorkspaceId());
        syncRecord.setUserId(effectiveUserId);
        syncRecord.setStatus("PENDING");
        syncRecord = syncRepository.save(syncRecord);
        syncRepository.flush();

        try {
            // 1. Build every chunk's embedding before touching the stored rows
            List<EcwsEmbedding> embeddings = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                AiSearchSyncDTO chunk = chunks.get(i);
                String context = buildContext(chunk);
                float[] vector = ollamaService.getOllamaEmbedding(context);

                EcwsEmbedding embedding = new EcwsEmbedding();
                embedding.setSourceType(sourceType);
                embedding.setSourceId(sourceId);
                embedding.setChunkIndex(i);
                embedding.setChunkText(context);
                embedding.setChunkTokens(context != null ? context.split("\\s+").length : 0);
                embedding.setEmbedding(VectorUtil.toString(vector));
                embedding.setSummary(chunk.getSummary());
                embedding.setStatus(chunk.getStatus());
                embedding.setPriority(chunk.getPriority());
                embedding.setRequestor(chunk.getRequestor());
                embedding.setDueDate(chunk.getDueDate());
                embedding.setScheduledDate(chunk.getScheduledDate());
                embedding.setParentSourceId(chunk.getParentSourceId());
                embedding.setTaskId(taskIdFor(chunk));
                embedding.setProjectId(chunk.getProjectId() != null ? chunk.getProjectId() : EcwsProjectEnum.ECWS.getProjectId());
                embedding.setWorkspaceId(chunk.getWorkspaceId() != null ? chunk.getWorkspaceId() : EmbeddingSourceTypeEnum.COLLABORATION_TASK.getWorkspaceId());
                embedding.setUserId(effectiveUserId);
                embedding.setCreatedByUserId(effectiveUserId);
                embedding.setUpdatedByUserId(effectiveUserId);
                embedding.setSourceUpdatedAt(chunk.getSourceUpdatedAt());
                embeddings.add(embedding);
            }

            // 2. Replace the stored rows once for the whole file
            embeddingRepository.deleteBySourceTypeAndSourceId(sourceType, sourceId);
            embeddingRepository.flush();
            embeddingRepository.saveAllAndFlush(embeddings);

            // 3. Update Sync Status
            syncRecord.setStatus("COMPLETED");
            syncRecord.setProcessedAt(Instant.now());
            syncRecord.setErrorMessage(null);
            syncRepository.saveAndFlush(syncRecord);

            log.info("Successfully synchronized {} ID: {} ({} chunks)", sourceType, sourceId,
                embeddings.size());
        } catch (Exception e) {
            log.error("Failed to synchronize {} ID: {}", sourceType, sourceId, e);
            syncRecord.setStatus("FAILED");
            syncRecord.setErrorMessage(e.getMessage());
            syncRecord.setRetryCount(syncRecord.getRetryCount() + 1);
            syncRecord.setNextRetryAt(Instant.now().plusSeconds(60L * syncRecord.getRetryCount()));
            syncRepository.save(syncRecord);
            throw e;
        }
    }

    public void refreshSync() {
        log.info("Starting refresh sync from source databases");
        refreshCollaborationTasks();
        refreshTaskComments();
        removeDeletedCommentEmbeddings();
        aiFileSyncService.refreshFileSyncs();
    }


    private void refreshCollaborationTasks() {
        try {
            // Read before the task list is fetched, so a task created during the refresh is
            // never a cleanup candidate (plan §7.3).
            Set<Long> storedTaskIds = findStoredIds(
                EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType());

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

                // findCollaborationTasks returns every task, unfiltered, so a stored id missing
                // from it was deleted in ecws (plan §7.3).
                removeStaleEmbeddings(EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType(),
                    storedTaskIds, tasks.stream()
                        .map(task -> (long) task.getId())
                        .collect(Collectors.toSet()));

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


    /**
     * Removes task_comment embeddings and sync records of comments that no longer exist in ecws
     * (plan §7.3). Uses findLiveCommentIds, not findAllActiveComments: most comments have a NULL
     * active_ind and would look deleted. ecws-service has no comment delete today, so this catches
     * rows removed directly in the database. Skipped if the id list can't be fetched or is empty.
     */
    private void removeDeletedCommentEmbeddings() {
        String sourceType = EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType();
        try {
            // Read before the live list is fetched, so a comment added meanwhile is kept.
            Set<Long> storedIds = findStoredIds(sourceType);
            if (storedIds.isEmpty()) {
                return;
            }

            RESTReqContainer<CollaborationTaskDTOContainer> restReqContainer =
                new RESTReqContainer<>(
                    ServiceDiscoveryEnum.ecws_service.getServiceDiscoveryName(),
                    "/collaborationTaskController/findLiveCommentIds",
                    new CollaborationTaskDTOContainer(),
                    new ParameterizedTypeReference<CollaborationTaskDTOContainer>() {},
                    HttpMethod.POST
                );
            CollaborationTaskDTOContainer response = restServiceClient.callRESTService(restReqContainer);
            if (response == null || response.getTaskCommentDTOList() == null) {
                log.warn("Deleted comment embedding cleanup skipped: ecws_service returned no comment ids");
                return;
            }

            Set<Long> liveIds = response.getTaskCommentDTOList().stream()
                .filter(c -> c != null && c.getId() != null)
                .map(c -> c.getId().longValue())
                .collect(Collectors.toSet());
            removeStaleEmbeddings(sourceType, storedIds, liveIds);
        } catch (Exception e) {
            log.error("Deleted comment embedding cleanup failed", e);
        }
    }

    /** Source ids of one type that currently have embeddings or sync records. */
    private Set<Long> findStoredIds(String sourceType) {
        Set<Long> ids = new HashSet<>(
            embeddingRepository.findDistinctSourceIdsBySourceTypeIn(List.of(sourceType)));
        ids.addAll(syncRepository.findDistinctSourceIdsBySourceTypeIn(List.of(sourceType)));
        return ids;
    }

    /**
     * Deletes embeddings and sync records for stored ids that are not in the live list (plan
     * §7.3). Skipped when the live list is empty, so an ecws-service problem can't wipe every
     * embedding of the type. Failures are logged, never thrown.
     */
    private void removeStaleEmbeddings(String sourceType, Set<Long> storedIds, Collection<Long> liveIds) {
        try {
            if (liveIds == null || liveIds.isEmpty()) {
                log.warn("Stale {} embedding cleanup skipped: ecws_service returned no ids", sourceType);
                return;
            }
            List<Long> staleIds = storedIds.stream()
                .filter(id -> !liveIds.contains(id))
                .sorted()
                .toList();
            if (staleIds.isEmpty()) {
                log.info("Stale {} embedding cleanup: nothing to remove", sourceType);
                return;
            }
            for (Long id : staleIds) {
                embeddingRepository.deleteBySourceTypeAndSourceId(sourceType, id);
                syncRepository.deleteBySourceTypeAndSourceId(sourceType, id);
            }
            log.info("Stale {} embedding cleanup: removed {} id(s): {}", sourceType,
                staleIds.size(), staleIds);
        } catch (Exception e) {
            log.error("Stale {} embedding cleanup failed", sourceType, e);
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


    /**
     * Task a synced row belongs to (plan §7.5). Uses the caller's taskId when sent; otherwise the
     * task itself for tasks, and parentSourceId for comments and task files (ecws-service doesn't
     * send taskId for those). Comment files have no fallback: their parentSourceId is the comment id.
     */
    static Long taskIdFor(AiSearchSyncDTO dto) {
        if (dto.getTaskId() != null) {
            return dto.getTaskId();
        }
        String sourceType = dto.getSourceType();
        if (EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType().equals(sourceType)) {
            return dto.getSourceId();
        }
        if (EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType().equals(sourceType)
                || EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT.getSourceType().equals(sourceType)) {
            return dto.getParentSourceId();
        }
        return null;
    }

    // ATTACH-CS: was a fixed template per type. Every label was written even when its value was
    // empty (and a null date as "null"), so words like "Scheduled:" or "Priority:" were in every
    // task's embedded text and a query such as "scheduled" matched nearly all tasks. Dates were
    // written as Date.toString() ("Mon Sep 28 00:00:00 KST 2026"). Replaced by labeled() below:
    // only fields with a value, dates as yyyy-MM-dd.
    // private String buildContext(AiSearchSyncDTO dto) {
    //     return switch (dto.getSourceType()) {
    //         case "task_comment" -> String.format("Comment: %s | Author: %s",
    //             nullSafe(dto.getDescription()), nullSafe(dto.getRequestor()));
    //         case "task_file_attachment", "comment_file_attachment" -> String.format(
    //             "File: %s | Content: %s",
    //             nullSafe(dto.getSummary()), nullSafe(dto.getDescription()));
    //         default -> String.format(
    //             "Summary: %s | Status: %s | Priority: %s | Requestor: %s | Due: %s | Scheduled: %s | Description: %s",
    //             nullSafe(dto.getSummary()), nullSafe(dto.getStatus()),
    //             nullSafe(dto.getPriority()), nullSafe(dto.getRequestor()),
    //             dto.getDueDate(), dto.getScheduledDate(),
    //             nullSafe(dto.getDescription()));
    //     };
    // }
    String buildContext(AiSearchSyncDTO dto) {
        return switch (dto.getSourceType()) {
            case "task_comment" -> labeled(
                "Comment", dto.getDescription(),
                "Author", dto.getRequestor());
            case "task_file_attachment", "comment_file_attachment" -> labeled(
                "File", dto.getSummary(),
                "Content", dto.getDescription());
            default -> labeled(
                "Summary", dto.getSummary(),
                "Status", dto.getStatus(),
                "Priority", dto.getPriority(),
                "Requestor", dto.getRequestor(),
                "Due", isoDate(dto.getDueDate()),
                "Scheduled", isoDate(dto.getScheduledDate()),
                "Description", dto.getDescription());
        };
    }

    /** "Label: value" pairs joined by " | ", leaving out pairs whose value is null or blank. */
    private static String labeled(String... labelValuePairs) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            String value = labelValuePairs[i + 1];
            if (value != null && !value.isBlank()) {
                parts.add(labelValuePairs[i] + ": " + value.trim());
            }
        }
        return String.join(" | ", parts);
    }

    /**
     * yyyy-MM-dd, or null. Formatted in UTC: ecws sends dates as "2025-07-24" and Jackson reads
     * that as midnight UTC, so the JVM zone (Pacific here) would print the previous day.
     * Handles java.sql.Date, whose toInstant() is unsupported.
     */
    private static String isoDate(java.util.Date date) {
        if (date == null) {
            return null;
        }
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd");
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    // ATTACH-CS: no longer used; buildContext now uses labeled(), which leaves out empty values.
    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}

