package com.search.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.search.api.model.SearchResponse.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Value("${ollama.base-url:http://host.docker.internal:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:gemma3:1b}")
    private String ollamaModel;

    @Value("${ollama.timeout-seconds:10}")
    private int timeoutSeconds;

    // NR-176 (search-api part): these prompts had no config mechanism at all —
    // externalized the same way ollama.base-url/model/timeout-seconds already
    // are, rather than adding a DB-backed admin UI like nexarank-api's
    // LlmConfig (search-api is deliberately stateless, no Postgres/JPA).
    // Blank (unset) means "use the built-in default" — set via k8s ConfigMap
    // env vars PROMPTS_RAG_ANSWER / PROMPTS_RAG_COMPARE to override.
    @Value("${prompts.rag-answer:}")
    private String ragAnswerPromptOverride;

    @Value("${prompts.rag-compare:}")
    private String ragComparePromptOverride;

    private static final String DEFAULT_RAG_ANSWER_PROMPT_TEMPLATE = """
            You are a helpful eCommerce shopping assistant.
            Answer the user's question based ONLY on the products listed below.
            Be concise and specific. Do not make up product details.
            If the answer is not in the product list, say so.

            Products:
            %s

            Question: %s

            Answer:""";

    private static final String DEFAULT_RAG_COMPARE_PROMPT_TEMPLATE = """
            You are a helpful eCommerce shopping assistant.
            Compare the following products clearly and concisely.
            Highlight key differences in price, brand, and rating.
            Recommend which is best value and why.

            Products to compare:
            %s

            Comparison:""";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String answer(String question, List<SearchHit> products) {
        try {
            String prompt = buildPrompt(question, products);

            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", ollamaModel,
                    "prompt", prompt,
                    "stream", false,
                    "options", Map.of(
                            "temperature", 0.3,
                            "num_predict", 300
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
                String answer = json.path("response").asText("").trim();
                log.info("RAG answer generated for question: {}", question);
                return answer.isBlank() ? "I could not generate an answer based on the available products." : answer;
            } else {
                log.warn("Ollama returned status {} for RAG request", response.statusCode());
                return "Could not generate an answer at this time.";
            }

        } catch (Exception e) {
            log.error("RAG failed for question '{}': {}", question, e.getMessage());
            return "Could not generate an answer: " + e.getMessage();
        }
    }

    public String compare(List<SearchHit> products) {
        try {
            String prompt = buildComparePrompt(products);

            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", ollamaModel,
                    "prompt", prompt,
                    "stream", false,
                    "options", Map.of(
                            "temperature", 0.2,
                            "num_predict", 400
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
                return json.path("response").asText("").trim();
            }
            return "Could not generate comparison.";

        } catch (Exception e) {
            log.error("Comparison failed: {}", e.getMessage());
            return "Could not generate comparison: " + e.getMessage();
        }
    }

    private String buildPrompt(String question, List<SearchHit> products) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < Math.min(products.size(), 8); i++) {
            SearchHit p = products.get(i);
            context.append(String.format("%d. \"%s\" | Brand: %s | Price: $%.2f | Rating: %.1f/5 | Category: %s%n",
                    i + 1,
                    p.getTitle() != null ? p.getTitle() : "N/A",
                    p.getBrand() != null ? p.getBrand() : "N/A",
                    p.getPrice() != null ? p.getPrice() : 0.0,
                    p.getRating() != null ? p.getRating() : 0.0,
                    p.getCategory() != null ? p.getCategory() : "N/A"
            ));
        }

        String template = (ragAnswerPromptOverride != null && !ragAnswerPromptOverride.isBlank())
                ? ragAnswerPromptOverride
                : DEFAULT_RAG_ANSWER_PROMPT_TEMPLATE;
        return String.format(template, context, question);
    }

    private String buildComparePrompt(List<SearchHit> products) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < Math.min(products.size(), 5); i++) {
            SearchHit p = products.get(i);
            context.append(String.format("%d. \"%s\" | Brand: %s | Price: $%.2f | Rating: %.1f/5%n",
                    i + 1,
                    p.getTitle() != null ? p.getTitle() : "N/A",
                    p.getBrand() != null ? p.getBrand() : "N/A",
                    p.getPrice() != null ? p.getPrice() : 0.0,
                    p.getRating() != null ? p.getRating() : 0.0
            ));
        }

        String template = (ragComparePromptOverride != null && !ragComparePromptOverride.isBlank())
                ? ragComparePromptOverride
                : DEFAULT_RAG_COMPARE_PROMPT_TEMPLATE;
        return String.format(template, context);
    }
}
