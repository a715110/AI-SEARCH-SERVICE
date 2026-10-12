package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.service.AiAnswerSpec.BlockSpec;
import com.dodaso.ecosystem.ai.service.AiAnswerSpec.FileSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Builds the PDF files the AI Search chat makes from an answer's document spec: title, headings
 * 1-3, paragraphs, bulleted lists and tables with a gray bold header row that repeats on every
 * page. PDFBox's built-in fonts can't draw Hangul, so Nanum Gothic (SIL Open Font License, in
 * resources/fonts) is embedded as a subset; we wrap lines ourselves. A character the font doesn't
 * have (such as an emoji) is drawn as "?". Pages are A4, landscape when a table has more than
 * LANDSCAPE_COLUMNS columns, and numbered "n / total". No links, scripts or images.
 */
final class AiPdfBuilder {

    private static final String REGULAR_FONT = "/fonts/NanumGothic-Regular.ttf";
    private static final String BOLD_FONT = "/fonts/NanumGothic-Bold.ttf";

    private static final float MARGIN = 56;
    private static final float TITLE_SIZE = 20;
    private static final float[] HEADING_SIZES = {16, 14, 12};
    private static final float TEXT_SIZE = 11;
    private static final float TABLE_SIZE = 9;
    private static final float SMALL_TABLE_SIZE = 7;
    private static final float FOOTER_SIZE = 8;
    private static final float LEADING = 1.45f;
    private static final float CELL_PADDING = 4;
    private static final float BULLET_INDENT = 16;
    // Tables wider than this make the pages landscape; wider than SMALL_TABLE_COLUMNS use a smaller font
    static final int LANDSCAPE_COLUMNS = 6;
    private static final int SMALL_TABLE_COLUMNS = 10;
    // A column is sized to its content up to this many characters, then its text wraps
    private static final int MAX_COLUMN_CHARS = 40;
    private static final int MAX_HEADER_LINES = 4;

    // Font files read once; each document embeds its own subset
    private static volatile byte[] regularFontBytes;
    private static volatile byte[] boldFontBytes;

    private final PDDocument doc;
    private final PDFont regular;
    private final PDFont bold;
    private final PDRectangle pageSize;
    private final Map<PDFont, Map<Integer, Float>> glyphWidths = new HashMap<>();
    private PDPageContentStream content;
    // Top of the free space on the current page
    private float y;

    private AiPdfBuilder(PDDocument doc, PDRectangle pageSize) throws IOException {
        this.doc = doc;
        this.pageSize = pageSize;
        this.regular = PDType0Font.load(doc, new ByteArrayInputStream(fontBytes(false)), true);
        this.bold = PDType0Font.load(doc, new ByteArrayInputStream(fontBytes(true)), true);
    }

