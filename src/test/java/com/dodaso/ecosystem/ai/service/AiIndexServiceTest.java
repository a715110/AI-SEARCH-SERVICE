package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.constant.ElcmSourceTypeEnum;
import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiIndexDTO;
import com.dodaso.ecosystem.ai.dto.AiIndexDeleteDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingSyncRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** ELCM records are indexed and removed in the ELCM app only, with their scope and owner. */
class AiIndexServiceTest {

    private static final String RECORD = ElcmSourceTypeEnum.CONTRACT_RECORD.getSourceType();
    private static final Instant T1 = Instant.parse("2026-10-28T10:00:00Z");

    private EcwsEmbeddingRepository embeddingRepository;
    private EcwsEmbeddingSyncRepository syncRepository;
    private OllamaService ollamaService;
    private FileTextExtractorService extractor;
    private AiIndexService service;
    private final List<String> embeddedTexts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        embeddingRepository = mock(EcwsEmbeddingRepository.class);
        syncRepository = mock(EcwsEmbeddingSyncRepository.class);
        ollamaService = mock(OllamaService.class);
        extractor = mock(FileTextExtractorService.class);
        when(ollamaService.getEmbeddingModel()).thenReturn("nomic-embed-text");
        when(ollamaService.getOllamaEmbedding(anyString())).thenAnswer(inv -> {
            embeddedTexts.add(inv.getArgument(0));
            return new float[] {0.1f, 0.2f};
        });
        when(syncRepository.findByAppCodeAndSourceTypeAndSourceId(anyString(), anyString(), anyLong()))
            .thenReturn(Optional.empty());
        when(syncRepository.save(any(EcwsEmbeddingSync.class))).thenAnswer(inv -> inv.getArgument(0));

        AiFileSyncService fileSync = new AiFileSyncService(extractor, mock(AiSyncService.class),
            syncRepository, embeddingRepository, null);
        service = new AiIndexService();
        ReflectionTestUtils.setField(service, "embeddingRepository", embeddingRepository);
        ReflectionTestUtils.setField(service, "syncRepository", syncRepository);
        ReflectionTestUtils.setField(service, "ollamaService", ollamaService);
        ReflectionTestUtils.setField(service, "extractorService", extractor);
        ReflectionTestUtils.setField(service, "aiFileSyncService", fileSync);
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static AiIndexDTO.AiIndexDTOBuilder record() {
        return AiIndexDTO.builder().appCode("ELCM").scopeId(3L).sourceType(RECORD).sourceId(41L)
            .ownerType("contract_record").ownerId(41L).title("LEASE-041").text("Lease for 123 Main St")
            .sourceUpdatedAt(T1);
    }

