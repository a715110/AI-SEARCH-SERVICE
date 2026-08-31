package com.dodaso.ecosystem.ai.service;
import com.dodaso.ecosystem.ai.container.AiGenerateDTOContainer;
import com.dodaso.ecosystem.ai.dto.AiGenerateDTO;
import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
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
            List<EcwsEmbedding> chunks = embeddingRepository.findSimilar(
                generateDTO.getProjectId(),
                generateDTO.getWorkspaceId(),
                Arrays.toString(queryEmbedding),
                6
            );
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

    private boolean needsRag(@SuppressWarnings("unused") String question) {
        return true;
    }
}


