package com.search.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    @Value("${ollama.base-url:http://host.docker.internal:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:gemma3:1b}")
    private String ollamaModel;

    @Value("${ollama.timeout-seconds:5}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String rewrite(String originalQuery) {
        try {
            String prompt = buildPrompt(originalQuery);

            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", ollamaModel,
                    "prompt", prompt,
                    "stream", false,
                    "options", Map.of(
                            "temperature", 0.3,
                            "num_predict", 60
                    )
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String rewritten = json.path("response").asText("").trim();

                if (rewritten.isBlank() || rewritten.length() < 3) {
                    log.warn("Empty rewrite response for query: {}", originalQuery);
                    return originalQuery;
                }

                // Clean up — remove quotes, truncate if too long
                rewritten = rewritten.replaceAll("[\"']", "").trim();
                if (rewritten.length() > 200) rewritten = rewritten.substring(0, 200);

                log.info("Query rewrite: [{}] → [{}]", originalQuery, rewritten);
                return rewritten;
            } else {
                log.warn("Ollama returned status {}, using original query", response.statusCode());
                return originalQuery;
            }

        } catch (Exception e) {
            log.warn("Query rewrite failed ({}), using original query: {}", e.getMessage(), originalQuery);
            return originalQuery;
        }
    }

    private String buildPrompt(String query) {
        return String.format("""
                You are a search query expander for an eCommerce product search engine.
                Expand the following search query with related terms, synonyms, and product types.
                Return ONLY the expanded query as a single line of comma-separated terms.
                Do not explain. Do not add any other text.
                
                Query: %s
                Expanded:""", query);
    }
}
