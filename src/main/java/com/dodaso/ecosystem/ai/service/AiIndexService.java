package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.constant.AppCodeEnum;
import com.dodaso.ecosystem.ai.constant.ElcmSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiIndexDTO;
import com.dodaso.ecosystem.ai.dto.AiIndexDeleteDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingSyncRepository;
import com.dodaso.ecosystem.ai.util.VectorUtil;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indexes and removes records of apps other than ECWS (ELCM today) in the shared
 * dodaso_embeddings table. Every row carries app_code, scope_id and its owner, so a scoped
 * search can limit results to the scopes the caller may see. ECWS keeps AiSyncService.
 */
@Service
@Slf4j
public class AiIndexService {

    private static final Long SYSTEM_USER_ID = 1L;

    @Autowired
    private EcwsEmbeddingRepository embeddingRepository;

    @Autowired
    private EcwsEmbeddingSyncRepository syncRepository;

    @Autowired
    private OllamaService ollamaService;

    @Autowired
    private FileTextExtractorService extractorService;

    @Autowired
    private AiFileSyncService aiFileSyncService;

    @Lazy
    @Autowired
    private AiIndexService self;

    /** Only ELCM uses the scoped endpoints; ECWS rows need project and task ids these don't carry. */
    static String requireScopedApp(String appCode) {
        if (!AppCodeEnum.ELCM.name().equals(appCode)) {
            throw new IllegalArgumentException("appCode must be ELCM");
        }
        return appCode;
    }

    /** ELCM source types start with "elcm_", so they never share a name with an ECWS type. */
    static String requireElcmSourceType(String sourceType) {
        try {
            return ElcmSourceTypeEnum.fromSourceType(sourceType).getSourceType();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown ELCM sourceType: " + sourceType);
        }
    }

