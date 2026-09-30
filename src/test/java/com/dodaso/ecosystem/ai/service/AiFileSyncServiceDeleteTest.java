package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingSyncRepository;
import com.dodaso.ecosystem.baseline.common.container.RESTReqContainer;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.ecws.container.FileAttachmentDTOContainer;
import com.dodaso.ecosystem.ecws.dto.FileAttachmentDTO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * File-embedding delete cleanup (plan PLAN-attachment-to-common-service 짠7.3):
 * deleteFileEmbeddings endpoint logic and the refreshFileSyncs inactive-file pass.
 */
class AiFileSyncServiceDeleteTest {

    private static final String TASK_FILE = EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT.getSourceType();
    private static final String COMMENT_FILE = EmbeddingSourceTypeEnum.COMMENT_FILE_ATTACHMENT.getSourceType();

    private EcwsEmbeddingRepository embeddingRepository;
    private EcwsEmbeddingSyncRepository syncRepository;
    private RESTServiceClient restServiceClient;
    private AiFileSyncService service;

    @BeforeEach
    void setUp() {
        embeddingRepository = mock(EcwsEmbeddingRepository.class);
        syncRepository = mock(EcwsEmbeddingSyncRepository.class);
        restServiceClient = mock(RESTServiceClient.class);
        service = new AiFileSyncService(mock(FileTextExtractorService.class), mock(AiSyncService.class),
            syncRepository, embeddingRepository, restServiceClient);
    }

    private static FileAttachmentDTO file(Integer id) {
        FileAttachmentDTO dto = new FileAttachmentDTO();
        dto.setId(id);
        return dto;
    }

    private void verifyDeleted(long id) {
        verify(embeddingRepository).deleteBySourceTypeAndSourceId(TASK_FILE, id);
        verify(embeddingRepository).deleteBySourceTypeAndSourceId(COMMENT_FILE, id);
        verify(syncRepository).deleteBySourceTypeAndSourceId(TASK_FILE, id);
        verify(syncRepository).deleteBySourceTypeAndSourceId(COMMENT_FILE, id);
    }

    private void verifyNotDeleted(long id) {
        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(anyString(), eq(id));
        verify(syncRepository, never()).deleteBySourceTypeAndSourceId(anyString(), eq(id));
    }

    // ---- deleteFileEmbeddings ----

    @Test
    void deletesBothSourceTypesInBothTablesForEachId() {
        int count = service.deleteFileEmbeddings(List.of(file(10), file(20)));

        assertEquals(2, count);
        verifyDeleted(10L);
        verifyDeleted(20L);
    }

    @Test
    void skipsNullZeroNegativeAndDuplicateIds() {
        List<FileAttachmentDTO> input = new ArrayList<>(Arrays.asList(
            file(5), file(5), file(null), file(0), file(-3), null));

        int count = service.deleteFileEmbeddings(input);

        assertEquals(1, count);
        verify(embeddingRepository, times(2)).deleteBySourceTypeAndSourceId(anyString(), anyLong());
        verify(syncRepository, times(2)).deleteBySourceTypeAndSourceId(anyString(), anyLong());
        verifyDeleted(5L);
    }

    @Test
    void nullOrEmptyListDeletesNothing() {
        assertEquals(0, service.deleteFileEmbeddings(null));
        assertEquals(0, service.deleteFileEmbeddings(List.of()));
        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
        verify(syncRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
    }

    // ---- refreshFileSyncs inactive cleanup ----

    private void activeFilesAre(FileAttachmentDTOContainer response) throws Exception {
        when(restServiceClient.callRESTService(any(RESTReqContainer.class))).thenReturn(response);
    }

    private static FileAttachmentDTOContainer active(Integer... ids) {
        FileAttachmentDTOContainer c = new FileAttachmentDTOContainer();
        c.setFileAttachmentDTOList(Arrays.stream(ids).map(AiFileSyncServiceDeleteTest::file).toList());
        return c;
    }

    private void storedIdsAre(List<Long> embeddingIds, List<Long> syncIds) {
        when(embeddingRepository.findDistinctSourceIdsBySourceTypeIn(any())).thenReturn(embeddingIds);
        when(syncRepository.findDistinctSourceIdsBySourceTypeIn(any())).thenReturn(syncIds);
        // mark active ids as synced so the loop skips downloading
        when(syncRepository.findBySourceTypeAndSourceId(anyString(), anyLong()))
            .thenAnswer(inv -> Optional.of(completedSync()));
    }

    private static com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync completedSync() {
        com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync s = new com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync();
        s.setStatus("COMPLETED");
        return s;
    }

    @Test
    void refreshRemovesStoredIdsThatAreNoLongerActive() throws Exception {
        storedIdsAre(List.of(1L, 2L, 3L), List.of(3L, 4L));
        activeFilesAre(active(1, 3));

        service.refreshFileSyncs();

        verifyDeleted(2L);   // embedding only
        verifyDeleted(4L);   // sync record only
        verifyNotDeleted(1L);
        verifyNotDeleted(3L);
    }

    @Test
    void refreshSkipsCleanupWhenActiveListIsEmpty() throws Exception {
        storedIdsAre(List.of(1L, 2L), List.of());
        activeFilesAre(active());

        service.refreshFileSyncs();

        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
        verify(syncRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
    }

    @Test
    void refreshSkipsCleanupWhenEcwsReturnsNull() throws Exception {
        storedIdsAre(List.of(1L), List.of());
        activeFilesAre(null);

        service.refreshFileSyncs();

        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
    }

    @Test
    void refreshSkipsCleanupWhenEcwsCallThrows() throws Exception {
        storedIdsAre(List.of(1L), List.of());
        when(restServiceClient.callRESTService(any(RESTReqContainer.class)))
            .thenThrow(new RuntimeException("ecws down"));

        service.refreshFileSyncs();

        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
    }

    @Test
    void fileUploadedDuringRefreshIsNotACleanupCandidate() throws Exception {
        // id 9 is not in the stored set read at the start, even though it's missing from active
        storedIdsAre(List.of(1L), List.of());
        activeFilesAre(active(1));

        service.refreshFileSyncs();

        verifyNotDeleted(9L);
        verifyNotDeleted(1L);
    }
}
