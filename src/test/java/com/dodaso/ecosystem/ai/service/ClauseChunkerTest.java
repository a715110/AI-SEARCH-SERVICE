package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract text is chunked by clause, with each chunk's heading kept for the embedding. */
class ClauseChunkerTest {

    // Hangul from code points: JE (U+C81C), JO (U+C870), "imdaeryo" (rent) U+C784 U+B300 U+B8CC
    private static final String JE = new String(Character.toChars(0xC81C));
    private static final String JO = new String(Character.toChars(0xC870));
    private static final String RENT_KO = new String(new int[] {0xC784, 0xB300, 0xB8CC}, 0, 3);

    private static String sentences(String word, int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) {
            b.append(word).append(" sentence number ").append(i).append(" ends here. ");
        }
        return b.toString().strip();
    }

    @Test
    void recognisesEnglishNumberedAndKoreanHeadings() {
        assertTrue(ClauseChunker.isHeading("Article 7 Rent"));
        assertTrue(ClauseChunker.isHeading("ARTICLE VII - RENT"));
        assertTrue(ClauseChunker.isHeading("Section 7.2 Late Charges"));
        assertTrue(ClauseChunker.isHeading("7. Rent"));
        assertTrue(ClauseChunker.isHeading("7.2 Late charges"));
        assertTrue(ClauseChunker.isHeading("12) Insurance"));
        assertTrue(ClauseChunker.isHeading(JE + "7" + JO + "(" + RENT_KO + ")"));
        assertTrue(ClauseChunker.isHeading(JE + " 12 " + JO));
    }

    @Test
    void bodyLinesAreNotHeadings() {
        assertFalse(ClauseChunker.isHeading("30 Days notice is required before the end of the term."));
        assertFalse(ClauseChunker.isHeading("The Tenant shall pay rent as set out in Article 7."));
        assertFalse(ClauseChunker.isHeading("2026 rent is $4,000 per month."));
        assertFalse(ClauseChunker.isHeading(""));
    }

    @Test
    void eachClauseKeepsItsHeadingAndSmallOnesAreMerged() {
        String text = "LEASE AGREEMENT\nBetween Landlord and Tenant.\n"
            + "1. Term\nThe term is five years.\n"
            + "2. Rent\nRent is $4,000 per month.\n";

        List<ClauseChunker.Chunk> chunks = ClauseChunker.chunk(text);

        assertEquals(1, chunks.size(), "small clauses fit in one chunk");
        assertEquals("1. Term", chunks.get(0).heading(), "preamble has no heading, the first clause's is used");
        assertTrue(chunks.get(0).text().startsWith("LEASE AGREEMENT"));
        assertTrue(chunks.get(0).text().contains("2. Rent\nRent is $4,000 per month."));
    }

    @Test
    void clausesThatDoNotFitTogetherStaySeparate() {
        String text = "Article 1 Premises\n" + sentences("Premises", 30) + "\n"
            + "Article 2 Rent\n" + sentences("Rent", 30) + "\n";

        List<ClauseChunker.Chunk> chunks = ClauseChunker.chunk(text);

        assertEquals(2, chunks.size());
        assertEquals("Article 1 Premises", chunks.get(0).heading());
        assertEquals("Article 2 Rent", chunks.get(1).heading());
        assertTrue(chunks.get(1).text().startsWith("Article 2 Rent"));
        assertFalse(chunks.get(0).text().contains("Article 2"));
    }

    @Test
    void longClauseIsCutAtSentenceEndsUnderTheCap() {
        String text = "Section 9 Maintenance\n" + sentences("Maintenance", 120);

        List<ClauseChunker.Chunk> chunks = ClauseChunker.chunk(text);

        assertTrue(chunks.size() > 1);
        for (ClauseChunker.Chunk c : chunks) {
            assertEquals("Section 9 Maintenance", c.heading());
            assertTrue(c.text().length() <= ClauseChunker.MAX_CHARS, "chunk over the cap");
            assertTrue(c.text().endsWith("."), "chunk ends mid-sentence: ..." + c.text().substring(c.text().length() - 30));
        }
    }

    @Test
    void koreanArticlesAreSplit() {
        String text = JE + "1" + JO + "\n" + sentences("a", 35) + "\n"
            + JE + "2" + JO + "(" + RENT_KO + ")\n" + sentences("b", 35);

        List<ClauseChunker.Chunk> chunks = ClauseChunker.chunk(text);

        assertEquals(2, chunks.size());
        assertEquals(JE + "2" + JO + "(" + RENT_KO + ")", chunks.get(1).heading());
    }

    @Test
    void textWithoutHeadingsReturnsNothingSoTheCallerFallsBack() {
        assertTrue(ClauseChunker.chunk(sentences("Plain", 50)).isEmpty());
    }

    @Test
    void wordWithNoSpacesIsCutAtTheCap() {
        List<String> pieces = ClauseChunker.cutLong("_".repeat(4000));
        assertEquals(List.of(1800, 1800, 400), pieces.stream().map(String::length).toList());
    }

    @Test
    void headerJoinsTitleAndHeading() {
        assertEquals("Lease 123 Main | Section 7 Rent | ", AiIndexService.header("Lease 123 Main", "Section 7 Rent"));
        assertEquals("Lease 123 Main | ", AiIndexService.header("Lease 123 Main", null));
        assertEquals("", AiIndexService.header(null, " "));
    }
}
