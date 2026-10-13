package com.dodaso.ecosystem.ai.controller;

import com.dodaso.ecosystem.ai.container.AiChatDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiChatConversationDTO;
import com.dodaso.ecosystem.ai.dto.AiChatMessageDTO;
import com.dodaso.ecosystem.ai.service.AiChatHistoryService;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved AI Search chats: list, open, rename and delete. Every call is for the request's loginId;
 * a chat that is another user's, deleted or unknown gets 404.
 */
@RestController
@RequestMapping("/aiChatController")
@Slf4j
public class AiChatController {

    static final String NOT_FOUND = "Chat not found";

    @Autowired
    private AiChatHistoryService aiChatHistoryService;

    /** The user's open chats in one app (aiChatConversationDTO.appCode, default ECWS), newest first. */
    @PostMapping(value = "/listConversations", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listConversations(@RequestBody AiChatDTOContainer requestBody) {
        AiChatDTOContainer returnValue = new AiChatDTOContainer();
        String appCode = requestBody.getAiChatConversationDTO() != null
            ? requestBody.getAiChatConversationDTO().getAppCode() : null;
        try {
            returnValue.setAiChatConversationDTOList(aiChatHistoryService.list(requestBody.getLoginId(), appCode,
                requestBody.getPageNum(), requestBody.getPageSize()));
        } catch (IllegalArgumentException e) {
            returnValue.setResultMessage(e.getMessage());
            return new ResponseEntity<>(returnValue, HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(returnValue, HttpStatus.OK);
    }

    /** The chat and all its messages in order. */
    @PostMapping(value = "/getConversation", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getConversation(@RequestBody AiChatDTOContainer requestBody) {
        AiChatDTOContainer returnValue = new AiChatDTOContainer();
        Long id = conversationId(requestBody);
        Optional<AiChatConversationDTO> conversation = aiChatHistoryService.conversation(id, requestBody.getLoginId());
        Optional<List<AiChatMessageDTO>> messages = conversation.isEmpty() ? Optional.empty()
            : aiChatHistoryService.messages(id, requestBody.getLoginId());
        if (messages.isEmpty()) {
            returnValue.setResultMessage(NOT_FOUND);
            return new ResponseEntity<>(returnValue, HttpStatus.NOT_FOUND);
        }
        returnValue.setAiChatConversationDTO(conversation.get());
        returnValue.setAiChatMessageDTOList(messages.get());
        return new ResponseEntity<>(returnValue, HttpStatus.OK);
    }

    /** Renames the chat to aiChatConversationDTO.title. */
    @PostMapping(value = "/renameConversation", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> renameConversation(@RequestBody AiChatDTOContainer requestBody) {
        AiChatDTOContainer returnValue = new AiChatDTOContainer();
        String title = requestBody.getAiChatConversationDTO() != null
            ? requestBody.getAiChatConversationDTO().getTitle() : null;
        Optional<AiChatConversationDTO> renamed;
        try {
            renamed = aiChatHistoryService.rename(conversationId(requestBody), requestBody.getLoginId(), title);
        } catch (IllegalArgumentException e) {
            returnValue.setResultMessage(e.getMessage());
            return new ResponseEntity<>(returnValue, HttpStatus.BAD_REQUEST);
        }
        if (renamed.isEmpty()) {
            returnValue.setResultMessage(NOT_FOUND);
            return new ResponseEntity<>(returnValue, HttpStatus.NOT_FOUND);
        }
        returnValue.setAiChatConversationDTO(renamed.get());
        return new ResponseEntity<>(returnValue, HttpStatus.OK);
    }

    /** Deletes the chat (soft delete); it no longer shows in the list or opens. */
    @PostMapping(value = "/deleteConversation", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deleteConversation(@RequestBody AiChatDTOContainer requestBody) {
        AiChatDTOContainer returnValue = new AiChatDTOContainer();
        if (!aiChatHistoryService.delete(conversationId(requestBody), requestBody.getLoginId())) {
            returnValue.setResultMessage(NOT_FOUND);
            return new ResponseEntity<>(returnValue, HttpStatus.NOT_FOUND);
        }
        returnValue.setResultMessage("Chat deleted");
        return new ResponseEntity<>(returnValue, HttpStatus.OK);
    }

    private static Long conversationId(AiChatDTOContainer requestBody) {
        return requestBody.getAiChatConversationDTO() != null ? requestBody.getAiChatConversationDTO().getId() : null;
    }
}
