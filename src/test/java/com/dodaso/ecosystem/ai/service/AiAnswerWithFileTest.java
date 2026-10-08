package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.dto.AiGeneratedFileDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsSearchHistoryRepository;
import com.dodaso.ecosystem.ai.service.AiAnswerSpec.BlockSpec;
import com.dodaso.ecosystem.ai.service.AiAnswerSpec.FileSpec;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.test.util.ReflectionTestUtils;

/** Answers that may come with a Word or Excel file the user asked for. */
class AiAnswerWithFileTest {

    private static final String TASK = EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType();
    private static final List<AiSearchResultDTO> SOURCES = List.of(AiSearchResultDTO.builder()
        .sourceType(TASK).sourceId(12L).taskId(12L).chunkText("Fix the login page").build());
    private static final FileSpec SPEC = new FileSpec("DOCX", "Summary", null,
        List.of(new BlockSpec("paragraph", null, "Fix login [1].", null, null, null, null)));

    private ChatClient openAiClient;
    private AiRagService rag;

    @BeforeEach
    void setUp() {
        openAiClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        rag = new AiRagService(mock(ChatClient.class), mock(EcwsEmbeddingRepository.class), mock(OllamaService.class));
        ReflectionTestUtils.setField(rag, "openAiChatClient", openAiClient);
    }

    private void reply(String json) {
        when(openAiClient.prompt().system(anyString()).user(anyString()).messages(anyList())
            .options(any(ChatOptions.class)).call().content()).thenReturn(json);
    }

    @Test
    void plainQuestionHasNoFile() {
        reply("{\"answer\": \"Login fails on Safari [1].\", \"file\": null}");

        AiRagService.AiAnswer answer = rag.answerWithFile("login issue", SOURCES, null, List.of(), "kim", null);

        assertEquals("Login fails on Safari [1].", answer.text());
        assertNull(answer.file());
    }

    @Test
    void askedForAFileReturnsItsSpec() {
        reply("""
            {"answer": "Word 문서를 만들었습니다.", "file": {"format": "DOCX", "fileName": "요약", "title": null,
             "blocks": [{"type": "heading", "level": 1, "text": "결정", "items": null, "name": null,
                         "columns": null, "rows": null}]}}""");

        AiRagService.AiAnswer answer = rag.answerWithFile("워드로 요약해줘", SOURCES, null, List.of(), "kim", null);

        assertEquals("Word 문서를 만들었습니다.", answer.text());
        assertEquals("DOCX", answer.file().format());
        assertEquals("결정", answer.file().blocks().get(0).text());
    }

    @Test
    void replyThatIsNotJsonIsShownAsTextWithoutAFile() {
        reply("Login fails on Safari [1].");

        AiRagService.AiAnswer answer = rag.answerWithFile("login issue", SOURCES, null, List.of(), "kim", null);

        assertEquals("Login fails on Safari [1].", answer.text());
        assertNull(answer.file());
        assertEquals("{\"file\": null}", AiRagService.parseAnswer("{\"file\": null}").text());
    }

    @Test
    void asksOpenAiForStrictJsonAndALongerReply() {
        reply("{\"answer\": \"ok\", \"file\": null}");
        ArgumentCaptor<ChatOptions> options = ArgumentCaptor.forClass(ChatOptions.class);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);

        rag.answerWithFile("q", SOURCES, null, List.of(), "kim", null);

