package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiChatConversationDTO;
import com.dodaso.ecosystem.ai.dto.AiChatFileDTO;
import com.dodaso.ecosystem.ai.dto.AiChatMessageDTO;
import com.dodaso.ecosystem.ai.dto.AiChatTurnDTO;
import com.dodaso.ecosystem.ai.dto.AiGeneratedFileDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.AiChatConversation;
import com.dodaso.ecosystem.ai.entity.AiChatMessage;
import com.dodaso.ecosystem.ai.repository.AiChatConversationRepository;
import com.dodaso.ecosystem.ai.repository.AiChatFileRepository;
import com.dodaso.ecosystem.ai.repository.AiChatMessageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** Saved AI Search chats: saving turns, owner checks, list, open, rename and delete. */
class AiChatHistoryServiceTest {

    private static final String TASK = EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType();
    private static final String COMMENT = EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType();

    private AiChatConversationRepository conversationRepository;
    private AiChatMessageRepository messageRepository;
    private AiChatFileRepository fileRepository;
    private AiChatHistoryService service;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(AiChatConversationRepository.class);
        messageRepository = mock(AiChatMessageRepository.class);
        fileRepository = mock(AiChatFileRepository.class);
        service = new AiChatHistoryService();
        ReflectionTestUtils.setField(service, "conversationRepository", conversationRepository);
        ReflectionTestUtils.setField(service, "messageRepository", messageRepository);
        ReflectionTestUtils.setField(service, "chatFileRepository", fileRepository);
        ReflectionTestUtils.setField(service, "model", "gpt-5-mini");
        // save() returns the row with its new id, as the database would
        when(conversationRepository.save(any())).thenAnswer(call -> {
            AiChatConversation c = call.getArgument(0);
            if (c.getId() == null) {
                c.setId(7L);
            }
            return c;
        });
        when(messageRepository.save(any())).thenAnswer(call -> {
            AiChatMessage m = call.getArgument(0);
            m.setId(70L);
            return m;
        });
    }

    private static List<AiSearchResultDTO> sources() {
        return List.of(
            AiSearchResultDTO.builder().sourceType(TASK).sourceId(12L).taskId(12L).chunkText("Fix the login page").build(),
            AiSearchResultDTO.builder().sourceType(COMMENT).sourceId(40L).parentSourceId(12L).taskId(12L)
                .chunkText("Login fails on Safari").build());
    }

    private static AiChatHistoryService.Turn turn(String question) {
        return new AiChatHistoryService.Turn(question, "Safari fails [2].", "login issue", sources(),
            List.of(new AiChatFileDTO("notes.txt", "abc".getBytes())), List.of(), 1200L);
    }

    private static AiChatConversation chat(Long id, String owner, int count) {
        AiChatConversation c = new AiChatConversation();
        c.setId(id);
        c.setOwnerLoginId(owner);
        c.setTitle("login issue");
        c.setMessageCount(count);
        c.setLastMessageAt(Instant.parse("2026-10-01T00:00:00Z"));
        return c;
    }

    @Test
    void firstTurnCreatesAChatTitledFromTheQuestion() {
        AiChatHistoryService.Saved saved = service.save(null, " kim ", null, 1L, turn("login\n  issue"));

        assertEquals(new AiChatHistoryService.Saved(7L, 70L), saved);
        ArgumentCaptor<AiChatConversation> chat = ArgumentCaptor.forClass(AiChatConversation.class);
        verify(conversationRepository, org.mockito.Mockito.times(2)).save(chat.capture());
        AiChatConversation created = chat.getValue();
        assertEquals("kim", created.getOwnerLoginId());
        assertEquals("ECWS", created.getAppCode());
        assertEquals(1L, created.getProjectId());
        assertEquals("login issue", created.getTitle());
        assertEquals(1, created.getMessageCount());

        ArgumentCaptor<AiChatMessage> message = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(messageRepository).save(message.capture());
        assertEquals(7L, message.getValue().getConversationId());
        assertEquals(1, message.getValue().getSeq());
        assertEquals("login\n  issue", message.getValue().getQuestion());
        assertEquals("Safari fails [2].", message.getValue().getAnswer());
        assertEquals("login issue", message.getValue().getSearchText());
        assertEquals("gpt-5-mini", message.getValue().getModel());
        assertEquals(1200L, message.getValue().getLatencyMs());
        assertEquals("[{\"fileName\":\"notes.txt\",\"size\":3}]", message.getValue().getAttachedFiles());
        // no chunk text in the saved sources
        assertFalse(message.getValue().getSources().contains("Safari\""));
        assertFalse(message.getValue().getSources().contains("chunkText"));
        verify(fileRepository, never()).linkToMessage(anyList(), anyLong(), anyLong());
    }

    @Test
    void followUpGetsTheNextSeqAndBumpsTheChat() {
        AiChatConversation existing = chat(7L, "kim", 2);
        when(conversationRepository.findOwnedForUpdate(7L, "kim")).thenReturn(Optional.of(existing));
        when(messageRepository.findMaxSeq(7L)).thenReturn(2);

        service.save(7L, "kim", null, 1L, turn("what about Safari?"));

        ArgumentCaptor<AiChatMessage> message = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(messageRepository).save(message.capture());
        assertEquals(3, message.getValue().getSeq());
        assertEquals(3, existing.getMessageCount());
        assertTrue(existing.getLastMessageAt().isAfter(Instant.parse("2026-10-01T00:00:00Z")));
        // the title stays the first question's
        assertEquals("login issue", existing.getTitle());
    }

    @Test
    void savingToAnotherUsersChatFails() {
        when(conversationRepository.findOwnedForUpdate(7L, "lee")).thenReturn(Optional.empty());

        assertThrows(AiChatHistoryService.ChatNotFoundException.class,
            () -> service.save(7L, "lee", null, 1L, turn("q")));
        verify(messageRepository, never()).save(any());
    }

    @Test
    void savingWithoutAnOwnerFails() {
        assertThrows(IllegalArgumentException.class, () -> service.save(null, " ", null, 1L, turn("q")));
        verifyNoInteractions(conversationRepository, messageRepository);
    }

    @Test
    void madeFilesAreTiedToTheMessage() {
        UUID fileId = UUID.randomUUID();
        AiChatHistoryService.Turn turn = new AiChatHistoryService.Turn("q", "a", null, List.of(), List.of(),
            List.of(AiGeneratedFileDTO.builder().id(fileId).build()), 10L);

        service.save(null, "kim", "ELCM", null, turn);

        verify(fileRepository).linkToMessage(List.of(fileId), 7L, 70L);
    }

    @Test
    void unknownAppCodeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service.save(null, "kim", "XYZ", 1L, turn("q")));
        assertEquals("ELCM", AiChatHistoryService.appCode(" elcm "));
        assertEquals("ECWS", AiChatHistoryService.appCode(null));
    }

    @Test
    void longQuestionTitleIsCut() {
        String title = AiChatHistoryService.title("x".repeat(200));

        assertEquals(AiChatHistoryService.TITLE_CHARS, title.length());
        assertTrue(title.endsWith("..."));
        assertEquals("New chat", AiChatHistoryService.title("  "));
    }

    @Test
    void recentTurnsAreTheLastOnesOldestFirst() {
        AiChatMessage m3 = new AiChatMessage();
        m3.setQuestion("q3");
        m3.setAnswer("a3");
        AiChatMessage m2 = new AiChatMessage();
        m2.setQuestion("q2");
        m2.setAnswer("a2");
        when(messageRepository.findByConversationIdOrderBySeqDesc(7L, PageRequest.of(0, AiRagService.MAX_HISTORY_TURNS)))
            .thenReturn(List.of(m3, m2));

        assertEquals(List.of(new AiChatTurnDTO("q2", "a2"), new AiChatTurnDTO("q3", "a3")), service.recentTurns(7L));
    }

    @Test
    void reopenedChatHasItsSourcesFileNamesAndDownloadableFiles() {
        when(conversationRepository.findOwned(7L, "kim")).thenReturn(Optional.of(chat(7L, "kim", 1)));
        AiChatMessage message = new AiChatMessage();
        message.setId(70L);
        message.setSeq(1);
        message.setQuestion("login issue");
        message.setAnswer("Safari fails [2].");
        message.setSources(AiChatHistoryService.sourcesJson(sources()));
        message.setAttachedFiles(AiChatHistoryService.attachedFilesJson(
            List.of(new AiChatFileDTO("notes.txt", "abc".getBytes()))));
        when(messageRepository.findByConversationIdOrderBySeqAsc(7L)).thenReturn(List.of(message));
        UUID fileId = UUID.randomUUID();
        AiChatFileRepository.FileInfo file = mock(AiChatFileRepository.FileInfo.class);
        when(file.getId()).thenReturn(fileId);
        when(file.getMessageId()).thenReturn(70L);
        when(file.getFileName()).thenReturn("Summary.docx");
        when(fileRepository.findInfoByMessageIds(eq(List.of(70L)), any())).thenReturn(List.of(file));

        List<AiChatMessageDTO> messages = service.messages(7L, "kim").orElseThrow();

        assertEquals(1, messages.size());
        AiChatMessageDTO reopened = messages.get(0);
        assertEquals("Safari fails [2].", reopened.getAnswer());
        assertEquals(2, reopened.getSources().size());
        assertEquals(12L, reopened.getSources().get(1).getTaskId());
        assertEquals(40L, reopened.getSources().get(1).getSourceId());
        assertEquals("Comment #40", reopened.getSources().get(1).getLabel());
        assertNull(reopened.getSources().get(1).getChunkText());
        assertEquals(List.of("notes.txt"), reopened.getFileNames());
        assertEquals(fileId, reopened.getGeneratedFiles().get(0).getId());
        assertNull(reopened.getGeneratedFiles().get(0).getContent());
    }

    @Test
    void anotherUsersChatDoesNotOpen() {
        when(conversationRepository.findOwned(7L, "lee")).thenReturn(Optional.empty());

        assertTrue(service.messages(7L, "lee").isEmpty());
        assertTrue(service.messages(7L, null).isEmpty());
        verifyNoInteractions(messageRepository);
    }

    @Test
    void listIsForTheOwnerWithAPageSizeCap() {
        when(conversationRepository.findRecent(eq("kim"), eq("ECWS"), any(Pageable.class)))
            .thenReturn(List.of(chat(7L, "kim", 3)));

        List<AiChatConversationDTO> list = service.list(" kim ", null, 0, 5000);

        assertEquals(1, list.size());
        assertEquals("login issue", list.get(0).getTitle());
        assertEquals(3, list.get(0).getMessageCount());
        verify(conversationRepository).findRecent("kim", "ECWS", PageRequest.of(0, AiChatHistoryService.MAX_PAGE_SIZE));
        service.list("kim", "ECWS", 0, 0);
        verify(conversationRepository).findRecent("kim", "ECWS", PageRequest.of(0, AiChatHistoryService.DEFAULT_PAGE_SIZE));
        assertTrue(service.list(null, null, 0, 0).isEmpty());
    }

    @Test
    void renameTrimsAndRefusesBlank() {
        AiChatConversation existing = chat(7L, "kim", 1);
        when(conversationRepository.findOwned(7L, "kim")).thenReturn(Optional.of(existing));

        AiChatConversationDTO renamed = service.rename(7L, "kim", "  Safari\nlogin  ").orElseThrow();

        assertEquals("Safari login", renamed.getTitle());
        assertEquals("Safari login", existing.getTitle());
        assertThrows(IllegalArgumentException.class, () -> service.rename(7L, "kim", " "));
        assertTrue(service.rename(8L, "kim", "x").isEmpty());
    }

    @Test
    void deleteIsSoftAndOnlyForTheOwner() {
        AiChatConversation existing = chat(7L, "kim", 1);
        when(conversationRepository.findOwned(7L, "kim")).thenReturn(Optional.of(existing));

        assertTrue(service.delete(7L, "kim"));
        assertEquals("N", existing.getActiveInd());
        assertFalse(service.delete(7L, "lee"));
        verify(conversationRepository, never()).delete(any());
    }
}
