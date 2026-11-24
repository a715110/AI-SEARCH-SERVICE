package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.dto.AiCatchUpDTO;
import com.dodaso.ecosystem.ai.dto.AiCatchUpItemDTO;
import com.dodaso.ecosystem.ai.dto.AiCatchUpPointDTO;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * ADDED 2026-11-23 (task catch-up). Summarizes the comments and events an app service sends, with
 * a citation on every statement. Uses the same chat client as the AI search answers, so where the
 * model runs (OpenAI, or an OpenAI-compatible server inside the instance) is set by
 * spring.ai.openai.base-url. A long thread is summarized in chunks and the chunks are joined.
 * Nothing is saved here.
 */
@Slf4j
@Service
public class AiCatchUpService {

  @Autowired
  @Qualifier("openAiChatClient")
  private ChatClient openAiChatClient;

  @Value("${dodaso.ai.catchup.chunk-chars:12000}")
  private int chunkChars;

  @Value("${dodaso.ai.catchup.max-items:300}")
  private int maxItems;

  public AiCatchUpDTO catchUp(AiCatchUpDTO request) {
    AiCatchUpDTO response = new AiCatchUpDTO();
    response.setTaskContext(request.getTaskContext());
    response.setSince(request.getSince());
    List<AiCatchUpItemDTO> items = request.getItems() == null ? List.of() : request.getItems();
    if (items.isEmpty()) {
      return response;
    }
    // Newest items win when a thread is longer than the cap; the order stays oldest first
    if (items.size() > maxItems) {
      log.info("Catch-up input cut from {} to the newest {} items", items.size(), maxItems);
      items = items.subList(items.size() - maxItems, items.size());
    }
    Set<String> validRefs = new HashSet<>();
    for (AiCatchUpItemDTO item : items) {
      validRefs.add(item.getRef());
    }

    long start = System.currentTimeMillis();
    List<List<AiCatchUpItemDTO>> chunks = CatchUpLogic.chunk(items, chunkChars);
    List<AiCatchUpPointDTO> collected = new ArrayList<>();
    int dropped = 0;
    for (List<AiCatchUpItemDTO> chunk : chunks) {
      String reply = ask(CatchUpLogic.buildPrompt(request.getTaskContext(), request.getSince(), chunk));
      CatchUpLogic.Parsed parsed = CatchUpLogic.parse(reply, validRefs);
      collected.addAll(parsed.points());
      dropped += parsed.dropped();
    }
    if (chunks.size() > 1 && collected.size() > CatchUpLogic.MAX_POINTS) {
      String reply = ask(CatchUpLogic.buildMergePrompt(request.getTaskContext(), collected));
      CatchUpLogic.Parsed merged = CatchUpLogic.parse(reply, validRefs);
      if (!merged.points().isEmpty()) {
        collected = new ArrayList<>(merged.points());
        dropped += merged.dropped();
      } else {
        // Keep the unmerged statements rather than lose the summary
        collected = new ArrayList<>(collected.subList(0, CatchUpLogic.MAX_POINTS));
      }
    }
    response.setPoints(collected);
    response.setDroppedPoints(dropped);
    log.info("Catch-up: {} item(s) in {} chunk(s), {} point(s), {} dropped, {} ms",
        items.size(), chunks.size(), collected.size(), dropped, System.currentTimeMillis() - start);
    return response;
  }

  private String ask(String prompt) {
    return openAiChatClient.prompt().user(prompt).call().content();
  }
}
