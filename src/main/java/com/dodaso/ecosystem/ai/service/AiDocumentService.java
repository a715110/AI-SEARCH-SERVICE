package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.dto.AiGeneratedFileDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.AiChatFile;
import com.dodaso.ecosystem.ai.repository.AiChatFileRepository;
import com.dodaso.ecosystem.ai.service.AiAnswerSpec.BlockSpec;
import com.dodaso.ecosystem.ai.service.AiAnswerSpec.FileSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTOnOff;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTString;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the Word and Excel files the AI Search chat makes from an answer's document spec, saves
 * them for their owner, and serves their downloads. Files are built only from the spec: no macros,
 * formulas, links or images.
 */
@Service
@Slf4j
public class AiDocumentService {

    static final String DOCX = "DOCX";
    static final String XLSX = "XLSX";
    static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // Limits on a spec; content over them is cut and the answer says so
    static final int MAX_BLOCKS = 300;
    static final int MAX_ROWS = 2000;
    static final int MAX_COLUMNS = 30;
    // Excel allows 32,767 chars in a cell
    static final int MAX_CELL_CHARS = 32000;
    // A built file over this isn't saved (dodaso_chat_file CHECK)
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final int MAX_FILE_NAME_CHARS = 100;
    // Excel sheet names are at most 31 chars
    private static final int MAX_SHEET_NAME_CHARS = 31;
    // Column width cap in Excel, in characters
    private static final int MAX_COLUMN_CHARS = 60;

    static final Duration EXPIRY = Duration.ofDays(7);

    static final String CUT_NOTE = "The file was shortened to fit its limits (at most " + MAX_BLOCKS
        + " parts, " + MAX_ROWS + " rows and " + MAX_COLUMNS + " columns per table).";
    static final String NO_TABLE_NOTE = "No Excel file was made: the answer had no table to put in it.";
    static final String UNSUPPORTED_NOTE = "No file was made: only Word and Excel files can be made.";
    static final String TOO_LARGE_NOTE = "No file was made: it would be larger than "
        + (MAX_FILE_BYTES / (1024 * 1024)) + " MB.";