    /** Throws IllegalArgumentException naming every missing or invalid field. */
    public void validate(AiIndexDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("aiIndexDTO is required");
        }
        requireScopedApp(dto.getAppCode());
        requireElcmSourceType(dto.getSourceType());
        List<String> missing = new ArrayList<>();
        if (dto.getScopeId() == null) missing.add("scopeId");
        if (dto.getSourceId() == null) missing.add("sourceId");
        if (isBlank(dto.getOwnerType())) missing.add("ownerType");
        if (dto.getOwnerId() == null) missing.add("ownerId");
        if (dto.getSourceUpdatedAt() == null) missing.add("sourceUpdatedAt");
        boolean hasFile = dto.getFileBytes() != null && dto.getFileBytes().length > 0;
        if (!hasFile && isBlank(dto.getText())) missing.add("text or fileBytes");
        if (hasFile && isBlank(dto.getFileName())) missing.add("fileName");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing " + String.join(", ", missing));
        }
    }

    @Async
    public void indexAsync(AiIndexDTO dto) {
        try {
            index(dto);
        } catch (Exception e) {
            log.error("Failed to index {} {} {}", dto.getAppCode(), dto.getSourceType(), dto.getSourceId(), e);
        }
    }

    /**
     * Replaces the stored rows of one source record. A request older than the stored rows is
     * ignored, so a late retry can't overwrite newer text. A file with no text leaves no rows.
     */
    void index(AiIndexDTO dto) throws Exception {
        validate(dto);
        String appCode = dto.getAppCode();
        String sourceType = dto.getSourceType();
        Long sourceId = dto.getSourceId();

        List<EcwsEmbedding> stored = embeddingRepository.findByAppCodeAndSourceTypeAndSourceId(appCode, sourceType, sourceId);
        if (!stored.isEmpty() && stored.get(0).getSourceUpdatedAt() != null
                && dto.getSourceUpdatedAt().isBefore(stored.get(0).getSourceUpdatedAt())) {
            log.info("Skipped {} {} {}: stored rows are newer", appCode, sourceType, sourceId);
            return;
        }

        EcwsEmbeddingSync sync = syncRepository.findByAppCodeAndSourceTypeAndSourceId(appCode, sourceType, sourceId)
            .orElse(new EcwsEmbeddingSync());
        sync.setAppCode(appCode);
        sync.setSourceType(sourceType);
        sync.setSourceId(sourceId);
        sync.setScopeId(dto.getScopeId());
        sync.setUserId(SYSTEM_USER_ID);
        sync.setStatus("PENDING");
        sync = syncRepository.save(sync);

        try {
            List<String> chunks = textChunks(dto);
            if (chunks.isEmpty()) {
                log.warn("No text in {} {} {} ({}); nothing indexed", appCode, sourceType, sourceId, dto.getFileName());
            }
            String model = ollamaService.getEmbeddingModel();
            List<EcwsEmbedding> rows = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                // The title is embedded with every chunk but not stored in chunk_text, so a chunk
                // is found by its document's name and shown without it.
                String embedded = isBlank(dto.getTitle()) ? chunk : dto.getTitle() + " | " + chunk;
                rows.add(row(dto, i, chunk, ollamaService.getOllamaEmbedding(embedded), model));
            }
            self.replaceRows(appCode, sourceType, sourceId, rows);

            sync.setStatus("COMPLETED");
            sync.setErrorMessage(null);
            sync.setProcessedAt(Instant.now());
            syncRepository.save(sync);
            log.info("Indexed {} {} {}: {} chunk(s)", appCode, sourceType, sourceId, rows.size());
        } catch (Exception e) {
            // No retry job for scoped apps: the app sends the record again on its next save.
            sync.setStatus("FAILED");
            sync.setErrorMessage(e.getMessage());
            syncRepository.save(sync);
            throw e;
        }
    }

    private List<String> textChunks(AiIndexDTO dto) throws Exception {
        String text = dto.getFileBytes() != null && dto.getFileBytes().length > 0
            ? extractorService.extract(new ByteArrayInputStream(dto.getFileBytes()), dto.getFileName())
            : dto.getText();
        if (isBlank(text)) {
            return List.of();
        }
        return aiFileSyncService.chunkFileText(text.trim());
    }

    private static EcwsEmbedding row(AiIndexDTO dto, int index, String chunk, float[] vector, String model) {
        EcwsEmbedding e = new EcwsEmbedding();
        e.setAppCode(dto.getAppCode());
        e.setScopeId(dto.getScopeId());
        e.setSourceType(dto.getSourceType());
        e.setSourceId(dto.getSourceId());
        e.setOwnerType(dto.getOwnerType());
        e.setOwnerId(dto.getOwnerId());
        e.setParentSourceId(dto.getParentSourceId());
        e.setTitle(dto.getTitle());
        e.setChunkIndex(index);
        e.setChunkText(chunk);
        e.setChunkTokens(chunk.split("\\s+").length);
        e.setEmbedding(VectorUtil.toString(vector));
        e.setEmbeddingModel(model);
        e.setStatus("ACTIVE");
        e.setCreatedByUserId(SYSTEM_USER_ID);
        e.setUpdatedByUserId(SYSTEM_USER_ID);
        e.setSourceUpdatedAt(dto.getSourceUpdatedAt());
        return e;
    }

    /** Old rows are removed only once every new chunk has its vector. */
    @Transactional
    public void replaceRows(String appCode, String sourceType, Long sourceId, List<EcwsEmbedding> rows) {
        embeddingRepository.deleteByAppCodeAndSourceTypeAndSourceId(appCode, sourceType, sourceId);
        embeddingRepository.flush();
        if (!rows.isEmpty()) {
            embeddingRepository.saveAllAndFlush(rows);
        }
    }

    /**
     * Removes the embeddings and sync rows of the given records, in the given app only.
     *
     * @return embedding rows deleted
     */
    @Transactional
    public long delete(AiIndexDeleteDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("aiIndexDeleteDTO is required");
        }
        String appCode = requireScopedApp(dto.getAppCode());
        String sourceType = requireElcmSourceType(dto.getSourceType());
        Collection<Long> ids = dto.getSourceIds();
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("sourceIds must be a non-empty list of ids");
        }
        long deleted = 0;
        for (Long id : ids) {
            deleted += embeddingRepository.deleteByAppCodeAndSourceTypeAndSourceId(appCode, sourceType, id);
            syncRepository.deleteByAppCodeAndSourceTypeAndSourceId(appCode, sourceType, id);
        }
        log.info("Deleted {} embedding row(s) of {} {} {}", deleted, appCode, sourceType, ids);
        return deleted;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
