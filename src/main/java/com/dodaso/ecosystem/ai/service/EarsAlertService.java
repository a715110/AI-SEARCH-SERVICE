package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.dto.AiAlertDTO;
import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

/**
 * Reads alerts and reminders from EARS over REST so an answer can use them. They are looked up
 * when a question is asked rather than embedded: they are short, change often and are already
 * linked to the tasks that are embedded.
 *
 * <p>A failed call only means the answer has no alerts; it never fails the answer.
 */
@Service
@Slf4j
public class EarsAlertService {

    private static final String RECIPIENT_ENDPOINT =
        "/eventReminderController/event-alert-reminder/recipient";
    private static final String SOURCES_ENDPOINT =
        "/eventReminderController/event-alert-reminder/sources";

    // source_reference_table EARS stores on task alerts
    static final String TASK_TABLE = "collaboration_task";

    // Newest alerts asked for per lookup, so a long alert history can't fill the prompt
    static final int MAX_ALERTS = 20;

    // A slow EARS delays the answer by at most this, instead of the client's 1-minute default
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    RESTServiceClient restServiceClient;

    /** The user's newest active alerts and reminders, across all projects. */
    public List<AiAlertDTO> findUserAlerts(String loginId) {
        if (loginId == null || loginId.trim().isEmpty()) {
            return new ArrayList<>();
        }
        // Not URL-encoded here: RestTemplate encodes the URL, so encoding it first would send
        // "%40" as "%2540".
        return fetch(RECIPIENT_ENDPOINT + "?loginId=" + loginId.trim() + "&size=" + MAX_ALERTS,
            "user " + loginId);
    }

    /** The newest active alerts and reminders about these tasks. */
    public List<AiAlertDTO> findTaskAlerts(Collection<Long> taskIds) {
        List<Long> ids = taskIds == null ? List.of() : taskIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        String idList = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return fetch(SOURCES_ENDPOINT + "?sourceReferenceTable=" + TASK_TABLE
            + "&sourceReferenceIds=" + idList + "&size=" + MAX_ALERTS, "tasks " + idList);
    }

    private List<AiAlertDTO> fetch(String endpoint, String description) {
        try {
            AlertPage page = restServiceClient.callRESTService(
                ServiceDiscoveryEnum.ears_service.getServiceDiscoveryName(),
                endpoint,
                null,
                new ParameterizedTypeReference<AlertPage>() {},
                HttpMethod.GET,
                null,
                RESPONSE_TIMEOUT);
            List<AiAlertDTO> alerts = page != null && page.getEventAlertReminderDTOList() != null
                ? page.getEventAlertReminderDTOList() : new ArrayList<>();
            log.info("[EARS] {} alert(s) for {}", alerts.size(), description);
            return alerts;
        } catch (Exception e) {
            log.warn("[EARS] Alerts for {} not loaded, answering without them: {}",
                description, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * The part of the EARS EventAlertReminderDTOContainer response used here. That class can't be
     * read back from JSON (it has only a builder), and ai-search doesn't depend on EARS.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AlertPage {
        private List<AiAlertDTO> eventAlertReminderDTOList;
    }
}
