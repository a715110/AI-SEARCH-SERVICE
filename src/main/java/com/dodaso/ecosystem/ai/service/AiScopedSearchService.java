package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.dto.AiScopedSearchDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.EcwsSearchHistory;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsSearchHistoryRepository;
import com.dodaso.ecosystem.ai.repository.ScopedEmbeddingRow;
import com.dodaso.ecosystem.ai.util.VectorUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Search limited to one app and the scopes the caller may see (ELCM workspaces). The scope list
 * comes from the app service's own permission check; an empty one is rejected, never read as
 * "all". ECWS keeps AiSearchService.search.
 */
@Service
@Slf4j
public class AiScopedSearchService {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;
    private static final int CANDIDATE_FACTOR = 5;
    private static final Long SYSTEM_USER_ID = 1L;

    @Autowired
    private EcwsEmbeddingRepository embeddingRepository;

    @Autowired
    private EcwsSearchHistoryRepository searchHistoryRepository;

    @Autowired
    private OllamaService ollamaService;

    @PersistenceContext
    private EntityManager entityManager;

    @Lazy
    @Autowired
    private AiScopedSearchService self;

    /** pgvector version, read once; null until read. */
    private volatile String vectorVersion;

    public List<AiSearchResultDTO> search(AiScopedSearchDTO request) {
        long start = System.currentTimeMillis();
        if (request == null) {
            throw new IllegalArgumentException("aiScopedSearchDTO is required");
        }
        String appCode = AiIndexService.requireScopedApp(request.getAppCode());
        List<Long> scopeIds = request.getScopeIds();
        if (scopeIds == null || scopeIds.isEmpty() || scopeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("scopeIds must be a non-empty list of ids");
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        List<String> sourceTypes = request.getSourceTypes() == null || request.getSourceTypes().isEmpty()
            ? null
            : request.getSourceTypes().stream().map(AiIndexService::requireElcmSourceType).toList();
        int limit = request.getLimit() == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, request.getLimit()));

        float[] queryVector = ollamaService.getOllamaEmbedding(request.getQuery());
        List<ScopedEmbeddingRow> rows = self.findRows(appCode, scopeIds, ollamaService.getEmbeddingModel(),
            sourceTypes, VectorUtil.toString(queryVector), limit * CANDIDATE_FACTOR);

        List<AiSearchResultDTO> results = toResults(rows, appCode, scopeIds, limit);
        saveHistory(request, appCode, scopeIds, limit, results, System.currentTimeMillis() - start);
        return results;
    }

    /**
     * Runs the scoped query in one transaction, after widening the HNSW scan for it: the shared
     * index is scanned first and app/scope are filtered after, so a small workspace in a large
     * table would otherwise come back short or empty.
     */
    @Transactional
    public List<ScopedEmbeddingRow> findRows(String appCode, Collection<Long> scopeIds, String model,
                                             Collection<String> sourceTypes, String queryVector, int candidates) {
        widenVectorScan();
        return sourceTypes == null
            ? embeddingRepository.findSimilarScopedAllTypes(appCode, scopeIds, model, queryVector, candidates)
            : embeddingRepository.findSimilarScoped(appCode, scopeIds, model, sourceTypes, queryVector, candidates);
    }

    /** pgvector 0.8+: iterative scan; older: a larger candidate list. Both last until commit. */
    void widenVectorScan() {
        if (vectorVersion == null) {
            Object v = entityManager.createNativeQuery(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'").getSingleResult();
            vectorVersion = v == null ? "" : v.toString();
        }
        String sql = supportsIterativeScan(vectorVersion)
            ? "SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true)"
            : "SELECT set_config('hnsw.ef_search', '200', true)";
        entityManager.createNativeQuery(sql).getSingleResult();
    }

    /** "0.8.1" -> true, "0.7.4" -> false; anything unreadable -> false. */
    static boolean supportsIterativeScan(String version) {
        try {
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 0 || minor >= 8;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Best chunk per record, closest first. relaxed_order can return rows slightly out of
     * order, so they are sorted by score here. A row outside the requested app or scopes is
     * dropped even though the query already filters them.
     */
    static List<AiSearchResultDTO> toResults(List<ScopedEmbeddingRow> rows, String appCode,
                                             Collection<Long> scopeIds, int limit) {
        Set<Long> allowed = new HashSet<>(scopeIds);
        List<ScopedEmbeddingRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(ScopedEmbeddingRow::getScore,
            Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, AiSearchResultDTO> best = new LinkedHashMap<>();
        for (ScopedEmbeddingRow row : sorted) {
            if (best.size() >= limit) {
                break;
            }
            if (!appCode.equals(row.getAppCode()) || !allowed.contains(row.getScopeId())) {
                log.warn("Dropped scoped search row {} outside {} {}", row.getId(), appCode, scopeIds);
                continue;
            }
            best.putIfAbsent(row.getSourceType() + ":" + row.getSourceId(), AiSearchResultDTO.builder()
                .appCode(row.getAppCode())
                .scopeId(row.getScopeId())
                .sourceType(row.getSourceType())
                .sourceId(row.getSourceId())
                .ownerType(row.getOwnerType())
                .ownerId(row.getOwnerId())
                .parentSourceId(row.getParentSourceId())
                .title(row.getTitle())
                .chunkText(row.getChunkText())
                .score(row.getScore())
                .build());
        }
        return new ArrayList<>(best.values());
    }

    private void saveHistory(AiScopedSearchDTO request, String appCode, List<Long> scopeIds, int limit,
                             List<AiSearchResultDTO> results, long latencyMs) {
        try {
            EcwsSearchHistory history = new EcwsSearchHistory();
            history.setAppCode(appCode);
            history.setScopeIds(scopeIds.toString());
            history.setUserRef(request.getUserRef());
            history.setQueryText(request.getQuery());
            history.setResultIds(results.stream().map(AiSearchResultDTO::getSourceId).toList().toString());
            history.setResultCount(results.size());
            history.setSearchLatencyMs(latencyMs);
            history.setTopK(limit);
            history.setCreatedByUserId(SYSTEM_USER_ID);
            history.setUpdatedByUserId(SYSTEM_USER_ID);
            searchHistoryRepository.save(history);
        } catch (Exception e) {
            log.error("Could not save scoped search history", e);
        }
    }
}
