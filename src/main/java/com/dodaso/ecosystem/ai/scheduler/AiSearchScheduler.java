package com.dodaso.ecosystem.ai.scheduler;

import com.dodaso.ecosystem.ai.service.AiSearchService;
import com.dodaso.ecosystem.ai.service.AiSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AiSearchScheduler {

    @Autowired
    private AiSyncService aiSyncService;

//    @Scheduled(cron = "0 0/15 * * * *") // Every 15 minutes
//    public void processSyncQueue() {
//        log.info("Starting automated AI Search sync queue processing...");
//        aiSearchService.processPendingSyncs();
//    }

    @Scheduled(cron = "0 0 0 * * *") // Daily at midnight
    public void dailyConsistencyCheck() {
        log.info("Starting daily AI Search consistency check...");
        aiSyncService.refreshSync();
    }
}
