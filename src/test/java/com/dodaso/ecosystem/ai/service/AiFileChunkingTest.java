package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingSyncRepository;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.ecws.dto.CollaborationTaskDTO;
import com.dodaso.ecosystem.ecws.dto.FileAttachmentDTO;
import com.dodaso.ecosystem.ecws.dto.TaskCommentDTO;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/** File chunking keeps every chunk (plan PLAN-attachment-to-common-service §7.4, C12b). */
class AiFileChunkingTest {

    private static final String TASK_FILE = EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT.getSourceType();
    private static final String COMMENT_FILE = EmbeddingSourceTypeEnum.COMMENT_FILE_ATTACHMENT.getSourceType();

    private EcwsEmbeddingRepository embeddingRepository;
    private EcwsEmbeddingSyncRepository syncRepository;
    private OllamaService ollamaService;
    private AiSyncService syncService;

    @BeforeEach
    void setUp() {
        embeddingRepository = mock(EcwsEmbeddingRepository.class);
        syncRepository = mock(EcwsEmbeddingSyncRepository.class);
        ollamaService = mock(OllamaService.class);
        when(ollamaService.getOllamaEmbedding(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(syncRepository.findBySourceTypeAndSourceId(anyString(), anyLong())).thenReturn(Optional.empty());
        when(syncRepository.save(any(EcwsEmbeddingSync.class))).thenAnswer(inv -> inv.getArgument(0));

        syncService = new AiSyncService();
        ReflectionTestUtils.setField(syncService, "embeddingRepository", embeddingRepository);
        ReflectionTestUtils.setField(syncService, "syncRepository", syncRepository);
        ReflectionTestUtils.setField(syncService, "ollamaService", ollamaService);
    }

    private static AiSearchSyncDTO chunk(int i, int n) {
        return AiSearchSyncDTO.builder().sourceType(TASK_FILE).sourceId(536L).parentSourceId(70L)
            .summary("f.txt [chunk " + (i + 1) + "/" + n + "]").description("text " + i).build();
    }

    @SuppressWarnings("unchecked")
    private List<EcwsEmbedding> savedRows() {
        ArgumentCaptor<List<EcwsEmbedding>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).saveAllAndFlush(captor.capture());
        return captor.getValue();
    }

    // ---- syncChunks ----

    @Test
    void storesOneRowPerChunkWithIndexes() {
        syncService.syncChunks(TASK_FILE, 536L, List.of(chunk(0, 3), chunk(1, 3), chunk(2, 3)));

        List<EcwsEmbedding> rows = savedRows();
        assertEquals(3, rows.size());
        assertEquals(List.of(0, 1, 2), rows.stream().map(EcwsEmbedding::getChunkIndex).toList());
        assertTrue(rows.stream().allMatch(r -> r.getSourceId() == 536L && TASK_FILE.equals(r.getSourceType())));
        assertTrue(rows.stream().allMatch(r -> Long.valueOf(70L).equals(r.getTaskId())));
        assertTrue(rows.get(1).getChunkText().contains("text 1"));
    }

    @Test
    void deletesOldRowsOnceBeforeSavingAndMarksCompleted() {
        syncService.syncChunks(TASK_FILE, 536L, List.of(chunk(0, 2), chunk(1, 2)));

        InOrder order = inOrder(embeddingRepository);
        order.verify(embeddingRepository).deleteBySourceTypeAndSourceId(TASK_FILE, 536L);
        order.verify(embeddingRepository).saveAllAndFlush(any());
        ArgumentCaptor<EcwsEmbeddingSync> sync = ArgumentCaptor.forClass(EcwsEmbeddingSync.class);
        verify(syncRepository).saveAndFlush(sync.capture());
        assertEquals("COMPLETED", sync.getValue().getStatus());
    }

    @Test
    void failedEmbeddingKeepsOldRowsAndMarksFailed() {
        when(ollamaService.getOllamaEmbedding(anyString()))
            .thenReturn(new float[] {0.1f})
            .thenThrow(new RuntimeException("ollama down"));

        assertThrows(RuntimeException.class,
            () -> syncService.syncChunks(TASK_FILE, 536L, List.of(chunk(0, 2), chunk(1, 2))));

        verify(embeddingRepository, never()).deleteBySourceTypeAndSourceId(anyString(), anyLong());
        verify(embeddingRepository, never()).saveAllAndFlush(any());
        ArgumentCaptor<EcwsEmbeddingSync> sync = ArgumentCaptor.forClass(EcwsEmbeddingSync.class);
        verify(syncRepository, org.mockito.Mockito.atLeastOnce()).save(sync.capture());
        assertEquals("FAILED", sync.getValue().getStatus());
    }

    // ---- syncFileAsync → chunks ----

    private static String words(int n) {
        return IntStream.range(0, n).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
    }

    @SuppressWarnings("unchecked")
    private List<AiSearchSyncDTO> chunksSentFor(String text, FileAttachmentDTO attachment) throws Exception {
        FileTextExtractorService extractor = mock(FileTextExtractorService.class);
        when(extractor.extract(any(), anyString())).thenReturn(text);
        AiSyncService aiSyncService = mock(AiSyncService.class);
        AiFileSyncService fileSync = new AiFileSyncService(extractor, aiSyncService, syncRepository,
            embeddingRepository, mock(RESTServiceClient.class));

        fileSync.syncFileAsync(attachment, new ByteArrayInputStream(new byte[] {1}));

        ArgumentCaptor<List<AiSearchSyncDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiSyncService).syncChunks(anyString(), eq((long) attachment.getId()), captor.capture());
        verify(aiSyncService, never()).sync(any());
        return captor.getValue();
    }

    private static FileAttachmentDTO taskFile() {
        FileAttachmentDTO a = new FileAttachmentDTO();
        a.setId(536);
        a.setFileName("long.txt");
        CollaborationTaskDTO task = new CollaborationTaskDTO();
        task.setId(70);
        a.setCollaborationTaskDTO(task);
        return a;
    }

    @Test
    void longFileIsSentAsAllItsChunksInOneCall() throws Exception {
        List<AiSearchSyncDTO> chunks = chunksSentFor(words(1200), taskFile());

        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).getDescription().startsWith("w0 "));
        assertTrue(chunks.get(1).getDescription().startsWith("w500 "));
        assertTrue(chunks.get(2).getDescription().startsWith("w1000 "));
        assertTrue(chunks.get(2).getDescription().endsWith("w1199"));
        assertEquals("long.txt [chunk 3/3]", chunks.get(2).getSummary());
        assertTrue(chunks.stream().allMatch(c -> Long.valueOf(70L).equals(c.getTaskId())));
    }

    @Test
    void commentFileChunksCarryCommentParentAndTask() throws Exception {
        FileAttachmentDTO a = taskFile();
        TaskCommentDTO comment = new TaskCommentDTO();
        comment.setId(424);
        a.setTaskCommentDTO(comment);

        List<AiSearchSyncDTO> chunks = chunksSentFor(words(600), a);

        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> COMMENT_FILE.equals(c.getSourceType())
            && Long.valueOf(424L).equals(c.getParentSourceId())
            && Long.valueOf(70L).equals(c.getTaskId())));
    }

    @Test
    void shortFileIsOneChunk() throws Exception {
        assertEquals(1, chunksSentFor(words(10), taskFile()).size());
    }
}
