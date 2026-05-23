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
                facetCacheExpiry = System.currentTimeMillis() + 60_000;
                log.info("FacetClient: fetched {} enabled facets from NexaRank", facets.size());
                return facets;
            }
        } catch (Exception e) {
            log.warn("FacetClient: Failed to fetch facets from NexaRank: {}", e.getMessage());
        }
        log.warn("FacetClient: returning empty facets list");
        return Collections.emptyList();
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
                tokenExpiry = System.currentTimeMillis() + (23 * 60 * 60 * 1000);
                return cachedToken;
            }
        } catch (Exception e) {
            log.warn("FacetClient login failed: {}", e.getMessage());
        }
        return null;
    }
}
