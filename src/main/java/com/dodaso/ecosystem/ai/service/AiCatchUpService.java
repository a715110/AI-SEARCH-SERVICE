package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.dto.AiCatchUpActionDTO;
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

  /** ADDED 2026-11-27 (audit trail): the model name returned with every answer. */
  @Value("${spring.ai.openai.chat.options.model:unknown}")
  private String modelName;

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
    boolean wantActions = request.isWantActions();
    List<AiCatchUpActionDTO> actions = new ArrayList<>();
    int droppedActions = 0;
    StringBuilder sent = new StringBuilder();
    for (List<AiCatchUpItemDTO> chunk : chunks) {
      String prompt = CatchUpLogic.buildPrompt(request.getTaskContext(), request.getSince(), chunk,
          wantActions, request.getCandidateOwners(), request.getExistingSubTasks());
      remember(sent, prompt);
      String reply = ask(prompt);
      CatchUpLogic.Parsed parsed = CatchUpLogic.parse(reply, validRefs,
          wantActions ? request.getCandidateOwners() : null);
      collected.addAll(parsed.points());
      dropped += parsed.dropped();
      actions.addAll(parsed.actions());
      droppedActions += parsed.droppedActions();
    }
    if (chunks.size() > 1 && collected.size() > CatchUpLogic.MAX_POINTS) {
      String mergePrompt = CatchUpLogic.buildMergePrompt(request.getTaskContext(), collected);
      remember(sent, mergePrompt);
      String reply = ask(mergePrompt);
      CatchUpLogic.Parsed merged = CatchUpLogic.parse(reply, validRefs);
      if (!merged.points().isEmpty()) {
        collected = new ArrayList<>(merged.points());
        dropped += merged.dropped();
      } else {
        // Keep the unmerged statements rather than lose the summary
        collected = new ArrayList<>(collected.subList(0, CatchUpLogic.MAX_POINTS));
      }
    }
    response.setModel(modelName);
    response.setPromptSent(sent.toString());
    response.setPoints(collected);
    response.setDroppedPoints(dropped);
    if (wantActions) {
      response.setActions(CatchUpLogic.mergeActions(actions, request.getExistingSubTasks()));
      response.setDroppedActions(droppedActions);
    }
    log.info("Catch-up: {} item(s) in {} chunk(s), {} point(s), {} dropped, {} action(s), {} ms",
        items.size(), chunks.size(), collected.size(), dropped, response.getActions().size(),
        System.currentTimeMillis() - start);
    return response;
  }

  /**
   * ADDED 2026-11-27 (draft reply). A reply to the comment thread for the person to edit and post.
   * Only the newest comments that fit are sent. The refs the model gives are checked against the
   * ones sent; nothing is saved here.
   */
  public AiCatchUpDTO draftReply(AiCatchUpDTO request) {
    AiCatchUpDTO response = new AiCatchUpDTO();
    response.setTaskContext(request.getTaskContext());
    List<AiCatchUpItemDTO> items = new ArrayList<>();
    if (request.getItems() != null) {
      for (AiCatchUpItemDTO item : request.getItems()) {
        if ("comment".equals(item.getKind())) {
          items.add(item);
        }
      }
    }
    if (items.isEmpty()) {
      return response;
    }
    List<AiCatchUpItemDTO> sent = DraftReplyLogic.newestWithin(items, Math.min(chunkChars, 8000));
    Set<String> validRefs = new HashSet<>();
    for (AiCatchUpItemDTO item : sent) {
      validRefs.add(item.getRef());
    }
    long start = System.currentTimeMillis();
    String draftPrompt = DraftReplyLogic.buildPrompt(request.getTaskContext(),
        request.getDrafterName(), request.getDraftInstruction(), sent);
    response.setModel(modelName);
    response.setPromptSent(draftPrompt);
    String reply = ask(draftPrompt);
    DraftReplyLogic.Draft draft = DraftReplyLogic.parse(reply, validRefs);
    if (draft != null) {
      response.setDraft(draft.text());
      response.setDraftRefs(draft.refs());
    }
    log.info("Draft reply: {} comment(s) sent, draft {}, {} ref(s), {} ms", sent.size(),
        draft == null ? "none" : "made", draft == null ? 0 : draft.refs().size(),
        System.currentTimeMillis() - start);
    return response;
  }

  /** Keeps the prompts of one answer for the audit trail, separated by a visible line. */
  private static void remember(StringBuilder sent, String prompt) {
    if (sent.length() > 0) {
      sent.append("\n\n===== next prompt =====\n\n");
    }
    sent.append(prompt);
  }

  private String ask(String prompt) {
    return openAiChatClient.prompt().user(prompt).call().content();
  }
}
