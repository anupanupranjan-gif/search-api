package com.search.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.search.api.model.nexarank.NexaRankRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NexaRankClient {

    private static final Logger log = LoggerFactory.getLogger(NexaRankClient.class);

    @Value("${nexarank.base-url}")
    private String baseUrl;

    @Value("${nexarank.service-username}")
    private String username;

    @Value("${nexarank.service-password}")
    private String password;

    @Value("${nexarank.cache-ttl-seconds:30}")
    private long cacheTtlSeconds;

    @Value("${nexarank.enabled:true}")
    private boolean enabled;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;  // initialized in constructor

    // Simple in-memory cache: query -> (rules, timestamp)
    private final ConcurrentHashMap<String, CachedRules> rulesCache = new ConcurrentHashMap<>();
    private volatile String cachedToken = null;
    private volatile long tokenExpiry = 0;

    public NexaRankClient() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public List<NexaRankRule> getRulesForQuery(String query) {
        if (!enabled || query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String normalizedQuery = query.toLowerCase().trim();

        // Check cache
        CachedRules cached = rulesCache.get(normalizedQuery);
        if (cached != null && !cached.isExpired(cacheTtlSeconds)) {
            return cached.rules;
        }

        try {
            String token = getToken();
            if (token == null) return Collections.emptyList();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/rules/query/" + normalizedQuery))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                List<NexaRankRule> rules = objectMapper.readValue(
                        response.body(), new TypeReference<List<NexaRankRule>>() {});
                rulesCache.put(normalizedQuery, new CachedRules(rules));
                log.debug("NexaRank rules for query='{}': {} rules", normalizedQuery, rules.size());
                return rules;
            } else {
                log.warn("NexaRank returned {} for query='{}'", response.statusCode(), normalizedQuery);
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("NexaRank lookup failed for query='{}': {}", normalizedQuery, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String getToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken;
        }
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("username", username, "password", password));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Map<String, String> map = objectMapper.readValue(
                        response.body(), new TypeReference<Map<String, String>>() {});
                cachedToken = map.get("token");
                tokenExpiry = System.currentTimeMillis() + (23 * 60 * 60 * 1000); // 23 hours
                return cachedToken;
            }
        } catch (Exception e) {
            log.warn("NexaRank login failed: {}", e.getMessage());
        }
        return null;
    }

    private static class CachedRules {
        final List<NexaRankRule> rules;
        final long timestamp;

        CachedRules(List<NexaRankRule> rules) {
            this.rules = rules;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long ttlSeconds) {
            return System.currentTimeMillis() - timestamp > ttlSeconds * 1000;
        }
    }
}
