package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiAlertDTO;
import com.dodaso.ecosystem.ai.dto.AiChatTurnDTO;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsSearchHistoryRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

/** Generate answers from the same results search returns. */
class AiSearchGenerateTest {

    private static final String TASK = EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType();
    private static final String COMMENT = EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType();

    private EcwsEmbeddingRepository embeddingRepository;
    private EcwsSearchHistoryRepository searchHistoryRepository;
    private OllamaService ollamaService;
    private AiRagService ragService;
    private EarsAlertService earsAlertService;
    private AiSearchService searchService;

    @BeforeEach
    void setUp() {
        embeddingRepository = mock(EcwsEmbeddingRepository.class);
        searchHistoryRepository = mock(EcwsSearchHistoryRepository.class);
        ollamaService = mock(OllamaService.class);
        ragService = mock(AiRagService.class);
        earsAlertService = mock(EarsAlertService.class);
        when(ollamaService.getOllamaEmbedding(anyString())).thenReturn(new float[] {0.1f, 0.2f});

        searchService = new AiSearchService(mock(ChatModel.class));
        ReflectionTestUtils.setField(searchService, "embeddingRepository", embeddingRepository);
        ReflectionTestUtils.setField(searchService, "searchHistoryRepository", searchHistoryRepository);
        ReflectionTestUtils.setField(searchService, "ollamaService", ollamaService);
        ReflectionTestUtils.setField(searchService, "aiRagService", ragService);
        ReflectionTestUtils.setField(searchService, "earsAlertService", earsAlertService);
    }

    private static EcwsEmbedding emb(String type, Long sourceId, Long taskId, String text) {
        EcwsEmbedding e = new EcwsEmbedding();
        e.setSourceType(type);
        e.setSourceId(sourceId);
        e.setTaskId(taskId);
        e.setChunkText(text);
        return e;
    }

    private static AiGenerateDTO request(String prompt, Long projectId) {
        AiGenerateDTO dto = new AiGenerateDTO(prompt);
        dto.setProjectId(projectId);
        return dto;
    }

    @Test
    void answersFromSearchResultsAndReturnsThemAsSources() {
        when(embeddingRepository.findSimilarAll(eq(1L), anyString(), anyInt())).thenReturn(List.of(
            emb(TASK, 12L, 12L, "Fix the login page"),
            emb(COMMENT, 40L, 12L, "Login fails on Safari")));
        // when(ragService.answer(eq("login issue"), any())).thenReturn("Safari login fails [2].");
        // when(ragService.answer(eq("login issue"), any(), any())).thenReturn("Safari login fails [2].");
        when(ragService.answer(eq("login issue"), any(), any(), any())).thenReturn("Safari login fails [2].");

        AiGenerateDTO response = searchService.generate(request("  login issue ", 1L));

        assertEquals("Safari login fails [2].", response.getGeneratedText());
        assertEquals(2, response.getSources().size());
        assertEquals(12L, response.getSources().get(1).getTaskId());
        assertEquals(40L, response.getSources().get(1).getSourceId());
        // verify(ragService).answer("login issue", response.getSources());
        // verify(ragService).answer("login issue", response.getSources(), null);
        verify(ragService).answer("login issue", response.getSources(), null, List.of());
        // generate is not a search; it doesn't add a history row
        verifyNoInteractions(searchHistoryRepository);
    }

    @Test
    void followUpFindsSourcesWithSearchTextAndPassesHistory() {
        when(embeddingRepository.findSimilarAll(eq(1L), anyString(), anyInt()))
            .thenReturn(List.of(emb(TASK, 12L, 12L, "Login fails on Safari")));
        List<AiChatTurnDTO> history = List.of(new AiChatTurnDTO("login issue", "Login fails [1]."));
        AiGenerateDTO request = request("what about Safari?", 1L);
        request.setSearchText("login issue what about Safari?");
        request.setHistory(history);

        searchService.generate(request);

        verify(ollamaService).getOllamaEmbedding("login issue what about Safari?");
        // verify(ragService).answer(eq("what about Safari?"), any(), eq(history));
        verify(ragService).answer(eq("what about Safari?"), any(), eq(history), any());
    }

    @Test
    void blankSearchTextFallsBackToPrompt() {
        AiGenerateDTO request = request("login", 1L);
        request.setSearchText("  ");

        searchService.generate(request);

        verify(ollamaService).getOllamaEmbedding("login");
    }

