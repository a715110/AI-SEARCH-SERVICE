package com.dodaso.ecosystem.ai.service;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;


@Service
@Slf4j
public class OllamaService {
    private final RestTemplate ollamaRestTemplate = new RestTemplate();

    float[] getOllamaEmbedding(String text) {
        int maxRetries = 3;
        long retryDelayMs = 2000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                OllamaService.OllamaEmbeddingRequest request = new OllamaService.OllamaEmbeddingRequest("nomic-embed-text", text);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(
                    org.springframework.http.MediaType.valueOf(MediaType.APPLICATION_JSON));
                HttpEntity<OllamaService.OllamaEmbeddingRequest> httpEntity = new HttpEntity<>(request);

                ResponseEntity<OllamaService.OllamaEmbeddingResponse> responseEntity = ollamaRestTemplate.exchange(
                    "http://localhost:11434/api/embeddings",
                    HttpMethod.POST,
                    httpEntity,
                    new ParameterizedTypeReference<OllamaService.OllamaEmbeddingResponse>() {}
                );

                OllamaService.OllamaEmbeddingResponse response = responseEntity.getBody();
                if (response != null && response.getEmbedding() != null) {
                    // Estimate tokens (rough approximation: 1 token ≈ 4 characters)
                    int estimatedTokens = (text.length() + 3) / 4;
                    log.info("[Embedding Token Usage] model=nomic-embed-text, estimated_tokens={}, text_length={}",
                        estimatedTokens, text.length());
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
