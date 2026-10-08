package com.dodaso.ecosystem.ai.scheduler;

import com.dodaso.ecosystem.ai.service.AiDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes the chat's generated files once they have expired. */
@Component
@Slf4j
public class AiChatFilePurgeJob {

    @Autowired
    private AiDocumentService aiDocumentService;

    @Scheduled(cron = "0 30 0 * * *") // Daily at 00:30
    public void purgeExpiredFiles() {
        try {
            int deleted = aiDocumentService.purgeExpired();
            log.info("Deleted {} expired AI chat file(s)", deleted);
        } catch (Exception e) {
            log.error("Failed to delete expired AI chat files", e);
        }
    }
}
