package com.dodaso.ecosystem.ai.service;

import com.dodaso.ecosystem.ai.dto.AiChatConversationDTO;
import com.dodaso.ecosystem.ai.dto.AiChatFileDTO;
import com.dodaso.ecosystem.ai.dto.AiChatMessageDTO;
import com.dodaso.ecosystem.ai.dto.AiChatTurnDTO;
import com.dodaso.ecosystem.ai.dto.AiGeneratedFileDTO;
import com.dodaso.ecosystem.ai.dto.AiSearchResultDTO;
import com.dodaso.ecosystem.ai.entity.AiChatConversation;
import com.dodaso.ecosystem.ai.entity.AiChatMessage;
import com.dodaso.ecosystem.ai.repository.AiChatConversationRepository;
import com.dodaso.ecosystem.ai.repository.AiChatFileRepository;
import com.dodaso.ecosystem.ai.repository.AiChatMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved AI Search chats: one conversation per chat, one message per question and its answer. Every
 * read and write is limited to the chat's owner; another user's chat is treated as not found.
 */
@Service
@Slf4j
public class AiChatHistoryService {

    static final String DEFAULT_APP_CODE = "ECWS";
    static final Set<String> APP_CODES = Set.of("ECWS", "ELCM");
    static final int TITLE_CHARS = 80;
    static final int MAX_TITLE_CHARS = 200;
    static final int DEFAULT_PAGE_SIZE = 30;
    static final int MAX_PAGE_SIZE = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private AiChatConversationRepository conversationRepository;

    @Autowired
    private AiChatMessageRepository messageRepository;

    @Autowired
    private AiChatFileRepository chatFileRepository;

    // Model the answers come from, saved with each message
    @Value("${spring.ai.openai.chat.options.model:}")
    private String model;

    /** A question and answer to save, with what the answer was made from. */
    public record Turn(String question, String answer, String searchText, List<AiSearchResultDTO> sources,
        List<AiChatFileDTO> attachedFiles, List<AiGeneratedFileDTO> generatedFiles, Long latencyMs) {}

    /** Ids of the chat and message a turn was saved as. */
    public record Saved(Long conversationId, Long messageId) {}

    /** The chat, if loginId owns it and it hasn't been deleted. */
    public Optional<AiChatConversation> findOwned(Long conversationId, String loginId) {
        if (conversationId == null || isBlank(loginId)) {
            return Optional.empty();
        }
        return conversationRepository.findOwned(conversationId, loginId.trim());
    }

    /** The chat's last AiRagService.MAX_HISTORY_TURNS questions and answers, oldest first. */
    public List<AiChatTurnDTO> recentTurns(Long conversationId) {
        List<AiChatMessage> newest = messageRepository.findByConversationIdOrderBySeqDesc(conversationId,
            PageRequest.of(0, AiRagService.MAX_HISTORY_TURNS));
        List<AiChatTurnDTO> turns = new ArrayList<>();
        for (AiChatMessage message : newest) {
            turns.add(new AiChatTurnDTO(message.getQuestion(), message.getAnswer()));
        }
        Collections.reverse(turns);
        return turns;
    }

