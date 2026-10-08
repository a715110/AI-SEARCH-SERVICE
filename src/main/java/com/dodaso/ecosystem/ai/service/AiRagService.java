package com.dodaso.ecosystem.ai.service;
import com.dodaso.ecosystem.ai.constant.EmbeddingSourceTypeEnum;
import com.dodaso.ecosystem.ai.container.AiGenerateDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiAlertDTO;
import com.dodaso.ecosystem.ai.dto.AiChatTurnDTO;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.util.VectorUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
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

    private static final String ALERT_INSTRUCTIONS = """
        An "Alerts and reminders" list may follow the sources; it comes from the notification \
        system. Use it for questions about due dates, overdue items, deadlines and reminders, \
        naming the task (for example Task #12) instead of a [n] citation. Alerts are not \
        sources: never cite them with [n]. Each alert says who it was sent to: only alerts \
        "sent to you" are the user's; call the others alerts sent to that person.""";

    // Hangul syllables, Jamo and compatibility Jamo
    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]");

    /**
     * Tells the model which language to answer in: Korean when the question has Hangul, English
     * otherwise. Decided here because the model, asked to match the question, often follows the
     * sources instead and answers an English question in Korean when the tasks are in Korean.
     */
    static String languageInstructions(String question) {
        String language = question != null && HANGUL.matcher(question).find() ? "Korean" : "English";
        return "Write your answer in " + language + ", because the user's question is in " + language
            + ". The language of the sources, alerts, attached files and earlier messages doesn't matter.";
    }

    private static final String NO_ACTIONS_INSTRUCTIONS = """
        You can only answer and write text. Don't offer to take actions such as marking alerts \
        as read, approving, opening files or sending messages.""";

    // Alerts listed in the prompt: the user's past-due and newest ones, and the source tasks'
    // (EarsAlertService.MAX_ALERTS each)
    // static final int MAX_ANSWER_ALERTS = 2 * EarsAlertService.MAX_ALERTS;
    static final int MAX_ANSWER_ALERTS = 3 * EarsAlertService.MAX_ALERTS;

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
        return answer(question, sources, history, null);
    }

    /**
     * Same as answer(question, sources, history), with EARS alerts and reminders listed after the
     * sources. With alerts but no sources the model is still called, so "what's overdue for me?"
     * can be answered from the alerts alone.
     */
    public String answer(String question, List<AiSearchResultDTO> sources, List<AiChatTurnDTO> history,
        List<AiAlertDTO> alerts) {
        return answer(question, sources, history, alerts, null);
    }

    /**
     * Same as answer(question, sources, history, alerts); alerts sent to loginId are shown as
     * "sent to you", the others with their recipient's login id.
     */
    public String answer(String question, List<AiSearchResultDTO> sources, List<AiChatTurnDTO> history,
        List<AiAlertDTO> alerts, String loginId) {
        return answer(question, sources, history, alerts, loginId, null);
    }

    /**
     * Same as answer(question, sources, history, alerts, loginId), with the text of the files the
     * user attached to the question listed after the alerts. With attached files but no sources
     * or alerts the model is still called, so "summarize this file" can be answered.
     */
    public String answer(String question, List<AiSearchResultDTO> sources, List<AiChatTurnDTO> history,
        List<AiAlertDTO> alerts, String loginId, List<AttachedFile> files) {
        // if (sources == null || sources.isEmpty()) {
        // if ((sources == null || sources.isEmpty()) && (alerts == null || alerts.isEmpty())) {
        if ((sources == null || sources.isEmpty()) && (alerts == null || alerts.isEmpty())
            && (files == null || files.isEmpty())) {
            return NOTHING_FOUND;
        }
        // String system = ANSWER_INSTRUCTIONS + " " + ALERT_INSTRUCTIONS + " " + NO_ACTIONS_INSTRUCTIONS;
        String system = ANSWER_INSTRUCTIONS + " " + ALERT_INSTRUCTIONS + " " + NO_ACTIONS_INSTRUCTIONS
            + " " + languageInstructions(question);
        if (files != null && !files.isEmpty()) {
            system += " " + FILE_INSTRUCTIONS;
        }
        return openAiChatClient.prompt()
            // .system(ANSWER_INSTRUCTIONS)
            // .system(ANSWER_INSTRUCTIONS + " " + ALERT_INSTRUCTIONS)
            // .system(ANSWER_INSTRUCTIONS + " " + ALERT_INSTRUCTIONS + " " + NO_ACTIONS_INSTRUCTIONS)
            .system(system)
            // .user(buildAnswerPrompt(question, sources))
            // .user(buildAnswerPrompt(question, sources, alerts, LocalDate.now()))
            // .user(buildAnswerPrompt(question, sources, alerts, LocalDate.now(), loginId))
            .user(buildAnswerPrompt(question, sources, alerts, LocalDate.now(), loginId, files))
            .messages(historyMessages(history))
            .call()
            .content();
    }

    /** An answer's chat text, and the file the user asked for (null when none). */
    public record AiAnswer(String text, AiAnswerSpec.FileSpec file) {}

    /**
     * Same as answer(question, sources, history, alerts, loginId, files), but the model may also
     * return a Word or Excel document spec when the user asks for a file. The model replies with
     * AiAnswerSpec JSON; a reply that isn't valid JSON is used as the text, with no file. Without
     * a loginId no file can be owned, so the plain text answer is used.
     */
    public AiAnswer answerWithFile(String question, List<AiSearchResultDTO> sources,
        List<AiChatTurnDTO> history, List<AiAlertDTO> alerts, String loginId, List<AttachedFile> files) {
        if (isBlank(loginId)) {
            return new AiAnswer(answer(question, sources, history, alerts, loginId, files), null);
        }
        if ((sources == null || sources.isEmpty()) && (alerts == null || alerts.isEmpty())
            && (files == null || files.isEmpty())) {
            return new AiAnswer(NOTHING_FOUND, null);
        }
        // String system = ANSWER_INSTRUCTIONS + " " + ALERT_INSTRUCTIONS + " " + NO_ACTIONS_INSTRUCTIONS;
        String system = ANSWER_INSTRUCTIONS + " " + ALERT_INSTRUCTIONS + " " + NO_ACTIONS_INSTRUCTIONS
            + " " + languageInstructions(question);
        if (files != null && !files.isEmpty()) {
            system += " " + FILE_INSTRUCTIONS;
        }
        system += " " + FILE_OUTPUT_INSTRUCTIONS;
        String reply = openAiChatClient.prompt()
            .system(system)
            .user(buildAnswerPrompt(question, sources, alerts, LocalDate.now(), loginId, files))
            .messages(historyMessages(history))
            .options(fileAnswerOptions())
            .call()
            .content();
        return parseAnswer(reply);
    }

    private static final String FILE_OUTPUT_INSTRUCTIONS = """
        Reply as JSON with "answer" and "file". "answer" is your reply in the chat. Set "file" only \
        when the user asks for a file or document, such as a Word document or an Excel sheet (for \
        example "as a Word file", "워드로 만들어줘", "엑셀로 정리해줘"); otherwise "file" is null. \
        Formats: DOCX for documents, summaries and reports; XLSX for lists and tables. Put the \
        content in the file's blocks and keep "answer" to 1-3 sentences saying what the file \
        contains. An XLSX file needs at least one table block; each table \
        becomes a sheet. Use only what is in the sources, alerts and attached files, and never \
        invent rows that aren't there. Keep a table to at most 500 rows; if there is more, say in \
        the answer that the file has the first 500. "fileName" is a short name without extension. \
        You can't make other formats (PDF, PowerPoint, images); say so and set "file" to null.""";

    // A file's content is in the reply, so it can be much longer than a plain answer
    static final int FILE_ANSWER_MAX_TOKENS = 16000;

    // Strict structured output: OpenAI replies with JSON that matches AiAnswerSpec.JSON_SCHEMA
    private static OpenAiChatOptions fileAnswerOptions() {
        return OpenAiChatOptions.builder()
            .responseFormat(ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder()
                    .name("answer_with_file")
                    .schema(AiAnswerSpec.JSON_SCHEMA)
                    .strict(true)
                    .build())
                .build())
            .maxCompletionTokens(FILE_ANSWER_MAX_TOKENS)
            .build();
    }

    private static final ObjectMapper ANSWER_MAPPER = new ObjectMapper();

    /** The reply as AiAnswerSpec; a reply that isn't valid JSON, or has no answer, is the text. */
    static AiAnswer parseAnswer(String reply) {
        if (reply == null) {
            return new AiAnswer(null, null);
        }
        try {
            AiAnswerSpec spec = ANSWER_MAPPER.readValue(reply, AiAnswerSpec.class);
            if (spec == null || spec.answer() == null) {
                log.warn("Answer JSON has no answer; showing the reply as text");
                return new AiAnswer(reply, null);
            }
            return new AiAnswer(spec.answer(), spec.file());
        } catch (Exception e) {
            log.warn("Answer is not valid JSON ({}); showing it as text without a file", e.getMessage());
            return new AiAnswer(reply, null);
        }
    }

    /** A file the user attached to the question; text is null when it couldn't be read. */
    public record AttachedFile(String fileName, String text) {}

    private static final String FILE_INSTRUCTIONS = """
        An "Attached files" list may follow; these are files the user attached to this question. \
        Use them like sources, but name the file (for example "report.pdf") instead of a [n] \
        citation. If a file couldn't be read, say so.""";

    // Text kept per attached file, so a few large files still fit the prompt
    static final int MAX_FILE_CHARS = 20000;

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
        return buildAnswerPrompt(question, sources, null, null);
    }

    static String buildAnswerPrompt(String question, List<AiSearchResultDTO> sources,
        List<AiAlertDTO> alerts, LocalDate today) {
        return buildAnswerPrompt(question, sources, alerts, today, null);
    }

    /**
     * The numbered sources, then the alerts (unnumbered, so [n] still means sources.get(n - 1))
     * with today's date so the model can tell what is overdue, then the question.
     */
    static String buildAnswerPrompt(String question, List<AiSearchResultDTO> sources,
        List<AiAlertDTO> alerts, LocalDate today, String loginId) {
        return buildAnswerPrompt(question, sources, alerts, today, loginId, null);
    }

    /**
     * Same as buildAnswerPrompt(question, sources, alerts, today, loginId), with the attached
     * files' text (unnumbered, cut at MAX_FILE_CHARS) after the alerts.
     */
    static String buildAnswerPrompt(String question, List<AiSearchResultDTO> sources,
        List<AiAlertDTO> alerts, LocalDate today, String loginId, List<AttachedFile> files) {
        if (sources == null) {
            sources = List.of();
        }
        StringBuilder prompt = new StringBuilder("Sources:\n");
        if (sources.isEmpty()) {
            prompt.append("(none)\n\n");
        }
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
        if (alerts != null && !alerts.isEmpty()) {
            prompt.append("Alerts and reminders");
            if (today != null) {
                prompt.append(" (today is ").append(today).append(')');
            }
            prompt.append(":\n");
            for (AiAlertDTO alert : alerts.subList(0, Math.min(alerts.size(), MAX_ANSWER_ALERTS))) {
                // prompt.append("- ").append(alertLine(alert)).append('\n');
                prompt.append("- ").append(alertLine(alert, loginId)).append('\n');
            }
            prompt.append('\n');
        }
        if (files != null && !files.isEmpty()) {
            prompt.append("Attached files:\n");
            for (AttachedFile file : files) {
                prompt.append("--- ").append(file.fileName()).append(" ---\n");
                String text = file.text();
                if (text == null || text.isBlank()) {
                    prompt.append("(this file couldn't be read)\n\n");
                    continue;
                }
                text = text.trim();
                if (text.length() > MAX_FILE_CHARS) {
                    text = text.substring(0, MAX_FILE_CHARS) + "...";
                }
                prompt.append(text).append("\n\n");
            }
        }
        return prompt.append("Question: ").append(question).toString();
    }

    // "Task #12, High, sent 2026-10-01 to you, unread: Task past due - The task is 3 days overdue."
    // private static String alertLine(AiAlertDTO alert) {
    private static String alertLine(AiAlertDTO alert, String loginId) {
        StringBuilder line = new StringBuilder();
        if (EarsAlertService.TASK_TABLE.equals(alert.getSourceReferenceTable())) {
            line.append("Task #").append(alert.getSourceReferenceId());
        } else if (alert.getSourceReferenceTable() != null) {
            line.append(alert.getSourceReferenceTable()).append(" #").append(alert.getSourceReferenceId());
        } else {
            line.append("General");
        }
        if (alert.getPriority() != null) {
            line.append(", ").append(alert.getPriority());
        }
        if (alert.getCreatedAt() != null) {
            line.append(", sent ").append(alert.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate());
        }
        if (alert.getRecipientValue() != null) {
            boolean toUser = loginId != null
                && alert.getRecipientValue().trim().equalsIgnoreCase(loginId.trim());
            line.append(alert.getCreatedAt() != null ? " to " : ", sent to ")
                .append(toUser ? "you" : alert.getRecipientValue());
        }
        if (alert.getReadInd() != null) {
            line.append(alert.getReadInd() == 1 ? ", read" : ", unread");
        }
        line.append(": ").append(alert.getSubject() != null ? alert.getSubject() : "");
        if (alert.getBody() != null && !alert.getBody().isBlank()) {
            line.append(" - ").append(alert.getBody().trim().replaceAll("\\s+", " "));
        }
        return line.toString();
    }

    private boolean needsRag(@SuppressWarnings("unused") String question) {
        return true;
    }
}


