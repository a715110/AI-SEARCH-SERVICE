package com.dodaso.ecosystem.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.container.AiIndexDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiIndexDTO;
import com.dodaso.ecosystem.ai.dto.AiScopedSearchDTO;
import com.dodaso.ecosystem.ai.service.AiIndexService;
import com.dodaso.ecosystem.ai.service.AiScopedSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/** Bad scoped requests get 400 with the reason; a good index request gets 202. */
class AiIndexControllerTest {

    @Test
    void invalidIndexRequestIs400AndNothingIsIndexed() {
        AiIndexService indexService = mock(AiIndexService.class);
        doThrow(new IllegalArgumentException("Missing scopeId")).when(indexService).validate(any());
        AiIndexController controller = new AiIndexController();
        ReflectionTestUtils.setField(controller, "aiIndexService", indexService);

        AiIndexDTOContainer body = new AiIndexDTOContainer();
        body.setAiIndexDTO(AiIndexDTO.builder().appCode("ELCM").build());
        ResponseEntity<AiIndexDTOContainer> response = controller.index(body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Missing scopeId", response.getBody().getResultMessage());
        verify(indexService, never()).indexAsync(any());
    }

    @Test
    void validIndexRequestIs202() {
        AiIndexService indexService = mock(AiIndexService.class);
        AiIndexController controller = new AiIndexController();
        ReflectionTestUtils.setField(controller, "aiIndexService", indexService);

        AiIndexDTOContainer body = new AiIndexDTOContainer();
        AiIndexDTO dto = AiIndexDTO.builder().appCode("ELCM").sourceType("elcm_contract_record").sourceId(41L).build();
        body.setAiIndexDTO(dto);
        ResponseEntity<AiIndexDTOContainer> response = controller.index(body);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(indexService).indexAsync(dto);
    }

    @Test
    void scopedSearchWithoutScopesIs400() {
        AiScopedSearchService searchService = mock(AiScopedSearchService.class);
        when(searchService.search(any())).thenThrow(new IllegalArgumentException("scopeIds must be a non-empty list of ids"));
        AiSearchController controller = new AiSearchController();
        ReflectionTestUtils.setField(controller, "aiScopedSearchService", searchService);

        AiIndexDTOContainer body = new AiIndexDTOContainer();
        body.setAiScopedSearchDTO(AiScopedSearchDTO.builder().appCode("ELCM").scopeIds(List.of()).query("q").build());
        ResponseEntity<AiIndexDTOContainer> response = controller.searchScoped(body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("scopeIds must be a non-empty list of ids", response.getBody().getResultMessage());
    }
}