    /**
     * Saves a turn for loginId: in the chat conversationId (which loginId must own), or in a new chat
     * titled from the question when conversationId is null. Files the answer made are tied to the
     * saved message.
     */
    @Transactional
    public Saved save(Long conversationId, String loginId, String appCode, Long projectId, Turn turn) {
        if (isBlank(loginId)) {
            throw new IllegalArgumentException("A saved chat needs an owner");
        }
        Instant now = Instant.now();
        AiChatConversation conversation;
        if (conversationId != null) {
            conversation = conversationRepository.findOwnedForUpdate(conversationId, loginId.trim())
                .orElseThrow(() -> new ChatNotFoundException(conversationId));
        } else {
            conversation = new AiChatConversation();
            conversation.setOwnerLoginId(loginId.trim());
            conversation.setAppCode(appCode(appCode));
            conversation.setProjectId(projectId);
            conversation.setTitle(title(turn.question()));
            conversation.setMessageCount(0);
            conversation.setLastMessageAt(now);
            conversation = conversationRepository.save(conversation);
        }

        AiChatMessage message = new AiChatMessage();
        message.setConversationId(conversation.getId());
        message.setSeq(messageRepository.findMaxSeq(conversation.getId()) + 1);
        message.setQuestion(turn.question());
        message.setAnswer(turn.answer());
        message.setSearchText(turn.searchText());
        message.setSources(sourcesJson(turn.sources()));
        message.setAttachedFiles(attachedFilesJson(turn.attachedFiles()));
        message.setModel(isBlank(model) ? null : model);
        message.setLatencyMs(turn.latencyMs());
        message = messageRepository.save(message);

        conversation.setMessageCount(conversation.getMessageCount() + 1);
        conversation.setLastMessageAt(now);
        conversationRepository.save(conversation);

        List<UUID> fileIds = turn.generatedFiles() == null ? List.of() : turn.generatedFiles().stream()
            .map(AiGeneratedFileDTO::getId).filter(id -> id != null).toList();
        if (!fileIds.isEmpty()) {
            chatFileRepository.linkToMessage(fileIds, conversation.getId(), message.getId());
        }
        return new Saved(conversation.getId(), message.getId());
    }

    /** loginId's open chats in appCode, newest first; page is 0-based, size defaults to DEFAULT_PAGE_SIZE. */
    public List<AiChatConversationDTO> list(String loginId, String appCode, int page, int size) {
        if (isBlank(loginId)) {
            return List.of();
        }
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return conversationRepository.findRecent(loginId.trim(), appCode(appCode),
                PageRequest.of(Math.max(0, page), pageSize))
            .stream().map(AiChatHistoryService::toDTO).collect(Collectors.toList());
    }

    /** The chat's messages in order, with their sources and the files that can still be downloaded. */
    public Optional<List<AiChatMessageDTO>> messages(Long conversationId, String loginId) {
        Optional<AiChatConversation> conversation = findOwned(conversationId, loginId);
        if (conversation.isEmpty()) {
            return Optional.empty();
        }
        List<AiChatMessage> messages = messageRepository.findByConversationIdOrderBySeqAsc(conversationId);
        Map<Long, List<AiGeneratedFileDTO>> filesByMessage = new LinkedHashMap<>();
        if (!messages.isEmpty()) {
            for (AiChatFileRepository.FileInfo file : chatFileRepository.findInfoByMessageIds(
                    messages.stream().map(AiChatMessage::getId).toList(), Instant.now())) {
                filesByMessage.computeIfAbsent(file.getMessageId(), id -> new ArrayList<>())
                    .add(AiGeneratedFileDTO.builder()
                        .id(file.getId())
                        .fileName(file.getFileName())
                        .contentType(file.getContentType())
                        .sizeBytes(file.getSizeBytes())
                        .expiresAt(file.getExpiresAt())
                        .build());
            }
        }
        List<AiChatMessageDTO> result = new ArrayList<>();
        for (AiChatMessage message : messages) {
            result.add(AiChatMessageDTO.builder()
                .id(message.getId())
                .seq(message.getSeq())
                .question(message.getQuestion())
                .answer(message.getAnswer())
                .sources(readSources(message.getSources()))
                .fileNames(readFileNames(message.getAttachedFiles()))
                .generatedFiles(filesByMessage.getOrDefault(message.getId(), new ArrayList<>()))
                .createdAt(message.getCreatedAt())
                .build());
        }
        return Optional.of(result);
    }

    /** The chat as the Recent chats list shows it, if loginId owns it. */
    public Optional<AiChatConversationDTO> conversation(Long conversationId, String loginId) {
        return findOwned(conversationId, loginId).map(AiChatHistoryService::toDTO);
    }

