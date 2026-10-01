package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingSyncRepository;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * chunkText caps chunks by characters as well as words, so a chunk of form filler
 * ("_____") stays under nomic-embed-text's 2048-token limit.
 */
class AiFileSyncServiceChunkTest {

    private static final int MAX_CHARS = 1800;

    private AiFileSyncService service;

    @BeforeEach
    void setUp() {
        service = new AiFileSyncService(mock(FileTextExtractorService.class), mock(AiSyncService.class),
            mock(EcwsEmbeddingSyncRepository.class), mock(EcwsEmbeddingRepository.class),
            mock(RESTServiceClient.class));
    }

    private static String repeat(String word, int times) {
        return String.join(" ", Collections.nCopies(times, word));
    }

    @Test
    void shortWordsStillChunkBy500Words() {
        // 500 x "ok" = 1499 chars, under the cap, so the word limit decides
        List<String> chunks = service.chunkText(repeat("ok", 1200), 500);

        assertEquals(3, chunks.size());
        assertEquals(500, chunks.get(0).split(" ").length);
        assertEquals(500, chunks.get(1).split(" ").length);
        assertEquals(200, chunks.get(2).split(" ").length);
    }

    @Test
    void formFillerIsCappedByCharacters() {
        String text = repeat("Name:______________________________", 500);

        List<String> chunks = service.chunkText(text, 500);

        assertTrue(chunks.size() > 1);
        chunks.forEach(c -> assertTrue(c.length() <= MAX_CHARS, "chunk length " + c.length()));
        assertEquals(text, String.join(" ", chunks));
    }

    @Test
    void normalProseIsCappedByCharacters() {
        List<String> chunks = service.chunkText(repeat("agreement", 500), 500);

        assertEquals(3, chunks.size());
        chunks.forEach(c -> assertTrue(c.length() <= MAX_CHARS, "chunk length " + c.length()));
    }

    @Test
    void wordLongerThanCapIsSplit() {
        String text = "Signature " + "_".repeat(5000) + " Date";

        List<String> chunks = service.chunkText(text, 500);

        chunks.forEach(c -> assertTrue(c.length() <= MAX_CHARS, "chunk length " + c.length()));
        assertEquals(text.replace(" ", ""), String.join("", chunks).replace(" ", ""));
    }

    @Test
    void emptyTextGivesNoChunks() {
        assertTrue(service.chunkText("", 500).isEmpty());
    }
}