        // the stubbing in reply() counts as a call too; the last one is answerWithFile's
        verify(openAiClient.prompt(), atLeastOnce()).system(system.capture());
        assertTrue(system.getValue().contains("Reply as JSON with \"answer\" and \"file\""));
        verify(openAiClient.prompt().system(anyString()).user(anyString()).messages(anyList()), atLeastOnce())
            .options(options.capture());
        OpenAiChatOptions sent = (OpenAiChatOptions) options.getValue();
        assertEquals(ResponseFormat.Type.JSON_SCHEMA, sent.getResponseFormat().getType());
        assertTrue(sent.getResponseFormat().getJsonSchema().getStrict());
        assertNotNull(sent.getResponseFormat().getJsonSchema().getSchema().get("$defs"));
        assertEquals(AiRagService.FILE_ANSWER_MAX_TOKENS, sent.getMaxCompletionTokens());
    }

    @Test
    void answerLanguageFollowsTheQuestionNotTheSources() {
        assertTrue(AiRagService.languageInstructions("summarize the login issue").startsWith(
            "Write your answer in English"));
        assertTrue(AiRagService.languageInstructions("로그인 문제를 요약해줘").startsWith(
            "Write your answer in Korean"));
        assertTrue(AiRagService.languageInstructions("Task #12 요약").startsWith("Write your answer in Korean"));
        assertTrue(AiRagService.languageInstructions(null).startsWith("Write your answer in English"));

        reply("{\"answer\": \"ok\", \"file\": null}");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        rag.answerWithFile("엑셀로 정리해줘", SOURCES, null, List.of(), "kim", null);
        verify(openAiClient.prompt(), atLeastOnce()).system(system.capture());
        assertTrue(system.getValue().contains("Write your answer in Korean"));
    }

    @Test
    void withoutALoginIdTheAnswerIsPlainText() {
        when(openAiClient.prompt().system(anyString()).user(anyString()).messages(anyList()).call().content())
            .thenReturn("Login fails [1].");

        AiRagService.AiAnswer answer = rag.answerWithFile("q", SOURCES, null, List.of(), null, null);

        assertEquals("Login fails [1].", answer.text());
        assertNull(answer.file());
        verify(openAiClient.prompt().system(anyString()).user(anyString()).messages(anyList()), never())
            .options(any(ChatOptions.class));
    }

    @Test
    void nothingToAnswerFromMeansNoModelCall() {
        AiRagService.AiAnswer answer = rag.answerWithFile("q", List.of(), null, List.of(), "kim", List.of());

        assertSame(AiRagService.NOTHING_FOUND, answer.text());
        verifyNoInteractions(openAiClient);
    }

    // ---- generate ----

    private AiSearchService searchService(AiRagService ragService, AiDocumentService documentService) {
        EcwsEmbeddingRepository embeddingRepository = mock(EcwsEmbeddingRepository.class);
        EcwsEmbedding emb = new EcwsEmbedding();
        emb.setSourceType(TASK);
        emb.setSourceId(12L);
        emb.setTaskId(12L);
        emb.setChunkText("Fix the login page");
        when(embeddingRepository.findSimilarAll(eq(1L), anyString(), anyInt())).thenReturn(List.of(emb));
        OllamaService ollamaService = mock(OllamaService.class);
        when(ollamaService.getOllamaEmbedding(anyString())).thenReturn(new float[] {0.1f});

        AiSearchService service = new AiSearchService(mock(ChatModel.class));
        ReflectionTestUtils.setField(service, "embeddingRepository", embeddingRepository);
        ReflectionTestUtils.setField(service, "searchHistoryRepository", mock(EcwsSearchHistoryRepository.class));
        ReflectionTestUtils.setField(service, "ollamaService", ollamaService);
        ReflectionTestUtils.setField(service, "aiRagService", ragService);
        ReflectionTestUtils.setField(service, "earsAlertService", mock(EarsAlertService.class));
        ReflectionTestUtils.setField(service, "fileTextExtractorService", new FileTextExtractorService());
        ReflectionTestUtils.setField(service, "aiDocumentService", documentService);
        return service;
    }

    private static AiGenerateDTO request(String loginId) {
        AiGenerateDTO request = new AiGenerateDTO("summarize as Word");
        request.setProjectId(1L);
        request.setLoginId(loginId);
        return request;
    }

    @Test
    void generateReturnsTheMadeFileAndItsNote() throws IOException {
        AiRagService ragService = mock(AiRagService.class);
        when(ragService.answerWithFile(any(), any(), any(), any(), any(), any()))
            .thenReturn(new AiRagService.AiAnswer("Made a Word file.", SPEC));
        AiDocumentService documentService = mock(AiDocumentService.class);
        AiGeneratedFileDTO file = AiGeneratedFileDTO.builder().id(UUID.randomUUID()).fileName("Summary.docx").build();
        when(documentService.create(eq(SPEC), eq("kim"), anyList()))
            .thenReturn(new AiDocumentService.Created(file, AiDocumentService.CUT_NOTE));

        AiGenerateDTO response = searchService(ragService, documentService).generate(request("kim"));

        assertEquals(List.of(file), response.getGeneratedFiles());
        assertEquals("Made a Word file.\n\n" + AiDocumentService.CUT_NOTE, response.getGeneratedText());
        verify(documentService).create(SPEC, "kim", response.getSources());
    }

    @Test
    void generateStillAnswersWhenTheFileFails() throws IOException {
        AiRagService ragService = mock(AiRagService.class);
        when(ragService.answerWithFile(any(), any(), any(), any(), any(), any()))
            .thenReturn(new AiRagService.AiAnswer("Made a Word file.", SPEC));
        AiDocumentService documentService = mock(AiDocumentService.class);
        when(documentService.create(any(), any(), any())).thenThrow(new IOException("disk"));

        AiGenerateDTO response = searchService(ragService, documentService).generate(request("kim"));

        assertTrue(response.getGeneratedFiles().isEmpty());
        assertEquals("Made a Word file.\n\n" + AiSearchService.FILE_FAILED, response.getGeneratedText());
    }

    @Test
    void generateMakesNoFileWithoutALoginId() {
        AiRagService ragService = mock(AiRagService.class);
        when(ragService.answerWithFile(any(), any(), any(), any(), any(), any()))
            .thenReturn(new AiRagService.AiAnswer("text", SPEC));
        AiDocumentService documentService = mock(AiDocumentService.class);

        AiGenerateDTO response = searchService(ragService, documentService).generate(request(null));

        assertTrue(response.getGeneratedFiles().isEmpty());
        verifyNoInteractions(documentService);
    }
}
