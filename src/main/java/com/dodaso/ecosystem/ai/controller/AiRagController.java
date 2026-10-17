package com.dodaso.ecosystem.ai.controller;

import com.dodaso.ecosystem.ai.container.AiGenerateDTOContainer;
import com.dodaso.ecosystem.ai.container.AiSearchSyncDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
import com.dodaso.ecosystem.ai.service.AiChatHistoryService;
import com.dodaso.ecosystem.ai.service.AiRagService;
import com.dodaso.ecosystem.ai.service.AiSearchService;
import com.dodaso.ecosystem.baseline.common.controller.RESTServiceController;
import com.dodaso.ecosystem.baseline.common.security.CallerLogin;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/aiRagController")
@Slf4j
public class AiRagController extends RESTServiceController<AiSearchSyncDTOContainer> {

    @Autowired
    private AiRagService aiRagService;

    @Autowired
    private AiSearchService aiSearchService;

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(@RequestBody AiGenerateDTOContainer requestBody) throws Exception {
        // AiGenerateDTOContainer returnValue = aiRagService.generate(requestBody);
        AiGenerateDTOContainer returnValue = new AiGenerateDTOContainer();
        // returnValue.setAiGenerateDTO(aiSearchService.generate(requestBody.getAiGenerateDTO()));
        if (requestBody.getAiGenerateDTO() != null) {
            // The chat owner and alert recipient: the access token's login when one is sent
            requestBody.getAiGenerateDTO().setLoginId(
                CallerLogin.actingFor(requestBody.getAiGenerateDTO().getLoginId(), "AI generate"));
        }
        try {
            returnValue.setAiGenerateDTO(aiSearchService.generate(requestBody.getAiGenerateDTO()));
        } catch (AiChatHistoryService.ChatNotFoundException e) {
            // Another user's, deleted or unknown chat
            returnValue.setResultMessage("Chat not found");
            return new ResponseEntity<>(returnValue, HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(returnValue, HttpStatus.OK);
    }

}