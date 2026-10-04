package com.dodaso.ecosystem.ai.service;
import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.container.AiGenerateDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiChatTurnDTO;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.util.VectorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiRagService {
    private final ChatClient ollamaChatClient;
    private final EcwsEmbeddingRepository embeddingRepository;
    private final OllamaService ollamaService;

    public AiGenerateDTOContainer generate(AiGenerateDTOContainer requestBody) {
        AiGenerateDTO generateDTO = requestBody.getAiGenerateDTO();
        if (generateDTO == null || generateDTO.getGeneratePrompt() == null
            || generateDTO.getGeneratePrompt().trim().isEmpty()) {
            throw new IllegalArgumentException("Prompt is empty");
        }
        // project_id = NULL matches no rows, so the answer would have no context at all.
        if (generateDTO.getProjectId() == null) {
            throw new IllegalArgumentException("Project is required");
        }

        AiGenerateDTOContainer responseContainer = new AiGenerateDTOContainer();
        responseContainer.setAiGenerateDTO(retrievalAugmentationAdvisor(generateDTO));
        return responseContainer;
    }

    public AiGenerateDTO retrievalAugmentationAdvisor(AiGenerateDTO generateDTO) {
        String question = generateDTO.getGeneratePrompt();
        boolean useRag = needsRag(question);
        log.info("[Generation Mode] Using {} for question: '{}'", useRag ? "RAG" : "Direct LLM", question);

        String generatedText;
        if (useRag) {
            float[] queryEmbedding = ollamaService.getOllamaEmbedding(question);
            // workspace_id is the content kind (task, comment, file); no caller sends it, and a
            // null one matched no rows. Without it, search the whole project like search() does.
            // List<EcwsEmbedding> chunks = embeddingRepository.findSimilar(
            //     generateDTO.getProjectId(),
            //     generateDTO.getWorkspaceId(),
            //     Arrays.toString(queryEmbedding),
            //     6
            // );
            List<EcwsEmbedding> chunks = generateDTO.getWorkspaceId() != null
                ? embeddingRepository.findSimilar(
                    generateDTO.getProjectId(),
                    generateDTO.getWorkspaceId(),
                    VectorUtil.toString(queryEmbedding),
                    6)
                : embeddingRepository.findSimilarAll(
                    generateDTO.getProjectId(),
                    VectorUtil.toString(queryEmbedding),
                    6);
            log.info("[Generation Mode] {} context chunk(s) for project {}",
                chunks.size(), generateDTO.getProjectId());
            String context = chunks.stream()
                .map(EcwsEmbedding::getChunkText)
                .collect(Collectors.joining("\n\n"));
            String augmentedPrompt = "Context:\n" + context + "\n\nQuestion: " + question;
            generatedText = ollamaChatClient.prompt()
                .user(augmentedPrompt)
                .call()
                .content();
        } else {
            generatedText = ollamaChatClient.prompt()
                .user(question)
                .call()
                .content();
        }

        AiGenerateDTO dto = new AiGenerateDTO();
        dto.setGeneratedText(generatedText);
        return dto;
    }

    static final String NOTHING_FOUND =
        "No matching tasks, comments or files were found, so there is nothing to answer from.";

    private static final String ANSWER_INSTRUCTIONS = """
        You help users with their project tasks. Use only the numbered sources below, which come \
        from the user's tasks, comments and attached files. Cite the sources you use like [1] or \
        [2][3]. If the user asks for a summary or a draft, such as an email, write it from the \
        sources. If the sources don't contain what is needed, say that it wasn't found in the \
        tasks; don't guess or use outside knowledge. Earlier messages in the conversation are only \
        there to understand follow-up questions; answer and cite from the numbered sources.""";

    // Earlier turns sent with a follow-up; older ones are dropped to keep the prompt small
    static final int MAX_HISTORY_TURNS = 3;

    // " [1]" or "[2][3]" in an earlier answer
    private static final Pattern CITATION = Pattern.compile("\\s?(\\[\\d{1,2}\\])+");

    // Search returns at most 10 results and file chunks are at most 1800 chars, so these limits
    // keep every result citable; they only guard against unusually long task or comment text.
    static final int MAX_ANSWER_SOURCES = 10;
    static final int MAX_SOURCE_CHARS = 1800;

    // OpenAI (OpenAiConfig): answers in seconds, where phi3 on CPU took 1.5+ minutes
    @Autowired
    @Qualifier("openAiChatClient")
    private ChatClient openAiChatClient;

    /**
     * Answers a question from search results. Source n in the prompt is sources.get(n - 1), so
     * the [n] citations match the results the caller shows; only the first MAX_ANSWER_SOURCES
     * are sent. No results: no model call.
     */
    public String answer(String question, List<AiSearchResultDTO> sources) {
        return answer(question, sources, null);
    }

    /**
     * Same as answer(question, sources), with the last MAX_HISTORY_TURNS earlier turns sent first
     * as user/assistant messages so a follow-up like "what about last month?" can be understood.
     */
    public String answer(String question, List<AiSearchResultDTO> sources, List<AiChatTurnDTO> history) {
        if (sources == null || sources.isEmpty()) {
            return NOTHING_FOUND;
        }
        return openAiChatClient.prompt()
            .system(ANSWER_INSTRUCTIONS)
            .user(buildAnswerPrompt(question, sources))
            .messages(historyMessages(history))
            .call()
            .content();
    }

    /**
     * Earlier turns as messages, oldest first. Their [n] citations are removed: they numbered that
     * turn's sources, and the current sources reuse the same numbers for other results.
     */
    static List<Message> historyMessages(List<AiChatTurnDTO> history) {
        List<Message> messages = new ArrayList<>();
        if (history == null) {
            return messages;
        }
        List<AiChatTurnDTO> answered = history.stream()
            .filter(turn -> turn != null && !isBlank(turn.getQuestion()) && !isBlank(turn.getAnswer()))
            .collect(Collectors.toList());
        int from = Math.max(0, answered.size() - MAX_HISTORY_TURNS);
        for (AiChatTurnDTO turn : answered.subList(from, answered.size())) {
            messages.add(new UserMessage(turn.getQuestion().trim()));
            messages.add(new AssistantMessage(CITATION.matcher(turn.getAnswer()).replaceAll("").trim()));
        }
        return messages;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    static String buildAnswerPrompt(String question, List<AiSearchResultDTO> sources) {
        StringBuilder prompt = new StringBuilder("Sources:\n");
        for (int i = 0; i < Math.min(sources.size(), MAX_ANSWER_SOURCES); i++) {
            AiSearchResultDTO source = sources.get(i);
            prompt.append('[').append(i + 1).append("] Task #").append(source.getTaskId());
            if (!EmbeddingSourceTypeEnum.COLLABORATION_TASK.getSourceType().equals(source.getSourceType())) {
                prompt.append(", ").append(source.getLabel());
            }
            String text = source.getChunkText() != null ? source.getChunkText() : "";
            if (text.length() > MAX_SOURCE_CHARS) {
                text = text.substring(0, MAX_SOURCE_CHARS) + "...";
            }
            prompt.append('\n').append(text).append("\n\n");
        }
        return prompt.append("Question: ").append(question).toString();
    }

    private boolean needsRag(@SuppressWarnings("unused") String question) {
        return true;
    }
}


