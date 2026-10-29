package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.dto.AiScopedSearchDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.EcwsSearchHistory;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsSearchHistoryRepository;
import com.dodaso.ecosystem.ai.repository.ScopedEmbeddingRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** A scoped search returns only the requested app and workspaces; no scopes means no search. */
class AiScopedSearchServiceTest {

    private EcwsEmbeddingRepository embeddingRepository;
    private EcwsSearchHistoryRepository historyRepository;
    private OllamaService ollamaService;
    private EntityManager entityManager;
    private final List<String> nativeSql = new ArrayList<>();
    private AiScopedSearchService service;

    @BeforeEach
    void setUp() {
        embeddingRepository = mock(EcwsEmbeddingRepository.class);
        historyRepository = mock(EcwsSearchHistoryRepository.class);
        ollamaService = mock(OllamaService.class);
        entityManager = mock(EntityManager.class);
        when(ollamaService.getOllamaEmbedding(anyString())).thenReturn(new float[] {0.1f});
        when(ollamaService.getEmbeddingModel()).thenReturn("nomic-embed-text");
        when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            nativeSql.add(sql);
            Query q = mock(Query.class);
            when(q.getSingleResult()).thenReturn(sql.contains("extversion") ? "0.8.1" : "on");
            return q;
        });

        service = new AiScopedSearchService();
        ReflectionTestUtils.setField(service, "embeddingRepository", embeddingRepository);
        ReflectionTestUtils.setField(service, "searchHistoryRepository", historyRepository);
        ReflectionTestUtils.setField(service, "ollamaService", ollamaService);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static ScopedEmbeddingRow row(long id, String app, Long scope, String type, long sourceId, double score) {
        ScopedEmbeddingRow r = mock(ScopedEmbeddingRow.class);
        when(r.getId()).thenReturn(id);
        when(r.getAppCode()).thenReturn(app);
        when(r.getScopeId()).thenReturn(scope);
        when(r.getSourceType()).thenReturn(type);
        when(r.getSourceId()).thenReturn(sourceId);
        when(r.getScore()).thenReturn(score);
        when(r.getChunkText()).thenReturn("chunk " + id);
        return r;
    }

    private static AiScopedSearchDTO.AiScopedSearchDTOBuilder request() {
        return AiScopedSearchDTO.builder().appCode("ELCM").scopeIds(List.of(3L)).query("rent increase");
    }

    @Test
    void emptyOrMissingScopesAreRejectedBeforeAnySearch() {
        assertThrows(IllegalArgumentException.class, () -> service.search(request().scopeIds(List.of()).build()));
        assertThrows(IllegalArgumentException.class, () -> service.search(request().scopeIds(null).build()));
        List<Long> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> service.search(request().scopeIds(withNull).build()));
        verifyNoInteractions(embeddingRepository, ollamaService);
    }

    @Test
    void ecwsAppMissingQueryAndEcwsSourceTypeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.search(request().appCode("ECWS").build()));
        assertThrows(IllegalArgumentException.class, () -> service.search(request().query(" ").build()));
        assertThrows(IllegalArgumentException.class,
            () -> service.search(request().sourceTypes(List.of("collaboration_task")).build()));
        verifyNoInteractions(embeddingRepository);
    }

    @Test
    void passesExactlyTheCallersScopesAppAndModel() {
        service.search(request().scopeIds(List.of(3L, 7L)).limit(4).build());

        verify(embeddingRepository).findSimilarScopedAllTypes(eq("ELCM"), eq(List.of(3L, 7L)),
            eq("nomic-embed-text"), anyString(), eq(20));
    }

    @Test
    void sourceTypeFilterUsesTheFilteredQuery() {
        service.search(request().sourceTypes(List.of("elcm_review_notation")).build());

        verify(embeddingRepository).findSimilarScoped(eq("ELCM"), eq(List.of(3L)), eq("nomic-embed-text"),
            eq(List.of("elcm_review_notation")), anyString(), anyInt());
    }

    @Test
    void anotherWorkspacesOrAppsRowIsNeverReturned() {
        List<ScopedEmbeddingRow> rows = List.of(
            row(1, "ELCM", 9L, "elcm_contract_record", 90, 0.99),   // other workspace
            row(2, "ECWS", null, "collaboration_task", 91, 0.98),   // ECWS row
            row(3, "ELCM", 3L, "elcm_contract_record", 41, 0.80));
        when(embeddingRepository.findSimilarScopedAllTypes(any(), any(), any(), any(), anyInt())).thenReturn(rows);

        List<AiSearchResultDTO> results = service.search(request().build());

        assertEquals(1, results.size());
        assertEquals(41L, results.get(0).getSourceId());
        assertEquals(3L, results.get(0).getScopeId());
    }

    @Test
    void keepsBestChunkPerRecordSortedByRealScore() {
        List<ScopedEmbeddingRow> rows = List.of(
            row(1, "ELCM", 3L, "elcm_staged_document", 5, 0.70),
            row(2, "ELCM", 3L, "elcm_staged_document", 5, 0.90),   // same file, better chunk
            row(3, "ELCM", 3L, "elcm_contract_record", 41, 0.80));
        when(embeddingRepository.findSimilarScopedAllTypes(any(), any(), any(), any(), anyInt())).thenReturn(rows);

        List<AiSearchResultDTO> results = service.search(request().build());

        assertEquals(List.of(5L, 41L), results.stream().map(AiSearchResultDTO::getSourceId).toList());
        assertEquals(0.90, results.get(0).getScore());
        assertEquals("chunk 2", results.get(0).getChunkText());
    }

    @Test
    void limitIsCappedAndHistoryRecordsAppScopesAndUser() {
        service.search(request().limit(500).userRef("kim@dodaso.local").build());

        verify(embeddingRepository).findSimilarScopedAllTypes(any(), any(), any(), any(),
            eq(AiScopedSearchService.MAX_LIMIT * 5));
        ArgumentCaptor<EcwsSearchHistory> history = ArgumentCaptor.forClass(EcwsSearchHistory.class);
        verify(historyRepository).save(history.capture());
        assertEquals("ELCM", history.getValue().getAppCode());
        assertEquals("[3]", history.getValue().getScopeIds());
        assertEquals("kim@dodaso.local", history.getValue().getUserRef());
        assertEquals(AiScopedSearchService.MAX_LIMIT, history.getValue().getTopK());
    }

    @Test
    void widensTheHnswScanByPgvectorVersion() {
        service.search(request().build());
        assertTrue(nativeSql.stream().anyMatch(s -> s.contains("hnsw.iterative_scan")));

        assertTrue(AiScopedSearchService.supportsIterativeScan("0.8.1"));
        assertTrue(AiScopedSearchService.supportsIterativeScan("1.0.0"));
        assertFalse(AiScopedSearchService.supportsIterativeScan("0.7.4"));
        assertFalse(AiScopedSearchService.supportsIterativeScan(""));
    }
}
