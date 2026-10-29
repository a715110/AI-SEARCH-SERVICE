package com.dodaso.ecosystem.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits contract text by clause, so a search hit is one whole clause instead of an arbitrary
 * 1800-character window that starts mid-clause. A heading line starts a clause:
 * <ul>
 *   <li>{@code Article 7}, {@code ARTICLE VII}, {@code Section 7.2} (with or without a title);</li>
 *   <li>a numbered heading such as {@code 7. Rent} or {@code 7.2 Late charges};</li>
 *   <li>the Korean article heading (je n jo), matched from code points.</li>
 * </ul>
 * Small neighbouring clauses are merged up to {@link #MAX_CHARS}; a clause longer than that is cut
 * at a sentence end near the cap when there is one. Text with no headings returns an empty list,
 * so the caller can fall back to plain chunking.
 */
final class ClauseChunker {

    /** Same cap as AiFileSyncService: stays under nomic-embed-text's 2048 tokens with a header. */
    static final int MAX_CHARS = 1800;

    /** How far back from the cap a sentence end is looked for. */
    static final int SENTENCE_LOOKBACK = 200;

    private static final int MAX_HEADING_CHARS = 100;

    // Hangul syllables JE (U+C81C) and JO (U+C870): "je 7 jo" = Article 7
    private static final String JE = new String(Character.toChars(0xC81C));
    private static final String JO = new String(Character.toChars(0xC870));

    private static final Pattern HEADING = Pattern.compile(
        "^\\s*(?:"
            + "(?:ARTICLE|Article|SECTION|Section|CLAUSE|Clause)\\s+(?:\\d{1,3}(?:\\.\\d{1,3})*|[IVXLC]{1,7})\\b"
            + "|\\d{1,2}(?:\\.\\d{1,2}){1,2}\\.?\\s+[A-Z]"
            + "|\\d{1,2}[.)]\\s+[A-Z]"
            + "|" + JE + "\\s*\\d{1,3}\\s*" + JO
            + ")");

    private ClauseChunker() {
    }

    /** One chunk and the clause heading it starts with (null for text before the first heading). */
    record Chunk(String heading, String text) {
    }

    static boolean isHeading(String line) {
        return line.length() <= 300 && HEADING.matcher(line).find();
    }

    static List<Chunk> chunk(String text) {
        List<Chunk> clauses = splitClauses(text);
        if (clauses.stream().noneMatch(c -> c.heading() != null)) {
            return List.of();
        }
        List<Chunk> chunks = new ArrayList<>();
        Chunk pending = null;
        for (Chunk clause : clauses) {
            if (clause.text().length() > MAX_CHARS) {
                if (pending != null) {
                    chunks.add(pending);
                    pending = null;
                }
                for (String piece : cutLong(clause.text())) {
                    chunks.add(new Chunk(clause.heading(), piece));
                }
            } else if (pending == null) {
                pending = clause;
            } else if (pending.text().length() + 2 + clause.text().length() <= MAX_CHARS) {
                pending = new Chunk(pending.heading() != null ? pending.heading() : clause.heading(),
                    pending.text() + "\n\n" + clause.text());
            } else {
                chunks.add(pending);
                pending = clause;
            }
        }
        if (pending != null) {
            chunks.add(pending);
        }
        return chunks;
    }

    /** Clauses in order; the text before the first heading is a clause with no heading. */
    static List<Chunk> splitClauses(String text) {
        List<Chunk> clauses = new ArrayList<>();
        String heading = null;
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (isHeading(line)) {
                addClause(clauses, heading, current);
                heading = headingOf(line);
                current.setLength(0);
            }
            if (!line.isBlank()) {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line.strip());
            }
        }
        addClause(clauses, heading, current);
        return clauses;
    }

    private static void addClause(List<Chunk> clauses, String heading, StringBuilder text) {
        if (!text.toString().isBlank()) {
            clauses.add(new Chunk(heading, text.toString()));
        }
    }

    private static String headingOf(String line) {
        String h = line.strip().replaceAll("\\s+", " ");
        return h.length() <= MAX_HEADING_CHARS ? h : h.substring(0, MAX_HEADING_CHARS);
    }

    /**
     * Pieces of at most MAX_CHARS. Each cut is at the last sentence end (". ", "? ", "! " or a
     * line break) within SENTENCE_LOOKBACK of the cap, else at the last space, else at the cap.
     */
    static List<String> cutLong(String text) {
        List<String> pieces = new ArrayList<>();
        String rest = text.strip();
        while (rest.length() > MAX_CHARS) {
            int cut = sentenceEnd(rest);
            if (cut <= 0) {
                cut = rest.lastIndexOf(' ', MAX_CHARS);
            }
            if (cut <= 0) {
                cut = MAX_CHARS;
            }
            pieces.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            pieces.add(rest);
        }
        return pieces;
    }

    private static int sentenceEnd(String text) {
        for (int i = MAX_CHARS - 1; i >= MAX_CHARS - SENTENCE_LOOKBACK; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if ((c == '.' || c == '?' || c == '!') && i + 1 < text.length()
                    && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return -1;
    }
}
