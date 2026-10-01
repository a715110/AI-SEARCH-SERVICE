package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.constant.EcwsProjectEnum;
import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingSyncRepository;
import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.container.RESTReqContainer;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.ecws.container.FileAttachmentDTOContainer;
import com.dodaso.ecosystem.ecws.dto.FileAttachmentDTO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiFileSyncService {

    private static final int CHUNK_SIZE = 500; // words per chunk

    /**
     * Characters per chunk. nomic-embed-text accepts at most 2048 tokens, and filler such as
     * "_____" or "....." in form PDFs costs one token per character, so a 500-word chunk of
     * a fillable form went over the limit and Ollama returned 500 "the input length exceeds
     * the context length". 1800 chars stays under 2048 tokens for any text, with room for
     * the "File: ... | Content: " prefix added by buildContext.
     */
    private static final int MAX_CHUNK_CHARS = 1800;

    private static final List<String> FILE_SOURCE_TYPES = List.of(
        EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT.getSourceType(),
        EmbeddingSourceTypeEnum.COMMENT_FILE_ATTACHMENT.getSourceType());

    private final FileTextExtractorService extractorService;
    private final AiSyncService aiSyncService;
    private final EcwsEmbeddingSyncRepository syncRepository;
    private final EcwsEmbeddingRepository embeddingRepository;
    private final RESTServiceClient restServiceClient;

    @Async
    public void syncFileAsync(FileAttachmentDTO attachment, InputStream inputStream) {
        try {
            String rawText = extractorService.extract(inputStream, attachment.getFileName());
            List<String> chunks = chunkText(rawText, CHUNK_SIZE);

            log.info("Syncing file '{}' — {} chunks", attachment.getFileName(), chunks.size());

            boolean isCommentFile = attachment.getTaskCommentDTO() != null;
            EmbeddingSourceTypeEnum type = isCommentFile
                ? EmbeddingSourceTypeEnum.COMMENT_FILE_ATTACHMENT
                : EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT;
            Integer commentId = isCommentFile ? attachment.getTaskCommentDTO().getId() : null;
            Long parentSourceId = isCommentFile
                ? (commentId != null ? commentId.longValue() : null)
                : (attachment.getCollaborationTaskDTO() != null
                    ? (long) attachment.getCollaborationTaskDTO().getId()
                    : null);
            // Owning task for both file types; ecws sends it for comment files too (plan §7.5).
            Long taskId = attachment.getCollaborationTaskDTO() != null
                ? (long) attachment.getCollaborationTaskDTO().getId()
                : null;

            List<AiSearchSyncDTO> chunkDtos = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                AiSearchSyncDTO syncDto = AiSearchSyncDTO.builder()
                    .sourceId(Long.valueOf(attachment.getId()))
                    .sourceType(type.getSourceType())
                    .workspaceId(type.getWorkspaceId())
                    .summary(attachment.getFileName() + " [chunk " + (i + 1) + "/" + chunks.size() + "]")
                    .description(chunk)
                    .status("ACTIVE")
                    .parentSourceId(parentSourceId)
                    .taskId(taskId)
                    .projectId(EcwsProjectEnum.ECWS.getProjectId())
                    .sourceUpdatedAt(Instant.now())
                    .build();

                // ATTACH-CS: was one sync() per chunk; each call replaced the file's rows, so only
                // the last chunk was kept (plan §7.4). Now all chunks are stored by syncChunks below.
                // aiSyncService.sync(syncDto);
                chunkDtos.add(syncDto);
            }
            aiSyncService.syncChunks(type.getSourceType(), Long.valueOf(attachment.getId()), chunkDtos);
        } catch (UnsupportedOperationException e) {
            log.warn("Skipping unsupported file: {}", attachment.getFileName());
        } catch (Exception e) {
            log.error("Failed to sync file attachment ID: {}", attachment.getId(), e);
        }
    }

    /**
     * Removes the embeddings and sync records of deleted file attachments (plan
     * PLAN-attachment-to-common-service §7.3). file_attachment.id is unique across task and
     * comment files, so both source types are cleared for each id. Ids with nothing stored are
     * ignored, so calling this twice is safe.
     *
     * @return number of ids processed
     */
    @Transactional
    public int deleteFileEmbeddings(List<FileAttachmentDTO> attachments) {
        List<Long> ids = attachments == null ? List.of() : attachments.stream()
            .filter(a -> a != null && a.getId() != null && a.getId() > 0)
            .map(a -> Long.valueOf(a.getId()))
            .distinct()
            .toList();
        deleteFileEmbeddingIds(ids);
        log.info("Deleted file embeddings for file_attachment ids {}", ids);
        return ids.size();
    }

    private void deleteFileEmbeddingIds(Collection<Long> ids) {
        for (Long id : ids) {
            for (String sourceType : FILE_SOURCE_TYPES) {
                embeddingRepository.deleteBySourceTypeAndSourceId(sourceType, id);
                syncRepository.deleteBySourceTypeAndSourceId(sourceType, id);
            }
        }
    }

    /**
     * file_attachment ids that currently have file embeddings or sync records. Read before the
     * active list is fetched, so a file uploaded during the refresh is never a cleanup candidate.
     */
    private Set<Long> findStoredFileIds() {
        Set<Long> ids = new HashSet<>(embeddingRepository.findDistinctSourceIdsBySourceTypeIn(FILE_SOURCE_TYPES));
        ids.addAll(syncRepository.findDistinctSourceIdsBySourceTypeIn(FILE_SOURCE_TYPES));
        return ids;
    }

    /**
     * Safety net for the delete-time cleanup (plan §7.3): removes file embeddings and sync records
     * whose file_attachment is no longer active. Catches failed cleanup calls, deletes made while
     * the file was still being embedded, and deletes from before the cleanup existed. Skipped when
     * the active list is empty, so an ecws-service problem can't wipe every file embedding.
     */
    private void removeInactiveFileEmbeddings(Set<Long> storedIds, List<FileAttachmentDTO> activeAttachments) {
        try {
            if (activeAttachments.isEmpty()) {
                log.warn("Inactive file embedding cleanup skipped: ecws_service returned no active files");
                return;
            }
            Set<Long> activeIds = activeAttachments.stream()
                .filter(a -> a.getId() != null)
                .map(a -> Long.valueOf(a.getId()))
                .collect(Collectors.toSet());
            List<Long> staleIds = storedIds.stream()
                .filter(id -> !activeIds.contains(id))
                .sorted()
                .toList();
            if (staleIds.isEmpty()) {
                log.info("Inactive file embedding cleanup: nothing to remove");
                return;
            }
            deleteFileEmbeddingIds(staleIds);
            log.info("Inactive file embedding cleanup: removed embeddings for {} file(s): {}",
                staleIds.size(), staleIds);
        } catch (Exception e) {
            log.error("Inactive file embedding cleanup failed", e);
        }
    }

    public void refreshFileSyncs() {
        try {
            Set<Long> storedFileIds = findStoredFileIds();

            log.info("Fetching all active file attachments for sync");

            FileAttachmentDTOContainer metaRequest = new FileAttachmentDTOContainer();
            RESTReqContainer<FileAttachmentDTOContainer> metaReq = new RESTReqContainer<>(
                ServiceDiscoveryEnum.ecws_service.getServiceDiscoveryName(),
                "/fileAttachmentController/findAllActiveAttachments",
                metaRequest,
                new ParameterizedTypeReference<FileAttachmentDTOContainer>() {},
                HttpMethod.POST
            );

            FileAttachmentDTOContainer metaResponse = restServiceClient.callRESTService(metaReq);

            if (metaResponse == null || metaResponse.getFileAttachmentDTOList() == null) {
                log.warn("No file attachments found from ecws_service");
                return;
            }

            List<FileAttachmentDTO> attachments = metaResponse.getFileAttachmentDTOList();
            log.info("Found {} active file attachments total", attachments.size());

            int syncedCount = 0, skippedCount = 0, failedCount = 0;

            for (FileAttachmentDTO attachment : attachments) {
                try {
                    if (isFileAlreadySynced(attachment)) {
                        skippedCount++;
                        continue;
                    }

                    FileAttachmentDTO stub = new FileAttachmentDTO();
                    stub.setId(attachment.getId());
                    FileAttachmentDTOContainer downloadRequest = new FileAttachmentDTOContainer();
                    downloadRequest.setFileAttachmentDTOList(List.of(stub));

                    RESTReqContainer<FileAttachmentDTOContainer> downloadReq = new RESTReqContainer<>(
                        ServiceDiscoveryEnum.ecws_service.getServiceDiscoveryName(),
                        "/fileAttachmentController/downloadFileAttachment",
                        downloadRequest,
                        new ParameterizedTypeReference<FileAttachmentDTOContainer>() {},
                        HttpMethod.POST
                    );

                    FileAttachmentDTOContainer downloadResponse = restServiceClient.callRESTService(downloadReq);

                    if (downloadResponse == null || downloadResponse.getFileAttachmentDTOList() == null
                            || downloadResponse.getFileAttachmentDTOList().isEmpty()) {
                        log.warn("Could not download bytes for file ID: {}", attachment.getId());
                        failedCount++;
                        continue;
                    }

                    FileAttachmentDTO downloaded = downloadResponse.getFileAttachmentDTOList().get(0);
                    if (downloaded.getFileBytes() == null || downloaded.getFileBytes().length == 0) {
                        log.warn("Empty bytes for file ID: {}, skipping", attachment.getId());
                        failedCount++;
                        continue;
                    }

                    downloaded.setCollaborationTaskDTO(attachment.getCollaborationTaskDTO());
                    downloaded.setTaskCommentDTO(attachment.getTaskCommentDTO());
                    syncFileAsync(downloaded, new ByteArrayInputStream(downloaded.getFileBytes()));
                    syncedCount++;

                } catch (Exception e) {
                    failedCount++;
                    log.error("Failed to sync file ID: {} — {}", attachment.getId(), e.getMessage());
                }
            }

            log.info("File sync refresh completed: {} synced, {} skipped, {} failed, {} total",
                syncedCount, skippedCount, failedCount, attachments.size());

            removeInactiveFileEmbeddings(storedFileIds, attachments);

        } catch (Exception e) {
            log.error("Failed to refresh file attachments for AI sync", e);
        }
    }

    private boolean isFileAlreadySynced(FileAttachmentDTO attachment) {
        try {
            boolean isCommentFile = attachment.getTaskCommentDTO() != null;
            String sourceType = isCommentFile
                ? EmbeddingSourceTypeEnum.COMMENT_FILE_ATTACHMENT.getSourceType()
                : EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT.getSourceType();
            return syncRepository.findBySourceTypeAndSourceId(sourceType, (long) attachment.getId())
                .map(s -> "COMPLETED".equals(s.getStatus()))
                .orElse(false);
        } catch (Exception e) {
            log.warn("Error checking sync status for file ID: {}, will re-sync", attachment.getId());
            return false;
        }
    }

    // ATTACH-CS: was word-count-only chunking; a chunk of form filler ("_____") went over
    // nomic-embed-text's 2048-token limit. Replaced by chunkText below, which also caps chunks
    // at MAX_CHUNK_CHARS (plan §7.4).
    // private List<String> chunkText(String text, int wordsPerChunk) {
    //     String[] words = text.split("\\s+");
    //     List<String> chunks = new ArrayList<>();
    //     StringBuilder chunkBuilder = new StringBuilder();
    //     int wordCount = 0;
    //
    //     for (String word : words) {
    //         if (wordCount > 0) {
    //             chunkBuilder.append(" ");
    //         }
    //         chunkBuilder.append(word);
    //         if (++wordCount >= wordsPerChunk) {
    //             chunks.add(chunkBuilder.toString());
    //             chunkBuilder.setLength(0);
    //             wordCount = 0;
    //         }
    //     }
    //
    //     if (!chunkBuilder.isEmpty()) {
    //         chunks.add(chunkBuilder.toString());
    //     }
    //
    //     return chunks;
    // }

    /**
     * Splits text into chunks of at most {@code wordsPerChunk} words and at most
     * {@link #MAX_CHUNK_CHARS} characters. A single word longer than the character cap
     * (e.g. a long "______" line) is cut into cap-sized pieces.
     */
    List<String> chunkText(String text, int wordsPerChunk) {
        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();
        StringBuilder chunkBuilder = new StringBuilder();
        int wordCount = 0;

        for (String word : words) {
            int start = 0;
            do {
                String piece = word.substring(start, Math.min(word.length(), start + MAX_CHUNK_CHARS));
                start += MAX_CHUNK_CHARS;

                if (wordCount > 0 && chunkBuilder.length() + 1 + piece.length() > MAX_CHUNK_CHARS) {
                    chunks.add(chunkBuilder.toString());
                    chunkBuilder.setLength(0);
                    wordCount = 0;
                }
                if (wordCount > 0) {
                    chunkBuilder.append(" ");
                }
                chunkBuilder.append(piece);
                if (++wordCount >= wordsPerChunk) {
                    chunks.add(chunkBuilder.toString());
                    chunkBuilder.setLength(0);
                    wordCount = 0;
                }
            } while (start < word.length());
        }

        if (!chunkBuilder.isEmpty()) {
            chunks.add(chunkBuilder.toString());
        }

        return chunks;
    }
}