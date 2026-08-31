package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.constant.EcwsProjectEnum;
import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
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
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiFileSyncService {

    private static final int CHUNK_SIZE = 500; // words per chunk

    private final FileTextExtractorService extractorService;
    private final AiSyncService aiSyncService;
    private final EcwsEmbeddingSyncRepository syncRepository;
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
                    .projectId(EcwsProjectEnum.ECWS.getProjectId())
                    .sourceUpdatedAt(Instant.now())
                    .build();

                aiSyncService.sync(syncDto);
            }
        } catch (UnsupportedOperationException e) {
            log.warn("Skipping unsupported file: {}", attachment.getFileName());
        } catch (Exception e) {
            log.error("Failed to sync file attachment ID: {}", attachment.getId(), e);
        }
    }

    public void refreshFileSyncs() {
        try {
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

    private List<String> chunkText(String text, int wordsPerChunk) {
        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();
        StringBuilder chunkBuilder = new StringBuilder();
        int wordCount = 0;

        for (String word : words) {
            if (wordCount > 0) {
                chunkBuilder.append(" ");
            }
            chunkBuilder.append(word);
            if (++wordCount >= wordsPerChunk) {
                chunks.add(chunkBuilder.toString());
                chunkBuilder.setLength(0);
                wordCount = 0;
            }
        }

        if (!chunkBuilder.isEmpty()) {
            chunks.add(chunkBuilder.toString());
        }

        return chunks;
    }
}