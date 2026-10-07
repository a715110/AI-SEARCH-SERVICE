package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.dto.AiAlertDTO;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** EARS alert lookups used by generate. */
class EarsAlertServiceTest {

    private RESTServiceClient restServiceClient;
    private EarsAlertService service;

    @BeforeEach
    void setUp() {
        restServiceClient = mock(RESTServiceClient.class);
        service = new EarsAlertService();
        service.restServiceClient = restServiceClient;
    }

    private static EarsAlertService.AlertPage page(AiAlertDTO... alerts) {
        EarsAlertService.AlertPage page = new EarsAlertService.AlertPage();
        page.setEventAlertReminderDTOList(List.of(alerts));
        return page;
    }

    @Test
    void userAlertsAreLookedUpByLoginId() throws Exception {
        AiAlertDTO alert = AiAlertDTO.builder().id(1).subject("Approval due").build();
        when(restServiceClient.callRESTService(eq("ears_service"),
            eq("/eventReminderController/event-alert-reminder/recipient?loginId=kim@dodaso.com&size="
                + EarsAlertService.MAX_ALERTS),
            isNull(), any(), eq(HttpMethod.GET), isNull(), any()))
            .thenReturn(page(alert));

        assertEquals(List.of(alert), service.findUserAlerts(" kim@dodaso.com "));
    }

    @Test
    void taskAlertsAreLookedUpOncePerTask() throws Exception {
        AiAlertDTO alert = AiAlertDTO.builder().id(2).subject("Task past due").build();
        when(restServiceClient.callRESTService(eq("ears_service"),
            eq("/eventReminderController/event-alert-reminder/sources?sourceReferenceTable=collaboration_task"
                + "&sourceReferenceIds=12,15&size=" + EarsAlertService.MAX_ALERTS),
            isNull(), any(), eq(HttpMethod.GET), isNull(), any()))
            .thenReturn(page(alert));

        assertEquals(List.of(alert), service.findTaskAlerts(Arrays.asList(12L, null, 15L, 12L)));
    }

    @Test
    void pastDueAlertsKeepTheNewestPerTaskOrApproval() throws Exception {
        AiAlertDTO task82New = AiAlertDTO.builder().id(30).sourceReferenceTable("collaboration_task")
            .sourceReferenceId(82).subject("Alert: Task Past Due").build();
        AiAlertDTO task82Old = AiAlertDTO.builder().id(10).sourceReferenceTable("collaboration_task")
            .sourceReferenceId(82).subject("Alert: Task Past Due").build();
        AiAlertDTO approval7 = AiAlertDTO.builder().id(20).sourceReferenceTable("workflow")
            .sourceReferenceId(7).subject("Alert: Approval Past Due").build();
        AiAlertDTO review7 = AiAlertDTO.builder().id(21).sourceReferenceTable("workflow")
            .sourceReferenceId(7).subject("Alert: Review Past Due").build();
        when(restServiceClient.callRESTService(eq("ears_service"),
            eq("/eventReminderController/event-alert-reminder/recipient?loginId=kim"
                + "&unreadPastDueOnly=true&size=" + EarsAlertService.MAX_PAST_DUE_FETCHED),
            isNull(), any(), eq(HttpMethod.GET), isNull(), any()))
            .thenReturn(page(task82New, approval7, task82Old, review7));

        assertEquals(List.of(task82New, approval7, review7), service.findUserPastDueAlerts("kim"));
    }

    @Test
    void nothingToLookUpMakesNoCall() {
        assertTrue(service.findUserPastDueAlerts(null).isEmpty());
        assertTrue(service.findUserAlerts(null).isEmpty());
        assertTrue(service.findUserAlerts("  ").isEmpty());
        assertTrue(service.findTaskAlerts(List.of()).isEmpty());
        assertTrue(service.findTaskAlerts(null).isEmpty());
        verifyNoInteractions(restServiceClient);
    }

    @Test
    void earsFailureMeansNoAlerts() throws Exception {
        when(restServiceClient.callRESTService(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Service 'ears_service' not found in Eureka registry"));

        assertTrue(service.findUserAlerts("kim").isEmpty());
        assertTrue(service.findTaskAlerts(List.of(12L)).isEmpty());
    }

    @Test
    void emptyResponseMeansNoAlerts() throws Exception {
        when(restServiceClient.callRESTService(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenReturn(null);

        assertTrue(service.findUserAlerts("kim").isEmpty());
    }
}
