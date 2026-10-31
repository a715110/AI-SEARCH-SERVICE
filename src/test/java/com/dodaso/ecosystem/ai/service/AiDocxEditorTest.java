package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.dto.AiChatFileDTO;
import com.dodaso.ecosystem.ai.dto.AiGeneratedFileDTO;
import com.dodaso.ecosystem.ai.service.AiAnswerSpec.EditSpec;
import com.dodaso.ecosystem.ai.service.AiAnswerSpec.ReplacementSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

/** Changing wording in a Word file while its formatting stays. */
class AiDocxEditorTest {

    /**
     * A lease-like file: "first three (3) full" split over a plain, a bold and a plain run, the
     * same number in a table cell, a curly-quoted term and a header.
     */
    private static byte[] lease() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Office Lease - Draft");
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("4.2 Abatement. Base Rent shall be abated for the first ");
            XWPFRun bold = p.createRun();
            bold.setBold(true);
            bold.setText("three (3)");
            p.createRun().setText(" full calendar months.");
            doc.createParagraph().createRun().setText("this “Lease” is made");
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("Lease Year 1");
            table.getRow(0).getCell(1).setText("$29,568.75 (Months 1-3 abated)");
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static XWPFDocument open(byte[] bytes) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    @Test
    void replacesAcrossRunsAndKeepsTheFirstRunsFormatting() throws Exception {
        AiDocxEditor.Result result = AiDocxEditor.apply(lease(),
            List.of(new ReplacementSpec("for the first three (3) full", "for the first six (6) full")));

        assertEquals(1, result.total());
        try (XWPFDocument doc = open(result.bytes())) {
            XWPFParagraph p = doc.getParagraphs().get(0);
            assertEquals("4.2 Abatement. Base Rent shall be abated for the first six (6) full calendar months.", p.getText());
            // The other paragraphs and the table are untouched
            assertEquals("Lease Year 1", doc.getTables().get(0).getRow(0).getCell(0).getText());
            assertEquals(3, p.getRuns().size());
        }
    }

    @Test
    void matchInsideOneRunKeepsThatRunsFormatting() throws Exception {
        AiDocxEditor.Result result = AiDocxEditor.apply(lease(),
            List.of(new ReplacementSpec("three (3)", "six (6)")));

        try (XWPFDocument doc = open(result.bytes())) {
            XWPFRun bold = doc.getParagraphs().get(0).getRuns().get(1);
            assertEquals("six (6)", bold.text());
            assertTrue(bold.isBold());
        }
    }

    @Test
    void replacesInTableCellsAndHeaders() throws Exception {
        AiDocxEditor.Result result = AiDocxEditor.apply(lease(), List.of(
            new ReplacementSpec("(Months 1-3 abated)", "(Months 1-6 abated)"),
            new ReplacementSpec("Office Lease - Draft", "Office Lease - Final")));

        assertEquals(2, result.total());
        try (XWPFDocument doc = open(result.bytes())) {
            assertEquals("$29,568.75 (Months 1-6 abated)", doc.getTables().get(0).getRow(0).getCell(1).getText());
            assertEquals("Office Lease - Final", doc.getHeaderList().get(0).getText().trim());
        }
    }

    @Test
    void findCopiedFromAWholeTableRowChangesTheCellThatDiffers() throws Exception {
        AiDocxEditor.Result result = AiDocxEditor.apply(lease(), List.of(new ReplacementSpec(
            "Lease Year 1 | $29,568.75 (Months 1-3 abated)", "Lease Year 1 | $29,568.75 (Months 1-6 abated)")));

        assertEquals(1, result.total());
        try (XWPFDocument doc = open(result.bytes())) {
            assertEquals("Lease Year 1", doc.getTables().get(0).getRow(0).getCell(0).getText());
            assertEquals("$29,568.75 (Months 1-6 abated)", doc.getTables().get(0).getRow(0).getCell(1).getText());
        }
    }

    @Test
    void rowFindWithADifferentCellCountIsUsedAsItIs() {
        assertEquals(List.of(new ReplacementSpec("a | b", "c")), AiDocxEditor.byCell("a | b", "c"));
    }

    @Test
    void straightQuotesMatchCurlyOnes() throws Exception {
        AiDocxEditor.Result result = AiDocxEditor.apply(lease(),
            List.of(new ReplacementSpec("this \"Lease\" is made", "this \"Lease\" is entered into")));

        assertEquals(1, result.total());
        try (XWPFDocument doc = open(result.bytes())) {
            assertEquals("this \"Lease\" is entered into", doc.getParagraphs().get(1).getText());
        }
    }

