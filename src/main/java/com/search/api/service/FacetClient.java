package com.search.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
public class FacetClient {

    private static final Logger log = LoggerFactory.getLogger(FacetClient.class);

    @Value("${nexarank.base-url}")
    private String baseUrl;

    @Value("${nexarank.service-username}")
    private String username;

    @Value("${nexarank.service-password}")
    private String password;

    @Value("${nexarank.enabled:true}")
    private boolean enabled;

    @Value("${nexarank.cache-ttl-seconds:30}")
    private long cacheTtlSeconds;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile List<Map<String, Object>> cachedFacets = null;
    private volatile long facetCacheExpiry = 0;
    private volatile String cachedToken = null;
    private volatile long tokenExpiry = 0;

    @PostConstruct
    public void init() {
        System.out.println("=== FacetClient INIT: baseUrl=" + baseUrl + " enabled=" + enabled + " ===");
        log.info("FacetClient initialized with baseUrl={} enabled={}", baseUrl, enabled);
    }

    public FacetClient() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public List<Map<String, Object>> getEnabledFacets() {
        if (!enabled) return Collections.emptyList();

        // Cache facets for 60 seconds
        if (cachedFacets != null && System.currentTimeMillis() < facetCacheExpiry) {
            log.info("FacetClient: returning {} cached facets", cachedFacets.size());
            return cachedFacets;
        }
        log.info("FacetClient: fetching facets from NexaRank at {}", baseUrl);

        try {
            String token = getToken();
            if (token == null) return Collections.emptyList();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/facets?enabledOnly=true"))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                List<Map<String, Object>> facets = objectMapper.readValue(
                        response.body(), new TypeReference<List<Map<String, Object>>>() {});
                cachedFacets = facets;
                facetCacheExpiry = System.currentTimeMillis() + (cacheTtlSeconds * 1000);
                log.info("FacetClient: fetched {} enabled facets from NexaRank", facets.size());
                return facets;
            }
        } catch (Exception e) {
            log.warn("FacetClient: Failed to fetch facets from NexaRank: {}", e.getMessage());
        }
        log.warn("FacetClient: returning empty facets list");
        return Collections.emptyList();
    }


    /**
     * Fetch facets filtered by visibility rules for the given context.
     * Bypasses cache since context changes per request.
     * TODO NR-42: move to NexaRank Java SDK v1.0.3
     */
    public List<Map<String, Object>> getEnabledFacets(java.util.Map<String, String> selectedFacets) {
        if (!enabled || selectedFacets == null || selectedFacets.isEmpty()) {
            return getEnabledFacets();
        }
        try {
            String token = getToken();
            if (token == null) return getEnabledFacets();

            StringBuilder url = new StringBuilder(baseUrl + "/api/v1/facets?enabledOnly=true");
            for (java.util.Map.Entry<String, String> entry : selectedFacets.entrySet()) {
                url.append("&facet_")
                   .append(java.net.URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                   .append("=")
                   .append(java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                List<Map<String, Object>> facets = objectMapper.readValue(
                        response.body(), new TypeReference<List<Map<String, Object>>>() {});
                log.debug("FacetClient: fetched {} context-aware facets for context={}",
                        facets.size(), selectedFacets);
                return facets;
            }
        } catch (Exception e) {
            log.warn("FacetClient: context-aware facet fetch failed: {}", e.getMessage());
        }
        return getEnabledFacets();
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
                // Login response carries extra fields (role, permissions[], etc.) beyond the
                // token — read as a tree instead of Map<String,String> so those don't break
                // deserialization (permissions is a JSON array, not a String).
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(response.body());
                cachedToken = node.has("token") ? node.get("token").asText() : null;
                tokenExpiry = System.currentTimeMillis() + (23 * 60 * 60 * 1000);
                return cachedToken;
            }
        } catch (Exception e) {
            log.warn("FacetClient login failed: {}", e.getMessage());
        }
        return null;
    }
}
