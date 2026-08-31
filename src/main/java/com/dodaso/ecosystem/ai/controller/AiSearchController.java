package com.dodaso.ecosystem.ai.controller;

import com.dodaso.ecosystem.ai.container.AiGenerateDTOContainer;
import com.dodaso.ecosystem.ai.container.AiSearchSyncDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
import com.dodaso.ecosystem.ai.service.AiSearchService;
import com.dodaso.ecosystem.baseline.common.controller.RESTServiceController;
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
@RequestMapping("/aiSearchController")
@Slf4j
public class AiSearchController extends RESTServiceController<AiSearchSyncDTOContainer> {

    @Autowired
    private AiSearchService aiSearchService;

//    @PostMapping(value = "/sync", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
//    public ResponseEntity<?> sync(@RequestBody AiSearchSyncDTOContainer requestBody) throws Exception {
//        AiSearchSyncDTO syncDto = requestBody.getAiSearchSyncDTO();
//        AiSearchSyncDTOContainer responseContainer = new AiSearchSyncDTOContainer();
//        aiSearchService.syncAsync(syncDto);
//        responseContainer.setResultMessage("Async sync triggered for task ID: " + syncDto.getSourceId());
//        return new ResponseEntity<>(responseContainer, HttpStatus.OK);
//    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@RequestBody AiSearchSyncDTOContainer requestBody) throws Exception {
        AiSearchSyncDTO searchRequest = requestBody.getAiSearchSyncDTO();
        AiSearchSyncDTOContainer responseContainer = new AiSearchSyncDTOContainer();
        List<AiSearchResultDTO> results = aiSearchService.search(
            searchRequest.getProjectId(),
            searchRequest.getWorkspaceId(),
            searchRequest.getSummary());
        responseContainer.setAiSearchResultDTOList(results);
        return new ResponseEntity<>(responseContainer, HttpStatus.OK);
    }

//     @PostMapping(value = "/refreshSync", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
//    public ResponseEntity<?> refreshSync(@RequestBody AiSearchSyncDTOContainer requestBody) {
//        log.info("Manual refresh sync triggered");
//        AiSearchSyncDTOContainer responseContainer = new AiSearchSyncDTOContainer();
//        try {
//            aiSearchService.refreshSync();
//            responseContainer.setResultMessage("Refresh sync completed");
//            return new ResponseEntity<>(responseContainer, HttpStatus.OK);
//        } catch (Exception e) {
//            log.error("Refresh sync failed", e);
//            responseContainer.setResultMessage("Refresh sync failed: " + e.getMessage());
//            return new ResponseEntity<>(responseContainer, HttpStatus.SERVICE_UNAVAILABLE);
//        }
//    }
//
//    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
//    public ResponseEntity<?> generate(@RequestBody AiGenerateDTOContainer requestBody) throws Exception {
//        AiGenerateDTO generateDTO = requestBody.getAiGenerateDTO();
//        AiGenerateDTOContainer responseContainer = new AiGenerateDTOContainer();
//        AiGenerateDTO result = aiSearchService.generate(generateDTO);
//        responseContainer.setAiGenerateDTO(result);
//        return new ResponseEntity<>(responseContainer, HttpStatus.OK);
//    }

}