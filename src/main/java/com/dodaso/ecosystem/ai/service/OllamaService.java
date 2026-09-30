package com.dodaso.ecosystem.ai.service;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;


/**
 * Direct call to Ollama's embeddings endpoint.
 *
 * <p><b>STEP1:</b> address and model come from configuration; they were hard-coded
 * ({@code http://localhost:11434/api/embeddings}, {@code nomic-embed-text}). In a customer
 * instance Ollama runs on its own host, so every embedding (sync and search) would have
 * failed. Properties used (the same ones Spring AI's auto-configured embedding model reads):
 * <ul>
 *   <li>{@code spring.ai.ollama.base-url}</li>
 *   <li>{@code spring.ai.ollama.embedding.options.model}</li>
 * </ul>
 * The model must produce vectors of {@code spring.ai.vectorstore.pgvector.dimensions} (768).
 */
@Service
@Slf4j
public class OllamaService {

    private static final String EMBEDDINGS_PATH = "/api/embeddings";

    private final RestTemplate ollamaRestTemplate = new RestTemplate();
    private final String embeddingsUrl;
    private final String embeddingModel;

    public OllamaService(@Value("${spring.ai.ollama.base-url}") String ollamaBaseUrl,
                         @Value("${spring.ai.ollama.embedding.options.model}") String embeddingModel) {
        this.embeddingsUrl = stripTrailingSlash(ollamaBaseUrl) + EMBEDDINGS_PATH;
        this.embeddingModel = embeddingModel;
    }

    /** "http://ollama:11434/" -> "http://ollama:11434" so paths can be appended safely. */
    static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    float[] getOllamaEmbedding(String text) {
        int maxRetries = 3;
        long retryDelayMs = 2000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                OllamaService.OllamaEmbeddingRequest request = new OllamaService.OllamaEmbeddingRequest(embeddingModel, text);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(
                    org.springframework.http.MediaType.valueOf(MediaType.APPLICATION_JSON));
                HttpEntity<OllamaService.OllamaEmbeddingRequest> httpEntity = new HttpEntity<>(request);

                ResponseEntity<OllamaService.OllamaEmbeddingResponse> responseEntity = ollamaRestTemplate.exchange(
                    embeddingsUrl,
                    HttpMethod.POST,
                    httpEntity,
                    new ParameterizedTypeReference<OllamaService.OllamaEmbeddingResponse>() {}
                );

                OllamaService.OllamaEmbeddingResponse response = responseEntity.getBody();
                if (response != null && response.getEmbedding() != null) {
                    // Estimate tokens (rough approximation: 1 token ≈ 4 characters)
                    int estimatedTokens = (text.length() + 3) / 4;
                    log.info("[Embedding Token Usage] model={}, estimated_tokens={}, text_length={}",
                        embeddingModel, estimatedTokens, text.length());
                    return response.getEmbedding();
                }
                log.warn("Empty response from embedding service on attempt {}/{}", attempt, maxRetries);
            } catch (Exception e) {
                log.warn("Failed to get embedding from Ollama (attempt {}/{}): {}",
                    attempt, maxRetries, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("All {} attempts to reach Ollama embedding service failed", maxRetries, e);
                    throw new RuntimeException("Embedding service unavailable after " + maxRetries + " attempts", e);
                }
                try {
                    Thread.sleep(retryDelayMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while retrying embedding service", ie);
                }
            }
        }
        throw new RuntimeException("Empty response from embedding service after all retries");
    }


    private static class OllamaEmbeddingRequest {
        public String model;
        public String prompt;
        public OllamaEmbeddingRequest(String model, String prompt) {
            this.model = model;
            this.prompt = prompt;
        }
    }

    private static class OllamaEmbeddingResponse {
        private float[] embedding;
        public float[] getEmbedding() { return embedding; }
        public void setEmbedding(float[] embedding) { this.embedding = embedding; }
    }

}