    @Test
    void everyOccurrenceIsReplacedAndMissingFindsAreReported() throws Exception {
        AiDocxEditor.Result result = AiDocxEditor.apply(lease(), List.of(
            new ReplacementSpec("Lease", "Agreement"),
            new ReplacementSpec("Security Deposit", "Deposit")));

        // Header, the quoted term and the table cell
        assertEquals(3, result.counts().get("Lease"));
        assertEquals(List.of("Security Deposit"), result.notFound());
    }

    @Test
    void replacementContainingTheFindDoesNotLoop() throws Exception {
        AiDocxEditor.Result result = AiDocxEditor.apply(lease(),
            List.of(new ReplacementSpec("calendar months", "calendar months and days")));

        assertEquals(1, result.total());
    }

    // ---- AiSearchService.applyEdit ----

    private static final EditSpec EDIT = new EditSpec("Lease.docx", "Lease-6-months",
        List.of(new ReplacementSpec("three (3)", "six (6)")));

    private static AiSearchService searchService(AiDocumentService documentService) {
        AiSearchService service = new AiSearchService(mock(ChatModel.class));
        ReflectionTestUtils.setField(service, "aiDocumentService", documentService);
        return service;
    }

    @Test
    void applyEditSavesTheEditedAttachedFile() throws Exception {
        AiDocumentService documentService = mock(AiDocumentService.class);
        AiGeneratedFileDTO saved = AiGeneratedFileDTO.builder().id(UUID.randomUUID()).fileName("Lease-6-months.docx").build();
        when(documentService.saveEdited(any(), eq("Lease-6-months"), eq("lease.DOCX"), eq(EDIT), eq("kim")))
            .thenReturn(new AiDocumentService.Created(saved, null));
        List<AiGeneratedFileDTO> generated = new ArrayList<>();

        String note = searchService(documentService).applyEdit(EDIT,
            List.of(new AiChatFileDTO("notes.txt", "x".getBytes()), new AiChatFileDTO("lease.DOCX", lease())),
            "kim", generated);

        assertNull(note);
        assertEquals(List.of(saved), generated);
        verify(documentService).saveEdited(any(), eq("Lease-6-months"), eq("lease.DOCX"), eq(EDIT), eq("kim"));
    }

    @Test
    void applyEditNeedsAnAttachedDocx() throws Exception {
        AiDocumentService documentService = mock(AiDocumentService.class);

        String note = searchService(documentService).applyEdit(EDIT,
            List.of(new AiChatFileDTO("lease.pdf", new byte[] {1})), "kim", new ArrayList<>());

        assertEquals(AiSearchService.EDIT_NO_DOCX, note);
        verifyNoInteractions(documentService);
    }

    @Test
    void applyEditWithNothingFoundMakesNoFile() throws Exception {
        AiDocumentService documentService = mock(AiDocumentService.class);
        EditSpec edit = new EditSpec("Lease.docx", "x", List.of(new ReplacementSpec("not in it", "y")));

        String note = searchService(documentService).applyEdit(edit,
            List.of(new AiChatFileDTO("Lease.docx", lease())), "kim", new ArrayList<>());

        assertEquals(AiSearchService.EDIT_NOTHING_FOUND, note);
        verifyNoInteractions(documentService);
    }

    @Test
    void applyEditListsFindsThatWereNotFound() throws Exception {
        AiDocumentService documentService = mock(AiDocumentService.class);
        when(documentService.saveEdited(any(), any(), any(), any(), any()))
            .thenReturn(new AiDocumentService.Created(AiGeneratedFileDTO.builder().build(), null));
        EditSpec edit = new EditSpec("Lease.docx", "x", List.of(
            new ReplacementSpec("three (3)", "six (6)"), new ReplacementSpec("Security Deposit", "Deposit")));

        String note = searchService(documentService).applyEdit(edit,
            List.of(new AiChatFileDTO("Lease.docx", lease())), "kim", new ArrayList<>());

        assertEquals(AiSearchService.EDIT_NOT_FOUND + "\"Security Deposit\"", note);
    }

    @Test
    void parseAnswerReadsTheEdit() {
        AiRagService.AiAnswer answer = AiRagService.parseAnswer("""
            {"answer": "Changed it.", "file": null, "edit": {"sourceFileName": "Lease.docx",
             "fileName": "Lease-6", "replacements": [{"find": "three (3)", "replace": "six (6)"}]}}""");

        assertNull(answer.file());
        assertEquals("Lease.docx", answer.edit().sourceFileName());
        assertEquals("six (6)", answer.edit().replacements().get(0).replace());
    }
}