    /** Renames loginId's chat; a blank title is refused, a long one cut to MAX_TITLE_CHARS. */
    @Transactional
    public Optional<AiChatConversationDTO> rename(Long conversationId, String loginId, String title) {
        String clean = title != null ? title.replaceAll("\\s+", " ").trim() : "";
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Title is empty");
        }
        Optional<AiChatConversation> conversation = findOwned(conversationId, loginId);
        conversation.ifPresent(c -> {
            c.setTitle(cut(clean, MAX_TITLE_CHARS));
            conversationRepository.save(c);
        });
        return conversation.map(AiChatHistoryService::toDTO);
    }

    /** Soft-deletes loginId's chat; false when it isn't theirs or is already deleted. */
    @Transactional
    public boolean delete(Long conversationId, String loginId) {
        Optional<AiChatConversation> conversation = findOwned(conversationId, loginId);
        conversation.ifPresent(c -> {
            c.setActiveInd("N");
            conversationRepository.save(c);
        });
        return conversation.isPresent();
    }

    /** The first question on one line, cut to TITLE_CHARS. */
    static String title(String question) {
        String clean = question != null ? question.replaceAll("\\s+", " ").trim() : "";
        if (clean.isEmpty()) {
            return "New chat";
        }
        return clean.length() <= TITLE_CHARS ? clean : clean.substring(0, TITLE_CHARS - 3).trim() + "...";
    }

    /**
     * The sources as saved: their number, task and source ids and label, without chunk text, which can
     * be large or out of date (a citation opens the live task).
     */
    static String sourcesJson(List<AiSearchResultDTO> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            AiSearchResultDTO source = sources.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("n", i + 1);
            row.put("taskId", source.getTaskId());
            row.put("sourceType", source.getSourceType());
            row.put("sourceId", source.getSourceId());
            row.put("parentSourceId", source.getParentSourceId());
            row.put("label", source.getLabel());
            rows.add(row);
        }
        return write(rows);
    }

    /** The attached files as saved: names and sizes only. */
    static String attachedFilesJson(List<AiChatFileDTO> files) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiChatFileDTO file : files) {
            if (file == null || file.getFileName() == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fileName", file.getFileName());
            row.put("size", file.getContent() != null ? file.getContent().length : null);
            rows.add(row);
        }
        return rows.isEmpty() ? null : write(rows);
    }

    static List<AiSearchResultDTO> readSources(String json) {
        if (isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<AiSearchResultDTO>>() {});
        } catch (Exception e) {
            log.warn("Could not read saved chat sources: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    static List<String> readFileNames(String json) {
        if (isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> rows = MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return rows.stream().map(row -> row.get("fileName")).filter(name -> name != null)
                .map(Object::toString).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Could not read saved chat file names: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write chat JSON", e);
        }
    }

    private static AiChatConversationDTO toDTO(AiChatConversation conversation) {
        return AiChatConversationDTO.builder()
            .id(conversation.getId())
            .appCode(conversation.getAppCode())
            .title(conversation.getTitle())
            .messageCount(conversation.getMessageCount())
            .lastMessageAt(conversation.getLastMessageAt())
            .build();
    }

    /** 'ECWS' when blank; anything other than 'ECWS' or 'ELCM' is refused. */
    static String appCode(String appCode) {
        String code = isBlank(appCode) ? DEFAULT_APP_CODE : appCode.trim().toUpperCase(Locale.ROOT);
        if (!APP_CODES.contains(code)) {
            throw new IllegalArgumentException("Unknown app code: " + appCode);
        }
        return code;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** The chat doesn't exist, was deleted, or belongs to another user. */
    public static class ChatNotFoundException extends RuntimeException {
        public ChatNotFoundException(Long conversationId) {
            super("Chat " + conversationId + " not found");
        }
    }
}
