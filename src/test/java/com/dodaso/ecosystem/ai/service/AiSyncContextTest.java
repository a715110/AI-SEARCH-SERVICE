package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiSearchSyncDTO;
import java.util.Date;
import java.util.GregorianCalendar;
import org.junit.jupiter.api.Test;

/** Embedded text leaves out empty labels (label pollution fix). */
class AiSyncContextTest {

    private final AiSyncService service = new AiSyncService();

    /** Midnight UTC, which is what Jackson makes of an ecws "yyyy-MM-dd" date. */
    private static Date date(int y, int m, int d) {
        GregorianCalendar c = new GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, m - 1, d);
        return c.getTime();
    }

    @Test
    void utcMidnightDateKeepsItsDayInAnyJvmZone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            for (String zone : new String[] {"America/Los_Angeles", "Asia/Seoul", "UTC"}) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
                assertEquals("Summary: x | Due: 2025-07-24", service.buildContext(AiSearchSyncDTO.builder()
                    .sourceType(EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType())
                    .summary("x").dueDate(date(2025, 7, 24)).build()), zone);
            }
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    void taskWithOnlySummaryHasNoOtherLabels() {
        String text = service.buildContext(AiSearchSyncDTO.builder()
            .sourceType(EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType())
            .summary("Fix login page").status("").priority("  ").build());

        assertEquals("Summary: Fix login page", text);
        assertFalse(text.contains("Scheduled"));
        assertFalse(text.contains("null"));
    }

    @Test
    void taskWritesFilledFieldsInOrderWithIsoDates() {
        String text = service.buildContext(AiSearchSyncDTO.builder()
            .sourceType(EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType())
            .summary("Quarterly report").status("OPEN").priority("HIGH").requestor("kim")
            .dueDate(date(2026, 9, 30))
            .scheduledDate(new java.sql.Date(date(2026, 9, 28).getTime()))
            .description("Prepare slides").build());

        assertEquals("Summary: Quarterly report | Status: OPEN | Priority: HIGH | Requestor: kim"
            + " | Due: 2026-09-30 | Scheduled: 2026-09-28 | Description: Prepare slides", text);
    }

    @Test
    void scheduledLabelOnlyWhenThereIsADate() {
        AiSearchSyncDTO.AiSearchSyncDTOBuilder b = AiSearchSyncDTO.builder()
            .sourceType(EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType()).summary("x");
        assertFalse(service.buildContext(b.build()).contains("Scheduled"));
        assertEquals("Summary: x | Scheduled: 2026-01-05",
            service.buildContext(b.scheduledDate(date(2026, 1, 5)).build()));
    }

    @Test
    void commentWithoutAuthorHasNoAuthorLabel() {
        assertEquals("Comment: looks good", service.buildContext(AiSearchSyncDTO.builder()
            .sourceType(EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType())
            .description("looks good").build()));
        assertEquals("Comment: looks good | Author: lee", service.buildContext(AiSearchSyncDTO.builder()
            .sourceType(EmbeddingSourceTypeEnum.TASK_COMMENT.getSourceType())
            .description("looks good").requestor("lee").build()));
    }

    @Test
    void fileKeepsNameAndContent() {
        assertEquals("File: a.txt [chunk 1/1] | Content: hello", service.buildContext(AiSearchSyncDTO.builder()
            .sourceType(EmbeddingSourceTypeEnum.TASK_FILE_ATTACHMENT.getSourceType())
            .summary("a.txt [chunk 1/1]").description("hello").build()));
    }
}
