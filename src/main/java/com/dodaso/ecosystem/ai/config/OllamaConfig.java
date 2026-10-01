package com.dodaso.ecosystem.ai.config;

import com.dodaso.ecosystem.ai.advisor.TokenLoggingAdvisor;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Chat model used for RAG answers.
 *
 * <p><b>STEP1:</b> the Ollama address, chat model and temperature were hard-coded
 * ({@code http://localhost:11434}, {@code phi3:3.8b}, {@code 0.2}). This bean replaces
 * Spring AI's auto-configured chat model, so the {@code spring.ai.ollama.*} properties were
 * silently ignored: in a customer instance, where Ollama runs on its own host, every chat
 * call would have gone to localhost and failed. The values now come from the same
 * properties the auto-configuration uses:
 * <ul>
 *   <li>{@code spring.ai.ollama.base-url} (base: {@code OLLAMA_BASE_URL}; local: http://localhost:11434)</li>
 *   <li>{@code spring.ai.ollama.chat.options.model}</li>
 *   <li>{@code spring.ai.ollama.chat.options.temperature}</li>
 * </ul>
 * A missing value fails at startup (no defaults here on purpose).
 */
@Configuration
public class OllamaConfig {

  @Primary
  @Bean
  public OllamaChatModel ollamaChatModel(
      @Value("${spring.ai.ollama.base-url}") String ollamaBaseUrl,
      @Value("${spring.ai.ollama.chat.options.model}") String chatModel,
      @Value("${spring.ai.ollama.chat.options.temperature}") double temperature) {

    OllamaOptions defaultOptions = OllamaOptions.builder()
        .model(chatModel)
        .temperature(temperature)
        .build();

    // Tool calling is not used: no tools are resolved or executed.
    ToolCallingManager toolCallingManager = new ToolCallingManager() {
      @Override
      public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return List.of();
      }

      @Override
      public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        return null;
      }
    };

    return new OllamaChatModel(
        OllamaApi.builder()
            .baseUrl(ollamaBaseUrl)
            .build(),
        defaultOptions,
        toolCallingManager,
        ObservationRegistry.NOOP,
        new ModelManagementOptions(null, null, null, null)
    );
  }

  @Bean
  public ChatClient ollamaChatClient(OllamaChatModel model) {
    return ChatClient.builder(model)
        .defaultAdvisors(new TokenLoggingAdvisor())
        .build();
  }
}
