package com.dodaso.ecosystem.ai.controller;

import com.dodaso.ecosystem.ai.service.AiFileSyncService;
import com.dodaso.ecosystem.baseline.common.controller.RESTServiceController;
import com.dodaso.ecosystem.ecws.container.FileAttachmentDTOContainer;
import com.dodaso.ecosystem.ecws.dto.FileAttachmentDTO;
import java.io.ByteArrayInputStream;
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
@RequestMapping("/aiFileSyncController")
@Slf4j
public class AiFileSyncController extends RESTServiceController<FileAttachmentDTOContainer> {

    @Autowired
    private AiFileSyncService aiFileSyncService;

    @PostMapping(value = "/syncFile", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FileAttachmentDTOContainer> syncFile(@RequestBody FileAttachmentDTOContainer requestBody) {
        FileAttachmentDTO attachment = requestBody.getFileAttachmentDTO();
        FileAttachmentDTOContainer response = new FileAttachmentDTOContainer();

        if (attachment == null || attachment.getFileBytes() == null) {
            response.setResultMessage("Skipped: no file content provided");
            return new ResponseEntity<>(response, HttpStatus.OK);
        }

        aiFileSyncService.syncFileAsync(attachment, new ByteArrayInputStream(attachment.getFileBytes()));
        log.info("File sync triggered for: {}", attachment.getFileName());
        response.setResultMessage("File sync triggered for: " + attachment.getFileName());
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}