    // "[1]" or " [2][3]" in the model's text
    private static final Pattern CITATIONS = Pattern.compile("\\s?((\\[\\d{1,2}\\])+)");
    private static final Pattern CITATION_NUMBER = Pattern.compile("\\[(\\d{1,2})\\]");
    // Characters Windows doesn't allow in file names, and control characters
    private static final Pattern BAD_FILE_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9]\\d{0,14})(\\.\\d{1,10})?");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter DEFAULT_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private AiChatFileRepository chatFileRepository;

    /** A saved file (null when none was made) and a note for the answer (null when none). */
    public record Created(AiGeneratedFileDTO file, String note) {}

    /**
     * Builds the file a spec describes, saves it for loginId for EXPIRY, and returns it without
     * its bytes. [n] citations become "(Task #12)" from sources. No file is made for an unknown
     * format, an Excel spec without a table, or a file over MAX_FILE_BYTES; the note says why.
     */
    public Created create(FileSpec spec, String loginId, List<AiSearchResultDTO> sources) throws IOException {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("A generated file needs an owner");
        }
        String format = spec.format() != null ? spec.format().trim().toUpperCase(Locale.ROOT) : "";
        if (!DOCX.equals(format) && !XLSX.equals(format)) {
            return new Created(null, UNSUPPORTED_NOTE);
        }
        List<String> notes = new ArrayList<>();
        FileSpec clean = limit(withTaskRefs(spec, sources), notes);
        if (XLSX.equals(format) && clean.blocks().stream().noneMatch(AiDocumentService::isTable)) {
            return new Created(null, NO_TABLE_NOTE);
        }
        byte[] bytes = DOCX.equals(format) ? buildDocx(clean) : buildXlsx(clean);
        if (bytes.length > MAX_FILE_BYTES) {
            return new Created(null, TOO_LARGE_NOTE);
        }

        AiChatFile file = new AiChatFile();
        file.setId(UUID.randomUUID());
        file.setOwnerLoginId(loginId.trim());
        file.setFileName(fileName(clean.fileName(), format, LocalDateTime.now()));
        file.setContentType(DOCX.equals(format) ? DOCX_TYPE : XLSX_TYPE);
        file.setSizeBytes((long) bytes.length);
        file.setContent(bytes);
        file.setSpec(MAPPER.writeValueAsString(clean));
        file.setExpiresAt(Instant.now().plus(EXPIRY));
        chatFileRepository.save(file);
        log.info("Saved generated {} file {} ({} bytes) for {}", format, file.getId(), bytes.length, loginId);
        return new Created(toDTO(file, false), notes.isEmpty() ? null : String.join(" ", notes));
    }

    /** The file with its bytes, if it belongs to loginId, is active and hasn't expired. */
    public Optional<AiGeneratedFileDTO> download(UUID id, String loginId) {
        if (id == null || loginId == null || loginId.isBlank()) {
            return Optional.empty();
        }
        return chatFileRepository.findDownloadable(id, loginId.trim(), Instant.now())
            .map(file -> toDTO(file, true));
    }

    /** Deletes the files past their expiry; returns how many. */
    @Transactional
    public int purgeExpired() {
        return chatFileRepository.deleteExpired(Instant.now());
    }

    private static AiGeneratedFileDTO toDTO(AiChatFile file, boolean withContent) {
        return AiGeneratedFileDTO.builder()
            .id(file.getId())
            .fileName(file.getFileName())
            .contentType(file.getContentType())
            .sizeBytes(file.getSizeBytes())
            .expiresAt(file.getExpiresAt())
            .content(withContent ? file.getContent() : null)
            .build();
    }

    /**
     * A safe file name with the format's extension: characters Windows doesn't allow are removed,
     * an extension the model added is dropped, at most MAX_FILE_NAME_CHARS before the extension.
     * Blank: AI-answer-yyyyMMdd-HHmm.
     */
    static String fileName(String name, String format, LocalDateTime now) {
        String extension = DOCX.equals(format) ? ".docx" : ".xlsx";
        String clean = name != null ? BAD_FILE_NAME_CHARS.matcher(name).replaceAll("").trim() : "";
        clean = clean.replaceAll("(?i)\\.(docx|xlsx|doc|xls)$", "");
        if (clean.length() > MAX_FILE_NAME_CHARS) {
            clean = clean.substring(0, MAX_FILE_NAME_CHARS);
        }
        // Windows drops trailing dots and spaces
        clean = clean.replaceAll("[.\\s]+$", "");
        if (clean.isEmpty()) {
            clean = "AI-answer-" + now.format(DEFAULT_NAME_TIME);
        }
        return clean + extension;
    }

    /**
     * Text with its [n] citations replaced by the cited tasks, such as " (Task #12, #15)": [n] means
     * nothing outside the chat. A number with no source is dropped.
     */
    static String citationsToTaskRefs(String text, List<AiSearchResultDTO> sources) {
        if (text == null) {
            return null;
        }
        Matcher matcher = CITATIONS.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Set<Long> taskIds = new LinkedHashSet<>();
            Matcher number = CITATION_NUMBER.matcher(matcher.group(1));
            while (number.find()) {
                int n = Integer.parseInt(number.group(1));
                if (sources != null && n >= 1 && n <= sources.size() && sources.get(n - 1).getTaskId() != null) {
                    taskIds.add(sources.get(n - 1).getTaskId());
                }
            }
            String replacement = "";
            if (!taskIds.isEmpty()) {
                List<String> refs = new ArrayList<>();
                for (Long taskId : taskIds) {
                    refs.add((refs.isEmpty() ? "Task #" : "#") + taskId);
                }
                replacement = " (" + String.join(", ", refs) + ")";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static FileSpec withTaskRefs(FileSpec spec, List<AiSearchResultDTO> sources) {
        List<BlockSpec> blocks = new ArrayList<>();
        for (BlockSpec block : spec.blocks() != null ? spec.blocks() : List.<BlockSpec>of()) {
            if (block == null) {
                continue;
            }
            blocks.add(new BlockSpec(block.type(), block.level(),
                citationsToTaskRefs(block.text(), sources),
                mapTexts(block.items(), sources),
                block.name(),
                mapTexts(block.columns(), sources),
                block.rows() == null ? null : block.rows().stream()
                    .map(row -> mapTexts(row, sources)).toList()));
        }
        return new FileSpec(spec.format(), spec.fileName(), citationsToTaskRefs(spec.title(), sources), blocks);
    }

    private static List<String> mapTexts(List<String> texts, List<AiSearchResultDTO> sources) {
        if (texts == null) {
            return null;
        }
        List<String> mapped = new ArrayList<>();
        for (String text : texts) {
            mapped.add(citationsToTaskRefs(text, sources));
        }
        return mapped;
    }

    /**
     * The spec cut to the limits: MAX_BLOCKS blocks, MAX_ROWS rows and MAX_COLUMNS columns per table,
     * MAX_CELL_CHARS per text. Table rows are padded or cut to the header's width, and a table
     * without columns gets "Column n" headers. Adds CUT_NOTE to notes when anything was cut.
     */
    static FileSpec limit(FileSpec spec, List<String> notes) {
        boolean[] cut = {false};
        List<BlockSpec> source = spec.blocks() != null ? spec.blocks() : List.of();
        if (source.size() > MAX_BLOCKS) {
            source = source.subList(0, MAX_BLOCKS);
            cut[0] = true;
        }
        List<BlockSpec> blocks = new ArrayList<>();
        for (BlockSpec block : source) {
            if (block == null) {
                continue;
            }
            List<String> columns = null;
            List<List<String>> rows = null;
            if (isTable(block)) {
                List<List<String>> sourceRows = block.rows() != null ? block.rows() : List.of();
                if (sourceRows.size() > MAX_ROWS) {
                    sourceRows = sourceRows.subList(0, MAX_ROWS);
                    cut[0] = true;
                }
                int width = block.columns() != null && !block.columns().isEmpty()
                    ? block.columns().size()
                    : sourceRows.stream().mapToInt(row -> row != null ? row.size() : 0).max().orElse(0);
                if (width > MAX_COLUMNS) {
                    width = MAX_COLUMNS;
                    cut[0] = true;
                }
                columns = new ArrayList<>();
                for (int c = 0; c < width; c++) {
                    String header = block.columns() != null && c < block.columns().size() ? block.columns().get(c) : null;
                    columns.add(header != null && !header.isBlank() ? cutText(header, cut) : "Column " + (c + 1));
                }
                rows = new ArrayList<>();
                for (List<String> row : sourceRows) {
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < width; c++) {
                        String cell = row != null && c < row.size() ? row.get(c) : null;
                        cells.add(cell != null ? cutText(cell, cut) : "");
                    }
                    if (row != null && row.size() > width) {
                        cut[0] = true;
                    }
                    rows.add(cells);
                }
            }
            List<String> items = null;
            if (block.items() != null) {
                items = new ArrayList<>();
                for (String item : block.items()) {
                    if (item != null) {
                        items.add(cutText(item, cut));
                    }
                }
            }
            blocks.add(new BlockSpec(block.type(), block.level(), cutText(block.text(), cut), items,
                block.name() != null ? cutText(block.name(), cut) : null, columns, rows));
        }
        if (cut[0]) {
            notes.add(CUT_NOTE);
        }
        return new FileSpec(spec.format(), spec.fileName(), cutText(spec.title(), cut), blocks);
    }

    private static String cutText(String text, boolean[] cut) {
        if (text != null && text.length() > MAX_CELL_CHARS) {
            cut[0] = true;
            return text.substring(0, MAX_CELL_CHARS);
        }
        return text;
    }

    private static boolean isTable(BlockSpec block) {
        return block != null && "table".equalsIgnoreCase(block.type());
    }

    private static boolean isType(BlockSpec block, String type) {
        return type.equalsIgnoreCase(block.type());
    }

    // ---- Word ----

    /** Title, headings 1-3, paragraphs, bulleted lists and tables with a bold header row. */
    static byte[] buildDocx(FileSpec spec) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFStyles styles = doc.createStyles();
            addHeadingStyle(styles, "Title", null);
            for (int level = 1; level <= 3; level++) {
                addHeadingStyle(styles, "Heading" + level, level - 1);
            }
            if (spec.title() != null && !spec.title().isBlank()) {
                addText(doc.createParagraph(), "Title", spec.title(), 20);
            }
            for (BlockSpec block : spec.blocks()) {
                if (isType(block, "heading")) {
                    int level = block.level() != null ? Math.max(1, Math.min(3, block.level())) : 1;
                    addText(doc.createParagraph(), "Heading" + level, block.text(), level == 1 ? 16 : level == 2 ? 14 : 12);
                } else if (isType(block, "bullets")) {
                    for (String item : block.items() != null ? block.items() : List.<String>of()) {
                        XWPFParagraph paragraph = doc.createParagraph();
                        paragraph.setIndentationLeft(360);
                        paragraph.setIndentationHanging(360);
                        addRuns(paragraph, "•\t" + item, false, null);
                    }
                } else if (isTable(block)) {
                    addDocxTable(doc, block);
                } else if (block.text() != null && !block.text().isBlank()) {
                    addRuns(doc.createParagraph(), block.text(), false, null);
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    // A paragraph style with an outline level (null for Title), so Word's navigation pane shows it
    private static void addHeadingStyle(XWPFStyles styles, String styleId, Integer outlineLevel) {
        CTStyle ctStyle = CTStyle.Factory.newInstance();
        ctStyle.setStyleId(styleId);
        CTString name = CTString.Factory.newInstance();
        name.setVal(styleId.startsWith("Heading") ? "heading " + styleId.substring("Heading".length()) : styleId);
        ctStyle.setName(name);
        ctStyle.setQFormat(CTOnOff.Factory.newInstance());
        if (outlineLevel != null) {
            CTDecimalNumber level = CTDecimalNumber.Factory.newInstance();
            level.setVal(BigInteger.valueOf(outlineLevel));
            CTPPrGeneral paragraphProperties = CTPPrGeneral.Factory.newInstance();
            paragraphProperties.setOutlineLvl(level);
            ctStyle.setPPr(paragraphProperties);
        }
        XWPFStyle style = new XWPFStyle(ctStyle);
        style.setType(STStyleType.PARAGRAPH);
        styles.addStyle(style);
    }

    private static void addText(XWPFParagraph paragraph, String styleId, String text, int fontSize) {
        paragraph.setStyle(styleId);
        paragraph.setSpacingBefore(240);
        paragraph.setSpacingAfter(120);
        addRuns(paragraph, text != null ? text : "", true, fontSize);
    }

    // Line breaks in the text become breaks in the paragraph
    private static void addRuns(XWPFParagraph paragraph, String text, boolean bold, Integer fontSize) {
        String[] lines = text.split("\\r?\\n", -1);
        XWPFRun run = paragraph.createRun();
        run.setBold(bold);
        if (fontSize != null) {
            run.setFontSize(fontSize);
        }
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                run.addBreak();
            }
            run.setText(lines[i], i);
        }
    }

    private static void addDocxTable(XWPFDocument doc, BlockSpec block) {
        if (block.name() != null && !block.name().isBlank()) {
            addRuns(doc.createParagraph(), block.name(), true, null);
        }
        int width = block.columns().size();
        if (width == 0) {
            return;
        }
        XWPFTable table = doc.createTable(block.rows().size() + 1, width);
        table.setWidth("100%");
        fillRow(table.getRow(0), block.columns(), true);
        for (int r = 0; r < block.rows().size(); r++) {
            fillRow(table.getRow(r + 1), block.rows().get(r), false);
        }
        // Space after the table
        doc.createParagraph();
    }

    private static void fillRow(XWPFTableRow row, List<String> cells, boolean header) {
        if (header) {
            row.setRepeatHeader(true);
        }
        for (int c = 0; c < cells.size(); c++) {
            XWPFTableCell cell = row.getCell(c);
            XWPFParagraph paragraph = cell.getParagraphs().get(0);
            addRuns(paragraph, cells.get(c), header, null);
            if (header) {
                cell.setColor("F2F2F2");
            }
        }
    }

    // ---- Excel ----

    /**
     * One sheet per table, named after it (unique, at most 31 chars), with a bold frozen header row.
     * A column whose cells are all numbers or all yyyy-MM-dd dates is written as numbers or dates;
     * everything else is text, so "=SUM(A1)" stays text. The title, headings, paragraphs and
     * bullets go on a last "Notes" sheet, only when there are any.
     */
    static byte[] buildXlsx(FileSpec spec) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(bold);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle wrapStyle = workbook.createCellStyle();
            wrapStyle.setWrapText(true);

            Set<String> sheetNames = new HashSet<>();
            List<String> notes = new ArrayList<>();
            if (spec.title() != null && !spec.title().isBlank()) {
                notes.add(spec.title());
            }
            int tableCount = 0;
            for (BlockSpec block : spec.blocks()) {
                if (isTable(block)) {
                    tableCount++;
                    Sheet sheet = workbook.createSheet(sheetName(block.name(), tableCount, sheetNames));
                    writeTable(sheet, block, headerStyle, dateStyle);
                } else if (isType(block, "bullets")) {
                    for (String item : block.items() != null ? block.items() : List.<String>of()) {
                        notes.add("• " + item);
                    }
                } else if (block.text() != null && !block.text().isBlank()) {
                    notes.add(block.text());
                }
            }
            // A title alone doesn't make a Notes sheet
            int titleLines = spec.title() != null && !spec.title().isBlank() ? 1 : 0;
            if (notes.size() > titleLines) {
                Sheet sheet = workbook.createSheet(sheetName("Notes", 0, sheetNames));
                for (int i = 0; i < notes.size(); i++) {
                    Cell cell = sheet.createRow(i).createCell(0);
                    cell.setCellValue(notes.get(i));
                    cell.setCellStyle(wrapStyle);
                }
                sheet.setColumnWidth(0, 100 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static String sheetName(String name, int tableNumber, Set<String> used) {
        String base = name != null && !name.isBlank() ? WorkbookUtil.createSafeSheetName(name.trim()) : "Table " + tableNumber;
        if (base.length() > MAX_SHEET_NAME_CHARS) {
            base = base.substring(0, MAX_SHEET_NAME_CHARS);
        }
        String candidate = base;
        for (int n = 2; used.contains(candidate.toLowerCase(Locale.ROOT)); n++) {
            String suffix = " (" + n + ")";
            candidate = base.substring(0, Math.min(base.length(), MAX_SHEET_NAME_CHARS - suffix.length())) + suffix;
        }
        used.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private enum ColumnKind { TEXT, NUMBER, DATE }

    private static void writeTable(Sheet sheet, BlockSpec block, CellStyle headerStyle, CellStyle dateStyle) {
        List<String> columns = block.columns();
        List<List<String>> rows = block.rows();
        int[] widths = new int[columns.size()];

        Row header = sheet.createRow(0);
        for (int c = 0; c < columns.size(); c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(columns.get(c));
            cell.setCellStyle(headerStyle);
            widths[c] = displayWidth(columns.get(c));
        }
        ColumnKind[] kinds = new ColumnKind[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            kinds[c] = columnKind(rows, c);
        }
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < columns.size(); c++) {
                String value = rows.get(r).get(c);
                if (value == null || value.isBlank()) {
                    continue;
                }
                Cell cell = row.createCell(c);
                String trimmed = value.trim();
                if (kinds[c] == ColumnKind.NUMBER) {
                    cell.setCellValue(Double.parseDouble(trimmed));
                } else if (kinds[c] == ColumnKind.DATE) {
                    cell.setCellValue(LocalDate.parse(trimmed));
                    cell.setCellStyle(dateStyle);
                } else {
                    cell.setCellValue(value);
                }
                widths[c] = Math.max(widths[c], displayWidth(value));
            }
        }
        sheet.createFreezePane(0, 1);
        for (int c = 0; c < columns.size(); c++) {
            sheet.setColumnWidth(c, (Math.min(widths[c], MAX_COLUMN_CHARS) + 2) * 256);
        }
    }

    // NUMBER or DATE when every non-blank cell of the column is one; TEXT otherwise or when empty
    private static ColumnKind columnKind(List<List<String>> rows, int column) {
        boolean numbers = true;
        boolean dates = true;
        boolean any = false;
        for (List<String> row : rows) {
            String value = row.get(column);
            if (value == null || value.isBlank()) {
                continue;
            }
            any = true;
            String trimmed = value.trim();
            numbers &= NUMBER.matcher(trimmed).matches();
            dates &= isIsoDate(trimmed);
            if (!numbers && !dates) {
                return ColumnKind.TEXT;
            }
        }
        if (!any) {
            return ColumnKind.TEXT;
        }
        return numbers ? ColumnKind.NUMBER : dates ? ColumnKind.DATE : ColumnKind.TEXT;
    }

    private static boolean isIsoDate(String value) {
        if (!ISO_DATE.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // Characters wide, counting Hangul and other wide characters as two; the longest line counts
    private static int displayWidth(String text) {
        int longest = 0;
        for (String line : text.split("\\r?\\n")) {
            int width = 0;
            for (int i = 0; i < line.length(); i++) {
                width += line.charAt(i) > 0x2E80 ? 2 : 1;
            }
            longest = Math.max(longest, width);
        }
        return longest;
    }
}