    @SuppressWarnings("unchecked")
    private List<EcwsEmbedding> savedRows() {
        ArgumentCaptor<List<EcwsEmbedding>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).saveAllAndFlush(captor.capture());
        return captor.getValue();
    }

    // ---- validate ----

    @Test
    void rejectsEcwsAndMissingApp() {
        assertThrows(IllegalArgumentException.class, () -> service.validate(record().appCode("ECWS").build()));
        assertThrows(IllegalArgumentException.class, () -> service.validate(record().appCode(null).build()));
        assertThrows(IllegalArgumentException.class, () -> service.validate(null));
    }

    @Test
    void rejectsEcwsSourceType() {
        String ecwsType = EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType();
        assertThrows(IllegalArgumentException.class, () -> service.validate(record().sourceType(ecwsType).build()));
    }

    @Test
    void namesEveryMissingField() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.validate(
            record().scopeId(null).ownerType(" ").ownerId(null).sourceUpdatedAt(null).text(null).build()));
        for (String field : List.of("scopeId", "ownerType", "ownerId", "sourceUpdatedAt", "text or fileBytes")) {
            assertTrue(e.getMessage().contains(field), e.getMessage());
        }
    }

    @Test
    void fileNeedsItsName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.validate(record().text(null).fileBytes(new byte[] {1}).build()));
        assertTrue(e.getMessage().contains("fileName"));
    }

    // ---- index ----

    @Test
    void storesElcmRowsWithScopeOwnerAndModel() throws Exception {
        service.index(record().build());

        verify(embeddingRepository).deleteByAppCodeAndSourceTypeAndSourceId("ELCM", RECORD, 41L);
        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
        EcwsEmbedding row = savedRows().get(0);
        assertEquals("ELCM", row.getAppCode());
        assertEquals(3L, row.getScopeId());
        assertEquals("contract_record", row.getOwnerType());
        assertEquals(41L, row.getOwnerId());
        assertEquals("nomic-embed-text", row.getEmbeddingModel());
        assertNull(row.getProjectId());
        assertNull(row.getWorkspaceId());
        // title is embedded with the chunk but not stored in it
        assertEquals("Lease for 123 Main St", row.getChunkText());
        assertEquals("LEASE-041 | Lease for 123 Main St", embeddedTexts.get(0));

        ArgumentCaptor<EcwsEmbeddingSync> sync = ArgumentCaptor.forClass(EcwsEmbeddingSync.class);
        verify(syncRepository, org.mockito.Mockito.atLeastOnce()).save(sync.capture());
        assertEquals("COMPLETED", sync.getValue().getStatus());
        assertEquals("ELCM", sync.getValue().getAppCode());
        assertEquals(3L, sync.getValue().getScopeId());
    }

    @Test
    void fileIsExtractedAndChunked() throws Exception {
        when(extractor.extract(any(), eq("lease.pdf"))).thenReturn("x".repeat(4000));

        service.index(record().sourceType(ElcmSourceTypeEnum.STAGED_DOCUMENT.getSourceType())
            .text(null).fileBytes(new byte[] {1}).fileName("lease.pdf").build());

        List<EcwsEmbedding> rows = savedRows();
        assertEquals(3, rows.size());
        assertEquals(List.of(0, 1, 2), rows.stream().map(EcwsEmbedding::getChunkIndex).toList());
        assertTrue(rows.stream().allMatch(r -> r.getChunkText().length() <= 1800));
    }

    @Test
    void olderRequestThanStoredRowsIsIgnored() throws Exception {
        EcwsEmbedding stored = new EcwsEmbedding();
        stored.setSourceUpdatedAt(T1.plusSeconds(60));
        when(embeddingRepository.findByAppCodeAndSourceTypeAndSourceId("ELCM", RECORD, 41L)).thenReturn(List.of(stored));

        service.index(record().build());

        verify(embeddingRepository, never()).deleteByAppCodeAndSourceTypeAndSourceId(anyString(), anyString(), anyLong());
        verify(embeddingRepository, never()).saveAllAndFlush(anyList());
        verifyNoInteractions(ollamaService);
    }

    @Test
    void failedEmbeddingKeepsOldRowsAndMarksFailed() {
        when(ollamaService.getOllamaEmbedding(anyString())).thenThrow(new RuntimeException("ollama down"));

        assertThrows(RuntimeException.class, () -> service.index(record().build()));

        verify(embeddingRepository, never()).deleteByAppCodeAndSourceTypeAndSourceId(anyString(), anyString(), anyLong());
        ArgumentCaptor<EcwsEmbeddingSync> sync = ArgumentCaptor.forClass(EcwsEmbeddingSync.class);
        verify(syncRepository, org.mockito.Mockito.atLeastOnce()).save(sync.capture());
        assertEquals("FAILED", sync.getValue().getStatus());
    }

    // ---- delete ----

    @Test
    void deleteTouchesOnlyElcmRows() {
        when(embeddingRepository.deleteByAppCodeAndSourceTypeAndSourceId("ELCM", RECORD, 41L)).thenReturn(2L);
        when(embeddingRepository.deleteByAppCodeAndSourceTypeAndSourceId("ELCM", RECORD, 42L)).thenReturn(1L);

        long deleted = service.delete(AiIndexDeleteDTO.builder().appCode("ELCM").sourceType(RECORD)
            .sourceIds(List.of(41L, 42L)).build());

        assertEquals(3L, deleted);
        verify(syncRepository).deleteByAppCodeAndSourceTypeAndSourceId("ELCM", RECORD, 41L);
        verify(syncRepository).deleteByAppCodeAndSourceTypeAndSourceId("ELCM", RECORD, 42L);
        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
        verify(syncRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
        verify(embeddingRepository, never()).deleteByAppCodeAndSourceTypeAndSourceId(eq("ECWS"), anyString(), anyLong());
    }

    @Test
    void deleteRejectsEcwsAppEcwsTypeAndEmptyIds() {
        String task = EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType();
        assertThrows(IllegalArgumentException.class, () -> service.delete(
            AiIndexDeleteDTO.builder().appCode("ECWS").sourceType(task).sourceIds(List.of(1L)).build()));
        assertThrows(IllegalArgumentException.class, () -> service.delete(
            AiIndexDeleteDTO.builder().appCode("ELCM").sourceType(task).sourceIds(List.of(1L)).build()));
        assertThrows(IllegalArgumentException.class, () -> service.delete(
            AiIndexDeleteDTO.builder().appCode("ELCM").sourceType(RECORD).sourceIds(List.of()).build()));
        verifyNoInteractions(embeddingRepository);
    }
}
