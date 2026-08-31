package com.dodaso.ecosystem.ai.controller;

import com.dodaso.ecosystem.ai.container.AiGenerateDTOContainer;
import com.dodaso.ecosystem.ai.container.AiSearchSyncDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
import com.dodaso.ecosystem.ai.service.AiRagService;
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
@RequestMapping("/aiRagController")
@Slf4j
public class AiRagController extends RESTServiceController<AiSearchSyncDTOContainer> {

    @Autowired
    private AiRagService aiRagService;

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(@RequestBody AiGenerateDTOContainer requestBody) throws Exception {
        AiGenerateDTOContainer returnValue = aiRagService.generate(requestBody);
        return new ResponseEntity<>(returnValue, HttpStatus.OK);
    }

}