package com.dodaso.ecosystem.ai.config;



import com.dodaso.ecosystem.ai.advisor.TokenLoggingAdvisor;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
//import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;

// Import Spring Boot related classes
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

@Configuration
public class OllamaConfig {
  @Primary
  @Bean
  public OllamaChatModel ollamaChatModel() {

    OllamaOptions defaultOptions = OllamaOptions.builder()
        .model("phi3:3.8b")
        .temperature(0.2)
        .build();

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
            .baseUrl("http://localhost:11434")
            .build(),
        defaultOptions,
        toolCallingManager,
        ObservationRegistry.NOOP,
        new ModelManagementOptions(null,null,null,null)
    );
  }


  @Bean
  public ChatClient ollamaChatClient(OllamaChatModel model) {
    return ChatClient.builder(model)
        .defaultAdvisors(new TokenLoggingAdvisor())
        .build();
  }
}
