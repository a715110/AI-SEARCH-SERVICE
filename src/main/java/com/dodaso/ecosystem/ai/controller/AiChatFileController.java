package com.dodaso.ecosystem.ai.controller;

import com.dodaso.ecosystem.ai.container.AiGeneratedFileDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiGeneratedFileDTO;
import com.dodaso.ecosystem.ai.service.AiDocumentService;
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

/** Downloads of the files the AI Search chat made (Word, Excel). */
@RestController
@RequestMapping("/aiChatFileController")
@Slf4j
public class AiChatFileController {

    @Autowired
    private AiDocumentService aiDocumentService;

    /**
     * The file with its bytes (base64 in JSON). 404 when the id is unknown, the file belongs to
     * another user, or it has expired.
     */
    @PostMapping(value = "/download", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> download(@RequestBody AiGeneratedFileDTOContainer requestBody) {
        AiGeneratedFileDTOContainer returnValue = new AiGeneratedFileDTOContainer();
        AiGeneratedFileDTO requested = requestBody != null ? requestBody.getAiGeneratedFileDTO() : null;
        Optional<AiGeneratedFileDTO> file = requested == null ? Optional.empty()
            : aiDocumentService.download(requested.getId(), requestBody.getLoginId());
        if (file.isEmpty()) {
            returnValue.setResultMessage("File not found or expired");
            return new ResponseEntity<>(returnValue, HttpStatus.NOT_FOUND);
        }
        returnValue.setAiGeneratedFileDTO(file.get());
        return new ResponseEntity<>(returnValue, HttpStatus.OK);
    }
}
