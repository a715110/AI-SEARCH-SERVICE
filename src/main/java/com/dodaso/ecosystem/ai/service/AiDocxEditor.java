package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.service.AiAnswerSpec.ReplacementSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

/**
 * Changes wording in a Word (.docx) file and keeps everything else: fonts, tables, headers,
 * numbering and layout. Each find is looked for in every paragraph of the body, of tables (also
 * nested) and of headers and footers; every occurrence is replaced. A match can span runs: the
 * replacement takes the formatting of the run the match starts in. Curly and straight quotes, and
 * non-breaking and plain spaces, match each other. Text boxes, footnotes and fields aren't searched.
 */
public final class AiDocxEditor {

    // Replacements applied from one answer; the rest are ignored
    static final int MAX_REPLACEMENTS = 100;

    private AiDocxEditor() {
    }

    /** The edited file, and how many times each find was replaced (0 when it wasn't found). */
    public record Result(byte[] bytes, Map<String, Integer> counts) {

        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        public List<String> notFound() {
            return counts.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList();
        }
    }

    public static Result apply(byte[] docx, List<ReplacementSpec> replacements) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            List<XWPFParagraph> paragraphs = new ArrayList<>();
            collect(doc.getBodyElements(), paragraphs);
            for (XWPFHeader header : doc.getHeaderList()) {
                collect(header.getBodyElements(), paragraphs);
            }
            for (XWPFFooter footer : doc.getFooterList()) {
                collect(footer.getBodyElements(), paragraphs);
            }

            Map<String, Integer> counts = new LinkedHashMap<>();
            if (replacements != null) {
                for (ReplacementSpec replacement : replacements.stream().limit(MAX_REPLACEMENTS).toList()) {
                    if (replacement == null || replacement.find() == null || replacement.find().isEmpty()) {
                        continue;
                    }
                    String replace = replacement.replace() != null ? replacement.replace() : "";
                    int count = 0;
                    // for (XWPFParagraph paragraph : paragraphs) {
                    //     count += replaceIn(paragraph, replacement.find(), replace);
                    // }
                    for (ReplacementSpec cell : byCell(replacement.find(), replace)) {
                        for (XWPFParagraph paragraph : paragraphs) {
                            count += replaceIn(paragraph, cell.find(), cell.replace());
                        }
                    }
                    counts.merge(replacement.find(), count, Integer::sum);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return new Result(out.toByteArray(), counts);
        }
    }

    // Between table cells in the text the model reads (FileTextExtractorService)
    static final String CELL_SEPARATOR = " | ";

    /**
     * A find copied from a whole table row ("Year 1 | $28.50 | 1-3 abated") can't match, since
     * cells are separate paragraphs. When find and replace have the same number of cells, each cell
     * that changed becomes its own replacement; otherwise the pair is used as it is.
     */
    static List<ReplacementSpec> byCell(String find, String replace) {
        if (!find.contains(CELL_SEPARATOR)) {
            return List.of(new ReplacementSpec(find, replace));
        }
        String[] findCells = find.split(Pattern.quote(CELL_SEPARATOR), -1);
        String[] replaceCells = replace.split(Pattern.quote(CELL_SEPARATOR), -1);
        if (findCells.length != replaceCells.length) {
            return List.of(new ReplacementSpec(find, replace));
        }
        List<ReplacementSpec> cells = new ArrayList<>();
        for (int i = 0; i < findCells.length; i++) {
            if (!findCells[i].equals(replaceCells[i]) && !findCells[i].isEmpty()) {
                cells.add(new ReplacementSpec(findCells[i], replaceCells[i]));
            }
        }
        return cells;
    }

    private static void collect(List<IBodyElement> elements, List<XWPFParagraph> paragraphs) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                paragraphs.add(paragraph);
            } else if (element instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        collect(cell.getBodyElements(), paragraphs);
                    }
                }
            }
        }
    }

    /**
     * Replaces every occurrence of find in the paragraph; returns how many. The paragraph's text is
     * the w:t elements of its runs in order (tabs and breaks aren't text, so a find can't cross
     * them). The first w:t of a match gets the text before it plus the replacement, the w:t
     * elements inside it become empty, and the last keeps the text after it.
     */
    static int replaceIn(XWPFParagraph paragraph, String find, String replace) {
        String normalizedFind = normalize(find);
        int count = 0;
        int from = 0;
        while (true) {
            List<CTText> texts = new ArrayList<>();
            for (XWPFRun run : paragraph.getRuns()) {
                texts.addAll(run.getCTR().getTList());
            }
            int[] starts = new int[texts.size()];
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < texts.size(); i++) {
                starts[i] = all.length();
                all.append(value(texts.get(i)));
            }
            int at = normalize(all.toString()).indexOf(normalizedFind, from);
            if (at < 0) {
                return count;
            }
            int end = at + find.length();
            int first = indexAt(starts, all.length(), at);
            int last = indexAt(starts, all.length(), end - 1);

            String firstValue = value(texts.get(first));
            String lastValue = value(texts.get(last));
            String before = firstValue.substring(0, at - starts[first]);
            String after = lastValue.substring(end - starts[last]);
            if (first == last) {
                setValue(texts.get(first), before + replace + after);
            } else {
                setValue(texts.get(first), before + replace);
                for (int i = first + 1; i < last; i++) {
                    setValue(texts.get(i), "");
                }
                setValue(texts.get(last), after);
            }
            count++;
            from = at + replace.length();
        }
    }

    /** The index of the w:t that holds character position of the paragraph's text. */
    private static int indexAt(int[] starts, int length, int position) {
        for (int i = starts.length - 1; i >= 0; i--) {
            int next = i + 1 < starts.length ? starts[i + 1] : length;
            if (starts[i] <= position && position < next) {
                return i;
            }
        }
        return 0;
    }

    private static String value(CTText text) {
        return text.getStringValue() != null ? text.getStringValue() : "";
    }

    private static void setValue(CTText text, String value) {
        text.setStringValue(value);
        // Word drops leading and trailing spaces without xml:space="preserve"
        text.setSpace(SpaceAttribute.Space.PRESERVE);
    }

    /** Curly quotes to straight ones and non-breaking spaces to spaces; the length doesn't change. */
    static String normalize(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            normalized.append(switch (c) {
                case '‘', '’', '‚', '‛', '′' -> '\'';
                case '“', '”', '„', '‟', '″' -> '"';
                case ' ', ' ', ' ' -> ' ';
                default -> c;
            });
        }
        return normalized.toString();
    }
}
