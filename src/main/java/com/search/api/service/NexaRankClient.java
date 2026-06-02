// Copyright (c) 2026 Anup Ranjan. Licensed under Apache 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
package com.search.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.search.api.model.nexarank.NexaRankEnrichedQuery;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 17: Updated to call /api/v1/rules/enrich
 * instead of /api/v1/rules/query/{query}
 *
 * The enrich endpoint returns engine-agnostic rule instructions
 * plus pre-translated ES DSL. No login required — enrich is public.
 */
@Service
public class NexaRankClient {

    private static final Logger log = LoggerFactory.getLogger(NexaRankClient.class);

    @Value("${nexarank.base-url}")
    private String baseUrl;

    @Value("${nexarank.cache-ttl-seconds:30}")
    private long cacheTtlSeconds;

    @Value("${nexarank.enabled:true}")
    private boolean enabled;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Cache: query -> (enrichedQuery, timestamp)
    private final ConcurrentHashMap<String, CachedEnrichment> cache = new ConcurrentHashMap<>();

    public NexaRankClient() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    }

    public NexaRankEnrichedQuery getEnrichedQuery(String query) {
        if (!enabled || query == null || query.isBlank()) {
            return NexaRankEnrichedQuery.passthrough(query);
        }

        String normalizedQuery = query.toLowerCase().trim();

        // Check cache
        CachedEnrichment cached = cache.get(normalizedQuery);
        if (cached != null && !cached.isExpired(cacheTtlSeconds)) {
            log.debug("NexaRank cache hit for query='{}'", normalizedQuery);
            return cached.enriched;
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "query", normalizedQuery,
                "engineType", "ELASTICSEARCH",
                "zone", "search-results"
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/rules/enrich"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                NexaRankEnrichedQuery enriched = objectMapper.readValue(
                    response.body(), NexaRankEnrichedQuery.class);
                cache.put(normalizedQuery, new CachedEnrichment(enriched));
                log.debug("NexaRank enriched query='{}': {} rules applied",
                    normalizedQuery, enriched.getAppliedRulesCount());
                return enriched;
            } else {
                log.warn("NexaRank enrich returned {} for query='{}'",
                    response.statusCode(), normalizedQuery);
                return NexaRankEnrichedQuery.passthrough(query);
            }
        } catch (Exception e) {
            log.warn("NexaRank enrich failed for query='{}': {}", normalizedQuery, e.getMessage());
            return NexaRankEnrichedQuery.passthrough(query);
        }
    }

    private static class CachedEnrichment {
        final NexaRankEnrichedQuery enriched;
        final long timestamp;

        CachedEnrichment(NexaRankEnrichedQuery enriched) {
            this.enriched = enriched;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long ttlSeconds) {
            return System.currentTimeMillis() - timestamp > ttlSeconds * 1000;
        }
    }
}
