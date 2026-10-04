package com.dodaso.ecosystem.ai.config;

import com.dodaso.ecosystem.ai.advisor.TokenLoggingAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chat model for AI search answers (OpenAI). Embeddings stay on Ollama.
 *
 * <p>Built here instead of through spring-ai-starter-model-openai: the starter would also
 * auto-configure OpenAI embedding, image and audio models next to the Ollama ones.
 *
 * <p>The bean is not a default candidate, so plain {@code ChatClient} injection points keep
 * getting {@code ollamaChatClient}; use {@code @Qualifier("openAiChatClient")}.
 *
 * <p>Without an API key the service still starts (search doesn't need it); answers then fail
 * with a message saying the key is missing.
 */
@Slf4j
@Configuration
public class OpenAiConfig {

  @Bean(defaultCandidate = false)
  public ChatClient openAiChatClient(
      @Value("${spring.ai.openai.api-key:}") String apiKey,
      @Value("${spring.ai.openai.chat.options.model}") String model,
      @Value("${spring.ai.openai.chat.options.reasoning-effort}") String reasoningEffort,
      @Value("${spring.ai.openai.chat.options.max-completion-tokens}") int maxCompletionTokens) {
    ChatModel chatModel;
    if (apiKey == null || apiKey.isBlank()) {
      log.warn("spring.ai.openai.api-key is not set: AI search answers are unavailable");
      chatModel = prompt -> {
        throw new IllegalStateException("OpenAI API key is not set (OPENAI_API_KEY / ai-openai-api-key)");
      };
    } else {
      chatModel = OpenAiChatModel.builder()
          .openAiApi(OpenAiApi.builder().apiKey(apiKey).build())
          .defaultOptions(OpenAiChatOptions.builder()
              .model(model)
              .reasoningEffort(reasoningEffort)
              .maxCompletionTokens(maxCompletionTokens)
              .build())
          .build();
    }
    return ChatClient.builder(chatModel)
        .defaultAdvisors(new TokenLoggingAdvisor())
        .build();
  }
}
