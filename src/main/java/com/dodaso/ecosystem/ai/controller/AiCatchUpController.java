package com.dodaso.ecosystem.ai.controller;

import com.dodaso.ecosystem.ai.container.AiCatchUpDTOContainer;
import com.dodaso.ecosystem.ai.service.AiCatchUpService;
import com.dodaso.ecosystem.baseline.common.config.CompanySettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADDED 2026-11-23 (task catch-up). Called by ecws-service, not by the browser. */
@Slf4j
@RestController
@RequestMapping("/aiCatchUpController")
public class AiCatchUpController {

  static final String ANSWERS_OFF = "AI answers are turned off for your company";

  @Autowired
  private AiCatchUpService aiCatchUpService;

  @Autowired
  private CompanySettings companySettings;

  @PostMapping(value = "/catchUp", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AiCatchUpDTOContainer> catchUp(@RequestBody AiCatchUpDTOContainer requestBody) {
    AiCatchUpDTOContainer returnValue = new AiCatchUpDTOContainer();
    if (!companySettings.inAppAnswers(CompanySettings.DEFAULT_COMPANY_ID)) {
      returnValue.setResultMessage(ANSWERS_OFF);
      return new ResponseEntity<>(returnValue, HttpStatus.FORBIDDEN);
    }
    if (requestBody == null || requestBody.getAiCatchUpDTO() == null) {
      returnValue.setResultMessage("Nothing to summarize");
      return new ResponseEntity<>(returnValue, HttpStatus.BAD_REQUEST);
    }
    try {
      returnValue.setAiCatchUpDTO(aiCatchUpService.catchUp(requestBody.getAiCatchUpDTO()));
    } catch (Exception e) {
      log.error("Catch-up failed", e);
      returnValue.setResultMessage("The AI model could not be reached");
      return new ResponseEntity<>(returnValue, HttpStatus.BAD_GATEWAY);
    }
    return new ResponseEntity<>(returnValue, HttpStatus.OK);
  }

  /** ADDED 2026-11-27 (draft reply). A suggested reply to the comments sent; nothing is posted. */
  @PostMapping(value = "/draftReply", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AiCatchUpDTOContainer> draftReply(
      @RequestBody AiCatchUpDTOContainer requestBody) {
    AiCatchUpDTOContainer returnValue = new AiCatchUpDTOContainer();
    if (!companySettings.inAppAnswers(CompanySettings.DEFAULT_COMPANY_ID)) {
      returnValue.setResultMessage(ANSWERS_OFF);
      return new ResponseEntity<>(returnValue, HttpStatus.FORBIDDEN);
    }
    if (requestBody == null || requestBody.getAiCatchUpDTO() == null) {
      returnValue.setResultMessage("Nothing to reply to");
      return new ResponseEntity<>(returnValue, HttpStatus.BAD_REQUEST);
    }
    try {
      returnValue.setAiCatchUpDTO(aiCatchUpService.draftReply(requestBody.getAiCatchUpDTO()));
    } catch (Exception e) {
      log.error("Draft reply failed", e);
      returnValue.setResultMessage("The AI model could not be reached");
      return new ResponseEntity<>(returnValue, HttpStatus.BAD_GATEWAY);
    }
    return new ResponseEntity<>(returnValue, HttpStatus.OK);
  }
}