    static byte[] build(FileSpec spec) throws IOException {
        PDRectangle pageSize = spec.blocks().stream()
            .anyMatch(block -> isTable(block) && block.columns().size() > LANDSCAPE_COLUMNS)
            ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
            : PDRectangle.A4;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            AiPdfBuilder builder = new AiPdfBuilder(doc, pageSize);
            builder.newPage();
            builder.write(spec);
            builder.content.close();
            builder.addPageNumbers();

            PDDocumentInformation info = doc.getDocumentInformation();
            if (spec.title() != null && !spec.title().isBlank()) {
                info.setTitle(spec.title());
            }
            info.setCreator("AI Search");
            info.setCreationDate(Calendar.getInstance());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] fontBytes(boolean isBold) throws IOException {
        byte[] bytes = isBold ? boldFontBytes : regularFontBytes;
        if (bytes == null) {
            String path = isBold ? BOLD_FONT : REGULAR_FONT;
            try (InputStream in = AiPdfBuilder.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IOException("Font " + path + " is missing from the classpath");
                }
                bytes = in.readAllBytes();
            }
            if (isBold) {
                boldFontBytes = bytes;
            } else {
                regularFontBytes = bytes;
            }
        }
        return bytes;
    }

    private static boolean isTable(BlockSpec block) {
        return "table".equalsIgnoreCase(block.type());
    }

    private static boolean isType(BlockSpec block, String type) {
        return type.equalsIgnoreCase(block.type());
    }

    private float left() {
        return MARGIN;
    }

    private float contentWidth() {
        return pageSize.getWidth() - 2 * MARGIN;
    }

    private float bottom() {
        return MARGIN;
    }

    private void newPage() throws IOException {
        if (content != null) {
            content.close();
        }
        PDPage page = new PDPage(pageSize);
        doc.addPage(page);
        content = new PDPageContentStream(doc, page);
        y = pageSize.getHeight() - MARGIN;
    }

    // Starts a new page when less than height is left on this one
    private void ensureSpace(float height) throws IOException {
        if (y - height < bottom()) {
            newPage();
        }
    }

    private void write(FileSpec spec) throws IOException {
        if (spec.title() != null && !spec.title().isBlank()) {
            writeText(spec.title(), bold, TITLE_SIZE, left(), contentWidth(), 0, 10);
        }
        for (BlockSpec block : spec.blocks()) {
            if (isType(block, "heading")) {
                int level = block.level() != null ? Math.max(1, Math.min(3, block.level())) : 1;
                float size = HEADING_SIZES[level - 1];
                writeText(block.text(), bold, size, left(), contentWidth(), size * 0.8f, 4);
            } else if (isType(block, "bullets")) {
                String bullet = canDraw(regular, '•') ? "•" : "-";
                for (String item : block.items() != null ? block.items() : List.<String>of()) {
                    writeBullet(bullet, item);
                }
                y -= TEXT_SIZE * 0.5f;
            } else if (isTable(block)) {
                writeTable(block);
            } else if (block.text() != null && !block.text().isBlank()) {
                writeText(block.text(), regular, TEXT_SIZE, left(), contentWidth(), 0, TEXT_SIZE * 0.6f);
            }
        }
    }

    // Wrapped text, moving to a new page line by line as needed
    private void writeText(String text, PDFont font, float size, float x, float width,
        float spaceBefore, float spaceAfter) throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }
        y -= spaceBefore;
        float lineHeight = size * LEADING;
        for (String line : wrap(text, font, size, width)) {
            ensureSpace(lineHeight);
            showText(line, font, size, x, y - size);
            y -= lineHeight;
        }
        y -= spaceAfter;
    }

    private void writeBullet(String bullet, String item) throws IOException {
        if (item == null) {
            return;
        }
        float lineHeight = TEXT_SIZE * LEADING;
        List<String> lines = wrap(item, regular, TEXT_SIZE, contentWidth() - BULLET_INDENT);
        for (int i = 0; i < lines.size(); i++) {
            ensureSpace(lineHeight);
            if (i == 0) {
                showText(bullet, regular, TEXT_SIZE, left() + 4, y - TEXT_SIZE);
            }
            showText(lines.get(i), regular, TEXT_SIZE, left() + BULLET_INDENT, y - TEXT_SIZE);
            y -= lineHeight;
        }
    }

    private void showText(String text, PDFont font, float size, float x, float baseline) throws IOException {
        if (text.isEmpty()) {
            return;
        }
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, baseline);
        content.showText(text);
        content.endText();
    }

    // ---- Tables ----

    /**
     * The table's name, then a bordered table whose columns are sized to their content. A row that
     * doesn't fit continues on the next page, under the header again.
     */
    private void writeTable(BlockSpec block) throws IOException {
        List<String> columns = block.columns();
        if (columns.isEmpty()) {
            return;
        }
        if (block.name() != null && !block.name().isBlank()) {
            writeText(block.name(), bold, TEXT_SIZE, left(), contentWidth(), TEXT_SIZE * 0.4f, 4);
        }
        float size = columns.size() > SMALL_TABLE_COLUMNS ? SMALL_TABLE_SIZE : TABLE_SIZE;
        float[] widths = columnWidths(block, size);

        List<List<String>> header = wrapRow(columns, widths, bold, size);
        for (int c = 0; c < header.size(); c++) {
            if (header.get(c).size() > MAX_HEADER_LINES) {
                header.set(c, new ArrayList<>(header.get(c).subList(0, MAX_HEADER_LINES)));
            }
        }
        float lineHeight = size * LEADING;
        // The header and at least one line of a row together, so a header is never alone at a page end
        ensureSpace(rowHeight(header, lineHeight) + lineHeight + 2 * CELL_PADDING);
        drawRow(header, 0, maxLines(header), widths, bold, size, true);
        float pageRoom = pageSize.getHeight() - 2 * MARGIN - rowHeight(header, lineHeight);

        for (List<String> row : block.rows()) {
            List<List<String>> cells = wrapRow(row, widths, regular, size);
            int lines = maxLines(cells);
            // A row that doesn't fit here but fits on a new page moves there in one piece
            float height = rowHeight(cells, lineHeight);
            if (y - height < bottom() && height <= pageRoom) {
                newTablePage(header, widths, size);
            }
            // A row taller than a page is split between pages
            int from = 0;
            while (from < lines) {
                int fit = (int) Math.floor((y - bottom() - 2 * CELL_PADDING) / lineHeight);
                if (fit < 1) {
                    newTablePage(header, widths, size);
                    continue;
                }
                int to = Math.min(lines, from + fit);
                drawRow(cells, from, to, widths, regular, size, false);
                from = to;
            }
        }
        y -= TEXT_SIZE;
    }

    private void newTablePage(List<List<String>> header, float[] widths, float size) throws IOException {
        newPage();
        drawRow(header, 0, maxLines(header), widths, bold, size, true);
    }

    private List<List<String>> wrapRow(List<String> cells, float[] widths, PDFont font, float size) {
        List<List<String>> wrapped = new ArrayList<>();
        for (int c = 0; c < widths.length; c++) {
            String cell = c < cells.size() && cells.get(c) != null ? cells.get(c) : "";
            wrapped.add(wrap(cell, font, size, widths[c] - 2 * CELL_PADDING));
        }
        return wrapped;
    }

    private static int maxLines(List<List<String>> cells) {
        return Math.max(1, cells.stream().mapToInt(List::size).max().orElse(1));
    }

    private static float rowHeight(List<List<String>> cells, float lineHeight) {
        return maxLines(cells) * lineHeight + 2 * CELL_PADDING;
    }

    // Lines from..to of each cell as one bordered row; the header row is filled gray
    private void drawRow(List<List<String>> cells, int from, int to, float[] widths, PDFont font,
        float size, boolean header) throws IOException {
        float lineHeight = size * LEADING;
        float height = (to - from) * lineHeight + 2 * CELL_PADDING;
        float x = left();
        float top = y;
        if (header) {
            content.setNonStrokingColor(0.95f, 0.95f, 0.95f);
            content.addRect(x, top - height, sum(widths), height);
            content.fill();
            content.setNonStrokingColor(0f, 0f, 0f);
        }
        content.setStrokingColor(0.6f, 0.6f, 0.6f);
        content.setLineWidth(0.5f);
        for (int c = 0; c < widths.length; c++) {
            content.addRect(x, top - height, widths[c], height);
            content.stroke();
            List<String> lines = cells.get(c);
            for (int line = from; line < to && line < lines.size(); line++) {
                float baseline = top - CELL_PADDING - (line - from) * lineHeight - size;
                showText(lines.get(line), font, size, x + CELL_PADDING, baseline);
            }
            x += widths[c];
        }
        y -= height;
    }

    /**
     * Each column as wide as its longest text (header or cell, up to MAX_COLUMN_CHARS characters),
     * then all scaled to fill the page width.
     */
    private float[] columnWidths(BlockSpec block, float size) {
        int count = block.columns().size();
        float[] wanted = new float[count];
        for (int c = 0; c < count; c++) {
            float widest = textWidth(block.columns().get(c), bold, size);
            for (List<String> row : block.rows()) {
                if (c < row.size() && row.get(c) != null) {
                    widest = Math.max(widest, textWidth(row.get(c), regular, size));
                }
            }
            float cap = MAX_COLUMN_CHARS * size * 0.6f;
            float floor = 3 * size;
            wanted[c] = Math.max(floor, Math.min(cap, widest)) + 2 * CELL_PADDING;
        }
        float scale = contentWidth() / sum(wanted);
        for (int c = 0; c < count; c++) {
            wanted[c] *= scale;
        }
        return wanted;
    }

    private static float sum(float[] values) {
        float total = 0;
        for (float value : values) {
            total += value;
        }
        return total;
    }

    // ---- Text ----

    /**
     * The text's lines at most width wide: line breaks are kept, lines wrap at spaces, and a word
     * wider than the line is broken between characters. Tabs become spaces, other control
     * characters are dropped and characters the font doesn't have become "?".
     */
    private List<String> wrap(String text, PDFont font, float size, float width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : drawable(text, font).split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            float lineWidth = 0;
            int lastSpace = -1;
            for (int i = 0; i < paragraph.length(); ) {
                int codePoint = paragraph.codePointAt(i);
                int charCount = Character.charCount(codePoint);
                float glyph = glyphWidth(font, codePoint) * size;
                if (lineWidth + glyph > width && line.length() > 0) {
                    if (codePoint == ' ') {
                        lines.add(stripEnd(line));
                        line.setLength(0);
                        lineWidth = 0;
                        lastSpace = -1;
                        i += charCount;
                        continue;
                    }
                    String rest = "";
                    if (lastSpace >= 0) {
                        rest = line.substring(lastSpace + 1);
                        line.setLength(lastSpace);
                    }
                    lines.add(stripEnd(line));
                    line.setLength(0);
                    line.append(rest);
                    lineWidth = textWidthDrawable(rest, font, size);
                    lastSpace = -1;
                }
                if (codePoint == ' ') {
                    lastSpace = line.length();
                }
                line.appendCodePoint(codePoint);
                lineWidth += glyph;
                i += charCount;
            }
            lines.add(stripEnd(line));
        }
        return lines;
    }

    private static String stripEnd(StringBuilder line) {
        return line.toString().stripTrailing();
    }

    // Text with only characters the font can draw: "\n" for line breaks, spaces for tabs, "?" otherwise
    private String drawable(String text, PDFont font) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(codePoint -> {
            if (codePoint == '\n') {
                result.append('\n');
            } else if (Character.isISOControl(codePoint) || codePoint == 0xFEFF) {
                // dropped
            } else if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                result.append(' ');
            } else if (glyphWidth(font, codePoint) < 0) {
                result.append('?');
            } else {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private boolean canDraw(PDFont font, int codePoint) {
        return glyphWidth(font, codePoint) >= 0;
    }

    // Width of a character at size 1, or -1 when the font has no glyph for it
    private float glyphWidth(PDFont font, int codePoint) {
        return glyphWidths.computeIfAbsent(font, f -> new HashMap<>()).computeIfAbsent(codePoint, cp -> {
            try {
                return font.getStringWidth(new String(Character.toChars(cp))) / 1000f;
            } catch (IllegalArgumentException e) {
                return -1f;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // Width of the longest line of a text that may still have characters the font can't draw
    private float textWidth(String text, PDFont font, float size) {
        float widest = 0;
        for (String line : drawable(text, font).split("\n")) {
            widest = Math.max(widest, textWidthDrawable(line, font, size));
        }
        return widest;
    }

    private float textWidthDrawable(String text, PDFont font, float size) {
        float width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += glyphWidth(font, codePoint) * size;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private void addPageNumbers() throws IOException {
        int total = doc.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            PDPage page = doc.getPage(i);
            try (PDPageContentStream footer = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
                String label = (i + 1) + " / " + total;
                float x = (pageSize.getWidth() - textWidthDrawable(label, regular, FOOTER_SIZE)) / 2;
                footer.setNonStrokingColor(0.4f, 0.4f, 0.4f);
                footer.beginText();
                footer.setFont(regular, FOOTER_SIZE);
                footer.newLineAtOffset(x, MARGIN / 2);
                footer.showText(label);
                footer.endText();
            }
        }
    }
}
