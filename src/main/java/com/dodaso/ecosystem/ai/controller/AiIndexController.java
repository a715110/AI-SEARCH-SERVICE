package com.dodaso.ecosystem.ai.controller;

import com.dodaso.ecosystem.ai.container.AiIndexDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiIndexDTO;
import com.dodaso.ecosystem.ai.service.AiIndexService;
import com.dodaso.ecosystem.baseline.common.controller.RESTServiceController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Index and remove records of apps other than ECWS (ELCM), called by their app service. */
@RestController
@RequestMapping("/aiIndexController")
@Slf4j
public class AiIndexController extends RESTServiceController<AiIndexDTOContainer> {

    @Autowired
    private AiIndexService aiIndexService;

    /** Checks the request, then indexes in the background: 202, or 400 naming what is missing. */
    @PostMapping(value = "/index", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AiIndexDTOContainer> index(@RequestBody AiIndexDTOContainer requestBody) {
        AiIndexDTO dto = requestBody.getAiIndexDTO();
        AiIndexDTOContainer response = new AiIndexDTOContainer();
        try {
            aiIndexService.validate(dto);
        } catch (IllegalArgumentException e) {
            response.setResultMessage(e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }
        aiIndexService.indexAsync(dto);
        response.setResultMessage("Indexing started for " + dto.getSourceType() + " " + dto.getSourceId());
        return new ResponseEntity<>(response, HttpStatus.ACCEPTED);
    }

    @PostMapping(value = "/delete", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AiIndexDTOContainer> delete(@RequestBody AiIndexDTOContainer requestBody) {
        AiIndexDTOContainer response = new AiIndexDTOContainer();
        try {
            long deleted = aiIndexService.delete(requestBody.getAiIndexDeleteDTO());
            response.setResultMessage("Deleted " + deleted + " embedding row(s)");
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            response.setResultMessage(e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
        }
    }
}
