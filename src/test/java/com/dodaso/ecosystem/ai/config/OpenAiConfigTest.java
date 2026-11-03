package com.dodaso.ecosystem.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.dodaso.ecosystem.ai.repository.EcwsEmbeddingRepository;
import com.dodaso.ecosystem.ai.service.AiRagService;
import com.dodaso.ecosystem.ai.service.OllamaService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

/** Which ChatClient goes where once both the Ollama and the OpenAI one exist. */
class OpenAiConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(OllamaConfig.class, OpenAiConfig.class, AiRagService.class)
        .withBean(EcwsEmbeddingRepository.class, () -> mock(EcwsEmbeddingRepository.class))
        .withBean(OllamaService.class, () -> mock(OllamaService.class))
        .withPropertyValues(
            "spring.ai.ollama.base-url=http://localhost:11434",
            "spring.ai.ollama.chat.options.model=phi3:3.8b",
            "spring.ai.ollama.chat.options.temperature=0.2",
            "spring.ai.openai.chat.options.model=gpt-5-mini",
            "spring.ai.openai.chat.options.reasoning-effort=low",
            "spring.ai.openai.chat.options.max-completion-tokens=2000");

    @Test
    void plainChatClientStaysOllamaAndAnswersGetOpenAi() {
        runner.withPropertyValues("spring.ai.openai.api-key=test-key").run(context -> {
            assertTrue(context.getStartupFailure() == null, () -> "" + context.getStartupFailure());
            ChatClient ollama = context.getBean("ollamaChatClient", ChatClient.class);
            ChatClient openAi = context.getBean("openAiChatClient", ChatClient.class);
            assertNotSame(ollama, openAi);
            // A plain ChatClient lookup must not become ambiguous
            assertSame(ollama, context.getBean(ChatClient.class));

            AiRagService rag = context.getBean(AiRagService.class);
            assertSame(ollama, ReflectionTestUtils.getField(rag, "ollamaChatClient"));
            assertSame(openAi, ReflectionTestUtils.getField(rag, "openAiChatClient"));
        });
    }

    @Test
    void missingKeyStillStartsButAnswersSayWhy() {
        runner.withPropertyValues("spring.ai.openai.api-key=").run(context -> {
            assertTrue(context.getStartupFailure() == null, () -> "" + context.getStartupFailure());
            ChatClient openAi = context.getBean("openAiChatClient", ChatClient.class);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> openAi.prompt().user("hi").call().content());
            assertEquals("OpenAI API key is not set (OPENAI_API_KEY / ai-openai-api-key)", e.getMessage());
        });
    }

    @Test
    void baseUrlSendsAnswersToThatServer() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            byte[] body = ("{\"id\":\"1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"qwen3:8b\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"local answer\"},"
                + "\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            runner.withPropertyValues(
                "spring.ai.openai.api-key=unused",
                "spring.ai.openai.base-url=http://127.0.0.1:" + server.getAddress().getPort()).run(context -> {
                ChatClient openAi = context.getBean("openAiChatClient", ChatClient.class);
                assertEquals("local answer", openAi.prompt().user("hi").call().content());
                assertEquals("/v1/chat/completions", path.get());
            });
        } finally {
            server.stop(0);
        }
    }
}
