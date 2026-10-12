package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.dto.AiGeneratedFileDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.AiChatFile;
import com.dodaso.ecosystem.ai.repository.AiChatFileRepository;
import com.dodaso.ecosystem.ai.service.AiAnswerSpec.BlockSpec;
import com.dodaso.ecosystem.ai.service.AiAnswerSpec.FileSpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Word and Excel files built from an answer's document spec, saved and downloaded by their owner. */
class AiDocumentServiceTest {

    private static final String TASK = EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType();
    // The Korean for "meeting summary", built from its code points so the source stays English;
    // the tests use it to check Hangul survives in every format
    private static final String KOREAN = new String(new int[] {0xD68C, 0xC758, 0xB85D, 0x20, 0xC694, 0xC57D}, 0, 6);

    private AiChatFileRepository repository;
    private AiDocumentService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiChatFileRepository.class);
        service = new AiDocumentService();
        ReflectionTestUtils.setField(service, "chatFileRepository", repository);
    }

    private static BlockSpec heading(int level, String text) {
        return new BlockSpec("heading", level, text, null, null, null, null);
    }

    private static BlockSpec paragraph(String text) {
        return new BlockSpec("paragraph", null, text, null, null, null, null);
    }

    private static BlockSpec bullets(String... items) {
        return new BlockSpec("bullets", null, null, List.of(items), null, null, null);
    }

    private static BlockSpec table(String name, List<String> columns, List<List<String>> rows) {
        return new BlockSpec("table", null, null, null, name, columns, rows);
    }

    private static AiSearchResultDTO source(long taskId) {
        return AiSearchResultDTO.builder().sourceType(TASK).sourceId(taskId).taskId(taskId).build();
    }

    @Test
    void docxHasTitleHeadingsBulletsAndTable() throws Exception {
        FileSpec spec = new FileSpec("DOCX", "Meeting summary", "October " + KOREAN, List.of(
            heading(1, "Decisions"),
            paragraph("Fix the login bug first."),
            bullets("Safari", "Chrome"),
            table("Action items", List.of("Task", "Owner"), List.of(List.of("Login bug", "kim")))));

        byte[] bytes = AiDocumentService.buildDocx(spec);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<XWPFParagraph> paragraphs = doc.getParagraphs();
            assertEquals("October " + KOREAN, paragraphs.get(0).getText());
            assertEquals("Title", paragraphs.get(0).getStyle());
            assertEquals("Decisions", paragraphs.get(1).getText());
            assertEquals("Heading1", paragraphs.get(1).getStyle());
            assertTrue(doc.getStyles().styleExist("Heading1"));
            assertEquals("Fix the login bug first.", paragraphs.get(2).getText());
            assertEquals("•\tSafari", paragraphs.get(3).getText());
            XWPFTable table = doc.getTables().get(0);
            assertEquals(2, table.getNumberOfRows());
            assertEquals("Owner", table.getRow(0).getCell(1).getText());
            assertTrue(table.getRow(0).getCell(0).getParagraphs().get(0).getRuns().get(0).isBold());
            assertEquals("kim", table.getRow(1).getCell(1).getText());
        }
    }

    @Test
    void xlsxHasOneSheetPerTableAndNotesLast() throws Exception {
        FileSpec spec = new FileSpec("XLSX", "tasks", "Overdue", List.of(
            paragraph("Tasks past due"),
            table("Overdue tasks", List.of("Task", "Hours", "Due", "Note"), List.of(
                List.of("Login bug", "3.5", "2026-10-10", "=SUM(A1)"),
                List.of(KOREAN, "-2", "2026-10-11", "+1"))),
            table("Overdue tasks", List.of("Owner"), List.of(List.of("kim")))));

        byte[] bytes = AiDocumentService.buildXlsx(spec);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals("Overdue tasks", workbook.getSheetName(0));
            assertEquals("Overdue tasks (2)", workbook.getSheetName(1));
            assertEquals("Notes", workbook.getSheetName(2));
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals("Task", sheet.getRow(0).getCell(0).getStringCellValue());
            assertTrue(workbook.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold());
            assertNotNull(sheet.getPaneInformation());
            assertEquals(KOREAN, sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals(CellType.NUMERIC, sheet.getRow(1).getCell(1).getCellType());
            assertEquals(-2.0, sheet.getRow(2).getCell(1).getNumericCellValue());
            assertTrue(DateUtil.isCellDateFormatted(sheet.getRow(1).getCell(2)));
            // never a formula
            assertEquals(CellType.STRING, sheet.getRow(1).getCell(3).getCellType());
            assertEquals("=SUM(A1)", sheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals("+1", sheet.getRow(2).getCell(3).getStringCellValue());
            Sheet notes = workbook.getSheetAt(2);
            assertEquals("Overdue", notes.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Tasks past due", notes.getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void pdfHasKoreanTextHeadingsBulletsAndTable() throws Exception {
        FileSpec spec = new FileSpec("PDF", "Meeting summary", "October " + KOREAN, List.of(
            heading(1, "Decisions"),
            paragraph("Fix the login bug first.\nSecond line " + KOREAN),
            bullets("Safari", "Chrome"),
            table("Action items", List.of("Task", "Owner"), List.of(List.of("Login bug", "kim " + KOREAN)))));

        byte[] bytes = AiDocumentService.buildPdf(spec);

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            assertEquals(1, doc.getNumberOfPages());
            assertEquals("October " + KOREAN, doc.getDocumentInformation().getTitle());
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("October " + KOREAN), text);
            assertTrue(text.contains("Decisions"), text);
            assertTrue(text.contains("Fix the login bug first."), text);
            assertTrue(text.contains("Second line " + KOREAN), text);
            assertTrue(text.contains("Safari"), text);
            assertTrue(text.contains("Action items"), text);
            assertTrue(text.contains("kim " + KOREAN), text);
            assertTrue(text.contains("1 / 1"), text);
            // portrait A4
            PDPage page = doc.getPage(0);
            assertTrue(page.getMediaBox().getHeight() > page.getMediaBox().getWidth());
        }
    }

    @Test
    void pdfWrapsLongTextAndRepeatsTheTableHeaderOnEveryPage() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            rows.add(List.of(KOREAN + " " + i, "kim"));
        }
        String longText = (KOREAN + " long sentence ").repeat(300) + "averyveryverylongwordwithoutanyspaces".repeat(10);
        FileSpec spec = new FileSpec("PDF", "x", null, List.of(
            paragraph(longText),
            table("Tasks", List.of("Task", "Owner"), rows)));

        try (PDDocument doc = Loader.loadPDF(AiDocumentService.buildPdf(spec))) {
            int pages = doc.getNumberOfPages();
            assertTrue(pages >= 3, "pages: " + pages);
            PDFTextStripper stripper = new PDFTextStripper();
            // From the page the table starts on, every page has its header
            int tablePages = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                assertTrue(text.contains(page + " / " + pages), text);
                if (tablePages > 0 || text.contains("Owner")) {
                    assertTrue(text.contains("Owner"), "header on page " + page);
                    tablePages++;
                }
            }
            assertTrue(tablePages >= 2, "table pages: " + tablePages);
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            String all = stripper.getText(doc);
            assertTrue(all.contains(KOREAN + " 0") && all.contains(KOREAN + " 199"), all);
        }
    }

    @Test
    void pdfDrawsCharactersTheFontLacksAsQuestionMarksAndWideTablesLandscape() throws Exception {
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < AiPdfBuilder.LANDSCAPE_COLUMNS + 1; i++) {
            columns.add("c" + i);
        }
        FileSpec spec = new FileSpec("PDF", "x", null, List.of(
            paragraph("Done 😀\ttab\u0001"),
            table(null, columns, List.of(List.of("a")))));

        try (PDDocument doc = Loader.loadPDF(AiDocumentService.buildPdf(spec))) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Done ?"), text);
            assertTrue(text.contains("tab"), text);
            PDPage page = doc.getPage(0);
            assertTrue(page.getMediaBox().getWidth() > page.getMediaBox().getHeight());
        }
    }

    @Test
    void createSavesAPdf() throws Exception {
        AiDocumentService.Created created = service.create(
            new FileSpec("pdf", KOREAN + ".pdf", "Report", List.of(paragraph("Content"))), "kim", List.of());

        ArgumentCaptor<AiChatFile> saved = ArgumentCaptor.forClass(AiChatFile.class);
        verify(repository).save(saved.capture());
        assertEquals(KOREAN + ".pdf", saved.getValue().getFileName());
        assertEquals(AiDocumentService.PDF_TYPE, saved.getValue().getContentType());
        assertEquals("%PDF", new String(saved.getValue().getContent(), 0, 4));
        assertEquals(KOREAN + ".pdf", created.file().getFileName());
    }

    @Test
    void csvHasTheFirstTableWithABomQuotingAndNoFormulas() throws Exception {
        FileSpec spec = new FileSpec("CSV", "tasks", "Overdue", List.of(
            paragraph("ignored"),
            table("Overdue", List.of("Task", "Hours", "Note"), List.of(
                List.of(KOREAN, "-2", "=SUM(A1)"),
                List.of("a, b", "3.5", "say \"hi\"\nnext line"))),
            table("Second", List.of("X"), List.of(List.of("never")))));

        byte[] bytes = AiDocumentService.buildCsv(spec);

        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Arrays.copyOf(bytes, 3));
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertEquals("Task,Hours,Note\r\n"
            + KOREAN + ",-2,'=SUM(A1)\r\n"
            + "\"a, b\",3.5,\"say \"\"hi\"\"\nnext line\"\r\n", csv);
    }

    @Test
    void csvFieldsStartingWithFormulaCharactersBecomeText() {
        assertEquals("'+1 more", AiDocumentService.csvField("+1 more"));
        assertEquals("'@cmd", AiDocumentService.csvField("@cmd"));
        assertEquals("'-x", AiDocumentService.csvField("-x"));
        assertEquals("-12.5", AiDocumentService.csvField("-12.5"));
        assertEquals("", AiDocumentService.csvField(null));
    }

    @Test
    void createSavesACsvAndSaysOnlyTheFirstTableIsInIt() throws Exception {
        AiDocumentService.Created created = service.create(new FileSpec("csv", "List.CSV", null, List.of(
            table("A", List.of("x"), List.of(List.of("1"))),
            table("B", List.of("y"), List.of(List.of("2"))))), "kim", List.of());

        ArgumentCaptor<AiChatFile> saved = ArgumentCaptor.forClass(AiChatFile.class);
        verify(repository).save(saved.capture());
        assertEquals("List.csv", saved.getValue().getFileName());
        assertEquals(AiDocumentService.CSV_TYPE, saved.getValue().getContentType());
        assertEquals(AiDocumentService.CSV_FIRST_TABLE_NOTE, created.note());
    }

    @Test
    void csvWithoutATableMakesNoFile() throws Exception {
        AiDocumentService.Created created = service.create(
            new FileSpec("CSV", "x", null, List.of(paragraph("no table"))), "kim", List.of());

        assertNull(created.file());
        assertEquals(AiDocumentService.NO_TABLE_CSV_NOTE, created.note());
        verifyNoInteractions(repository);
    }

    @Test
    void xlsxWithOnlyATitleHasNoNotesSheet() throws Exception {
        FileSpec spec = new FileSpec("XLSX", "t", "Title only",
            List.of(table(null, List.of("A"), List.of(List.of("1")))));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(AiDocumentService.buildXlsx(spec)))) {
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals("Table 1", workbook.getSheetName(0));
        }
    }

    @Test
    void xlsxWithoutATableMakesNoFile() throws Exception {
        AiDocumentService.Created created = service.create(
            new FileSpec("XLSX", "x", null, List.of(paragraph("no table"))), "kim", List.of());

        assertNull(created.file());
        assertEquals(AiDocumentService.NO_TABLE_NOTE, created.note());
        verifyNoInteractions(repository);
    }

    @Test
    void unsupportedFormatMakesNoFile() throws Exception {
        AiDocumentService.Created created = service.create(
            new FileSpec("PPTX", "x", null, List.of(paragraph("text"))), "kim", List.of());

        assertNull(created.file());
        assertEquals(AiDocumentService.UNSUPPORTED_NOTE, created.note());
        verifyNoInteractions(repository);
    }

    @Test
    void createSavesTheFileForItsOwnerAndReturnsItWithoutBytes() throws Exception {
        FileSpec spec = new FileSpec("docx", "Summary: v1?", "Summary", List.of(paragraph("Fix login [1][2].")));

        AiDocumentService.Created created = service.create(spec, " kim ", List.of(source(12), source(15)));

        ArgumentCaptor<AiChatFile> saved = ArgumentCaptor.forClass(AiChatFile.class);
        verify(repository).save(saved.capture());
        AiChatFile file = saved.getValue();
        assertEquals("kim", file.getOwnerLoginId());
        assertEquals("Summary v1.docx", file.getFileName());
        assertEquals(AiDocumentService.DOCX_TYPE, file.getContentType());
        assertEquals(file.getContent().length, file.getSizeBytes());
        assertEquals("ECWS", file.getAppCode());
        assertEquals("Y", file.getActiveInd());
        assertTrue(file.getSpec().contains("Fix login (Task #12, #15)."));
        Duration expiry = Duration.between(Instant.now(), file.getExpiresAt());
        assertTrue(expiry.compareTo(AiDocumentService.EXPIRY.minusMinutes(1)) > 0);

        AiGeneratedFileDTO dto = created.file();
        assertEquals(file.getId(), dto.getId());
        assertEquals("Summary v1.docx", dto.getFileName());
        assertNull(dto.getContent());
        assertNull(created.note());
    }

    @Test
    void createNeedsAnOwner() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
            new FileSpec("DOCX", "x", null, List.of()), " ", List.of()));
    }

    @Test
    void limitsCutLongTablesAndSayItWasCut() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < AiDocumentService.MAX_ROWS + 5; i++) {
            rows.add(List.of("r" + i));
        }
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < AiDocumentService.MAX_COLUMNS + 2; i++) {
            columns.add("c" + i);
        }
        List<String> notes = new ArrayList<>();

        FileSpec limited = AiDocumentService.limit(new FileSpec("XLSX", "x", null, List.of(
            table("t", columns, rows),
            paragraph("y".repeat(AiDocumentService.MAX_CELL_CHARS + 10)))), notes);

        BlockSpec table = limited.blocks().get(0);
        assertEquals(AiDocumentService.MAX_ROWS, table.rows().size());
        assertEquals(AiDocumentService.MAX_COLUMNS, table.columns().size());
        // short rows are padded to the header's width
        assertEquals(AiDocumentService.MAX_COLUMNS, table.rows().get(0).size());
        assertEquals("", table.rows().get(0).get(1));
        assertEquals(AiDocumentService.MAX_CELL_CHARS, limited.blocks().get(1).text().length());
        assertEquals(List.of(AiDocumentService.CUT_NOTE), notes);
    }

    @Test
    void limitsKeepSmallSpecsAndNameMissingColumns() {
        List<String> notes = new ArrayList<>();

        FileSpec limited = AiDocumentService.limit(new FileSpec("XLSX", "x", null, List.of(
            table("t", null, List.of(List.of("a", "b"))))), notes);

        assertEquals(List.of("Column 1", "Column 2"), limited.blocks().get(0).columns());
        assertTrue(notes.isEmpty());
    }

    @Test
    void fileNamesAreCleaned() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 9, 5);

        assertEquals(KOREAN + ".docx", AiDocumentService.fileName(KOREAN, "DOCX", now));
        assertEquals("ab.xlsx", AiDocumentService.fileName("a/b\u0001.xlsx", "XLSX", now));
        assertEquals("report.docx", AiDocumentService.fileName("report. ", "DOCX", now));
        assertEquals("AI-answer-20261007-0905.docx", AiDocumentService.fileName(" <> ", "DOCX", now));
        assertEquals("AI-answer-20261007-0905.xlsx", AiDocumentService.fileName(null, "XLSX", now));
        assertEquals("report.pdf", AiDocumentService.fileName("report.PDF", "PDF", now));
        assertEquals("x".repeat(AiDocumentService.MAX_FILE_NAME_CHARS) + ".docx",
            AiDocumentService.fileName("x".repeat(150), "DOCX", now));
    }

    @Test
    void citationsBecomeTaskReferences() {
        List<AiSearchResultDTO> sources = List.of(source(12), source(12), source(15));

        assertEquals("Login fails (Task #12).", AiDocumentService.citationsToTaskRefs("Login fails [1].", sources));
        assertEquals("Both (Task #12, #15).", AiDocumentService.citationsToTaskRefs("Both [1][2][3].", sources));
        assertEquals("Unknown.", AiDocumentService.citationsToTaskRefs("Unknown [9].", sources));
        assertNull(AiDocumentService.citationsToTaskRefs(null, sources));
    }

    @Test
    void downloadReturnsTheOwnersFileWithItsBytes() {
        UUID id = UUID.randomUUID();
        AiChatFile file = new AiChatFile();
        file.setId(id);
        file.setFileName("a.docx");
        file.setContent(new byte[] {1, 2});
        when(repository.findDownloadable(eq(id), eq("kim"), any())).thenReturn(Optional.of(file));

        Optional<AiGeneratedFileDTO> found = service.download(id, " kim ");

        assertTrue(found.isPresent());
        assertArrayEquals(new byte[] {1, 2}, found.get().getContent());
    }

    @Test
    void downloadOfAnotherUsersOrExpiredFileFindsNothing() {
        UUID id = UUID.randomUUID();
        when(repository.findDownloadable(any(), any(), any())).thenReturn(Optional.empty());

        assertFalse(service.download(id, "lee").isPresent());
        assertFalse(service.download(null, "kim").isPresent());
        assertFalse(service.download(id, null).isPresent());
    }
}