    @Test
    void historyBecomesMessagesWithoutOldCitations() {
        List<AiChatTurnDTO> history = new java.util.ArrayList<>();
        for (int i = 1; i <= AiRagService.MAX_HISTORY_TURNS + 1; i++) {
            history.add(new AiChatTurnDTO("q" + i, "a" + i + " [1][2]."));
        }
        history.add(new AiChatTurnDTO("unanswered", null));

        List<Message> messages = AiRagService.historyMessages(history);

        // the oldest turn is dropped, the unanswered one skipped
        assertEquals(AiRagService.MAX_HISTORY_TURNS * 2, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
        assertEquals("q2", messages.get(0).getText());
        assertTrue(messages.get(1) instanceof AssistantMessage);
        assertEquals("a2.", messages.get(1).getText());
        assertTrue(AiRagService.historyMessages(null).isEmpty());
    }

    @Test
    void searchStillWritesHistory() {
        when(embeddingRepository.findSimilarAll(eq(1L), anyString(), anyInt()))
            .thenReturn(List.of(emb(TASK, 12L, 12L, "Fix the login page")));

        List<AiSearchResultDTO> results = searchService.search(1L, null, "login");

        assertEquals(1, results.size());
        verify(searchHistoryRepository).save(any());
    }

    @Test
    void projectIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> searchService.generate(request("login", null)));
        verify(embeddingRepository, never()).findSimilarAll(any(), anyString(), anyInt());
    }

    @Test
    void emptyPromptIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> searchService.generate(request("  ", 1L)));
    }

    @Test
    void noSourcesMeansNoModelCall() {
        ChatClient ollamaClient = mock(ChatClient.class);
        ChatClient openAiClient = mock(ChatClient.class);
        AiRagService realRag = new AiRagService(ollamaClient, embeddingRepository, ollamaService);
        ReflectionTestUtils.setField(realRag, "openAiChatClient", openAiClient);

        assertSame(AiRagService.NOTHING_FOUND, realRag.answer("anything", List.of()));
        verifyNoInteractions(ollamaClient, openAiClient);
    }

    @Test
    void answerUsesOpenAiNotOllama() {
        ChatClient ollamaClient = mock(ChatClient.class);
        ChatClient openAiClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        // when(openAiClient.prompt().system(anyString()).user(anyString()).call().content())
        when(openAiClient.prompt().system(anyString()).user(anyString()).messages(anyList()).call().content())
            .thenReturn("Kim uploaded it [1].");
        AiRagService realRag = new AiRagService(ollamaClient, embeddingRepository, ollamaService);
        ReflectionTestUtils.setField(realRag, "openAiChatClient", openAiClient);

        String answer = realRag.answer("who uploaded it", List.of(
            AiSearchResultDTO.builder().sourceType(TASK).sourceId(12L).taskId(12L)
                .chunkText("Kim uploaded the file").build()));

        assertEquals("Kim uploaded it [1].", answer);
        verifyNoInteractions(ollamaClient);
    }

    @Test
    void promptNumbersSourcesInResultOrder() {
        List<AiSearchResultDTO> sources = List.of(
            AiSearchResultDTO.builder().sourceType(TASK).sourceId(12L).taskId(12L)
                .chunkText("Fix the login page").build(),
            AiSearchResultDTO.builder().sourceType(COMMENT).sourceId(40L).taskId(12L)
                .chunkText("Login fails on Safari").build());

        String prompt = AiRagService.buildAnswerPrompt("login issue", sources);

        assertTrue(prompt.contains("[1] Task #12\nFix the login page"));
        assertTrue(prompt.contains("[2] Task #12, Comment #40\nLogin fails on Safari"));
        assertFalse(prompt.contains("Task #12, Task #12"));
        assertTrue(prompt.endsWith("Question: login issue"));
    }

    @Test
    void promptKeepsFirstSourcesAndShortensLongText() {
        List<AiSearchResultDTO> sources = new java.util.ArrayList<>();
        for (long i = 1; i <= AiRagService.MAX_ANSWER_SOURCES + 2; i++) {
            sources.add(AiSearchResultDTO.builder().sourceType(TASK).sourceId(i).taskId(i)
                .chunkText("x".repeat(AiRagService.MAX_SOURCE_CHARS + 100)).build());
        }

        String prompt = AiRagService.buildAnswerPrompt("q", sources);

        assertTrue(prompt.contains("[" + AiRagService.MAX_ANSWER_SOURCES + "] Task #"));
        assertFalse(prompt.contains("[" + (AiRagService.MAX_ANSWER_SOURCES + 1) + "]"));
        assertTrue(prompt.contains("x".repeat(AiRagService.MAX_SOURCE_CHARS) + "..."));
        assertFalse(prompt.contains("x".repeat(AiRagService.MAX_SOURCE_CHARS + 1)));
    }

    private static AiAlertDTO alert(int id, String table, Integer sourceId, String subject) {
        return AiAlertDTO.builder().id(id).sourceReferenceTable(table).sourceReferenceId(sourceId)
            .subject(subject).build();
    }

    @Test
    void addsUserAndTaskAlertsOnceEachAndReturnsThem() {
        when(embeddingRepository.findSimilarAll(eq(1L), anyString(), anyInt())).thenReturn(List.of(
            emb(TASK, 12L, 12L, "Fix the login page"),
            emb(COMMENT, 40L, 12L, "Login fails on Safari")));
        AiAlertDTO mine = alert(1, "workflow", 7, "Approval due");
        AiAlertDTO onTask = alert(2, EarsAlertService.TASK_TABLE, 12, "Task past due");
        when(earsAlertService.findUserAlerts("kim")).thenReturn(List.of(mine, onTask));
        when(earsAlertService.findTaskAlerts(List.of(12L, 12L))).thenReturn(List.of(onTask));
        AiGenerateDTO request = request("what is overdue?", 1L);
        request.setLoginId("kim");

        AiGenerateDTO response = searchService.generate(request);

        assertEquals(List.of(mine, onTask), response.getAlerts());
        verify(ragService).answer(eq("what is overdue?"), any(), any(), eq(List.of(mine, onTask)));
    }

    @Test
    void alertsWithoutSourcesStillCallTheModel() {
        ChatClient openAiClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(openAiClient.prompt().system(anyString()).user(anyString()).messages(anyList()).call().content())
            .thenReturn("Task #12 is overdue.");
        AiRagService realRag = new AiRagService(mock(ChatClient.class), embeddingRepository, ollamaService);
        ReflectionTestUtils.setField(realRag, "openAiChatClient", openAiClient);

        String answer = realRag.answer("what is overdue?", List.of(), null,
            List.of(alert(2, EarsAlertService.TASK_TABLE, 12, "Task past due")));

        assertEquals("Task #12 is overdue.", answer);
        assertSame(AiRagService.NOTHING_FOUND, realRag.answer("q", List.of(), null, List.of()));
    }

    @Test
    void promptListsAlertsAfterSourcesWithoutNumbers() {
        List<AiSearchResultDTO> sources = List.of(AiSearchResultDTO.builder().sourceType(TASK)
            .sourceId(12L).taskId(12L).chunkText("Fix the login page").build());
        AiAlertDTO onTask = AiAlertDTO.builder().id(2).sourceReferenceTable(EarsAlertService.TASK_TABLE)
            .sourceReferenceId(12).priority("High").readInd((byte) 0)
            .subject("Task past due").body("Due  2026-10-01").build();

        String prompt = AiRagService.buildAnswerPrompt("what is overdue?", sources,
            List.of(onTask, alert(3, null, null, "Weekly reminder")), java.time.LocalDate.of(2026, 10, 6));

        assertTrue(prompt.contains("[1] Task #12\nFix the login page"));
        assertTrue(prompt.contains("Alerts and reminders (today is 2026-10-06):\n"
            + "- Task #12, High, unread: Task past due - Due 2026-10-01\n"
            + "- General: Weekly reminder\n"));
        assertFalse(prompt.contains("[2]"));
        assertTrue(prompt.indexOf("[1] Task #12") < prompt.indexOf("Alerts and reminders"));
        assertTrue(prompt.endsWith("Question: what is overdue?"));
    }

    @Test
    void promptWithoutSourcesSaysNone() {
        String prompt = AiRagService.buildAnswerPrompt("q", List.of(),
            List.of(alert(1, "workflow", 7, "Approval due")), null);

        assertTrue(prompt.startsWith(
            "Sources:\n(none)\n\nAlerts and reminders:\n- workflow #7: Approval due\n"));
    }
}
