package com.dodaso.ecosystem.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.container.AiChatDTOContainer;
import com.dodaso.ecosystem.ai.container.AiGenerateDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiChatConversationDTO;
import com.dodaso.ecosystem.ai.dto.AiChatMessageDTO;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.service.AiChatHistoryService;
import com.dodaso.ecosystem.ai.service.AiSearchService;
import com.dodaso.ecosystem.baseline.common.config.CompanySettings;
import com.dodaso.ecosystem.baseline.common.security.VerifiedCaller;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Saved chat endpoints answer 404 for a chat that isn't the caller's. */
class AiChatControllerTest {

    private AiChatHistoryService historyService;
    private AiChatController controller;

    @BeforeEach
    void setUp() {
        historyService = mock(AiChatHistoryService.class);
        controller = new AiChatController();
        ReflectionTestUtils.setField(controller, "aiChatHistoryService", historyService);
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void signIn(String login) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(VerifiedCaller.ATTRIBUTE, new VerifiedCaller(login, List.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void withATokenTheTokensLoginOwnsTheChats() {
        signIn("kim");
        when(historyService.list("kim", null, 0, 0)).thenReturn(List.of(new AiChatConversationDTO()));

        ResponseEntity<?> response = controller.listConversations(request(null, "lee"));

        assertEquals(1, ((AiChatDTOContainer) response.getBody()).getAiChatConversationDTOList().size());
        verify(historyService).list("kim", null, 0, 0);
    }

    @Test
    void withATokenGenerateActsForTheTokensLogin() throws Exception {
        signIn("kim");
        AiSearchService searchService = mock(AiSearchService.class);
        AiRagController ragController = new AiRagController();
        ReflectionTestUtils.setField(ragController, "aiSearchService", searchService);
        ReflectionTestUtils.setField(ragController, "companySettings", new CompanySettings(null, null));
        AiGenerateDTOContainer request = new AiGenerateDTOContainer();
        request.setAiGenerateDTO(new AiGenerateDTO("q"));
        request.getAiGenerateDTO().setLoginId("lee");

        ragController.generate(request);

        verify(searchService).generate(org.mockito.ArgumentMatchers.argThat(dto -> "kim".equals(dto.getLoginId())));
    }

    private static AiChatDTOContainer request(Long id, String loginId) {
        AiChatDTOContainer request = new AiChatDTOContainer();
        request.setLoginId(loginId);
        request.setAiChatConversationDTO(AiChatConversationDTO.builder().id(id).build());
        return request;
    }

    @Test
    void getConversationReturnsTheChatAndItsMessages() {
        AiChatConversationDTO chat = AiChatConversationDTO.builder().id(7L).title("login").build();
        when(historyService.conversation(7L, "kim")).thenReturn(Optional.of(chat));
        when(historyService.messages(7L, "kim")).thenReturn(Optional.of(List.of(new AiChatMessageDTO())));

        ResponseEntity<?> response = controller.getConversation(request(7L, "kim"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AiChatDTOContainer body = (AiChatDTOContainer) response.getBody();
        assertEquals(chat, body.getAiChatConversationDTO());
        assertEquals(1, body.getAiChatMessageDTOList().size());
    }

    @Test
    void anotherUsersChatIsNotFound() {
        when(historyService.conversation(7L, "lee")).thenReturn(Optional.empty());
        when(historyService.rename(7L, "lee", null)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.getConversation(request(7L, "lee")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.renameConversation(request(7L, "lee")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.deleteConversation(request(7L, "lee")).getStatusCode());
    }

    @Test
    void blankRenameIsABadRequest() {
        when(historyService.rename(eq(7L), eq("kim"), any())).thenThrow(new IllegalArgumentException("Title is empty"));

        assertEquals(HttpStatus.BAD_REQUEST, controller.renameConversation(request(7L, "kim")).getStatusCode());
    }

    @Test
    void listUsesTheRequestsAppCodeAndPage() {
        AiChatDTOContainer request = request(null, "kim");
        request.getAiChatConversationDTO().setAppCode("ELCM");
        request.setPageNum(1);
        request.setPageSize(10);
        when(historyService.list("kim", "ELCM", 1, 10)).thenReturn(List.of(new AiChatConversationDTO()));

        ResponseEntity<?> response = controller.listConversations(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, ((AiChatDTOContainer) response.getBody()).getAiChatConversationDTOList().size());
    }

    @Test
    void generateIsRefusedWhileAnswersAreOffForTheCompany() throws Exception {
        AiSearchService searchService = mock(AiSearchService.class);
        AiRagController ragController = new AiRagController();
        ReflectionTestUtils.setField(ragController, "aiSearchService", searchService);
        ReflectionTestUtils.setField(ragController, "companySettings", new CompanySettings(null, "off"));
        AiGenerateDTOContainer request = new AiGenerateDTOContainer();
        request.setAiGenerateDTO(new AiGenerateDTO("q"));

        ResponseEntity<?> response = ragController.generate(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(AiRagController.ANSWERS_OFF, ((AiGenerateDTOContainer) response.getBody()).getResultMessage());
        verifyNoInteractions(searchService);
    }

    @Test
    void generateInAnotherUsersChatIsNotFound() throws Exception {
        AiSearchService searchService = mock(AiSearchService.class);
        AiRagController ragController = new AiRagController();
        ReflectionTestUtils.setField(ragController, "aiSearchService", searchService);
        ReflectionTestUtils.setField(ragController, "companySettings", new CompanySettings(null, null));
        when(searchService.generate(any())).thenThrow(new AiChatHistoryService.ChatNotFoundException(7L));
        AiGenerateDTOContainer request = new AiGenerateDTOContainer();
        request.setAiGenerateDTO(new AiGenerateDTO("q"));

        ResponseEntity<?> response = ragController.generate(request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(((AiGenerateDTOContainer) response.getBody()).getAiGenerateDTO());
    }
}
