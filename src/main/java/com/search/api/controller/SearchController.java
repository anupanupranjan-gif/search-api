// Copyright (c) 2026 Anup Ranjan. Licensed under Apache 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
package com.search.api.controller;

import com.search.api.model.ClickEvent;
import com.search.api.model.ErrorResponse;
import com.search.api.model.SearchRequest;
import com.search.api.model.SearchResponse;
import com.search.api.service.CacheService;
import com.search.api.service.ClickEventProducer;
import com.search.api.service.QueryRewriteService;
import com.search.api.service.RagService;
import com.search.api.config.MetricsConfig;
import com.search.api.service.SearchService;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private final SearchService searchService;
    private final QueryRewriteService queryRewriteService;
    private final RagService ragService;
    private final CacheService cacheService;
    private final ClickEventProducer clickEventProducer;

    @Value("${nexarank.api.base-url:http://nexarank-api.default.svc.cluster.local/api/v1}")
    private String nexarankBaseUrl;
    private final Timer searchLatencyTimer;

    public SearchController(SearchService searchService,
                            QueryRewriteService queryRewriteService,
                            RagService ragService,
                            CacheService cacheService,
                            ClickEventProducer clickEventProducer,
                            MetricsConfig metricsConfig) {
        this.searchService = searchService;
        this.queryRewriteService = queryRewriteService;
        this.ragService = ragService;
        this.cacheService = cacheService;
        this.clickEventProducer = clickEventProducer;
        this.searchLatencyTimer = metricsConfig.getSearchLatencyTimer();
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "hybrid") String mode,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean rewrite
    ) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("BAD_REQUEST", "Query parameter 'q' is required"));
        }
        if (!mode.equals("hybrid") && !mode.equals("vector") && !mode.equals("keyword")) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("BAD_REQUEST", "mode must be one of: hybrid, vector, keyword"));
        }
        final int effectiveSize = Math.min(size, 100);
        final String finalQuery = rewrite && !q.trim().equals("*")
                ? queryRewriteService.rewrite(q) : q;
        final String rewrittenQuery = !finalQuery.equals(q) ? finalQuery : null;

        return searchLatencyTimer.record(() -> {
            try {
                SearchRequest req = new SearchRequest();
                req.setQuery(finalQuery);
                req.setMode(mode);
                req.setCategory(category);
                req.setBrand(brand);
                req.setMinPrice(minPrice);
                req.setMaxPrice(maxPrice);
                req.setPage(page);
                req.setSize(effectiveSize);

                SearchResponse response = searchService.search(req);
                if (rewrittenQuery != null) {
                    response.setOriginalQuery(q);
                    response.setRewrittenQuery(rewrittenQuery);
                }
                // Track all search events (async, non-blocking)
                try {
                    java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
                    String seJson = String.format(
                        "{\"query\":\"%s\",\"resultCount\":%d,\"mode\":\"%s\",\"tookMs\":%d}",
                        finalQuery.replace("\"", "'"), response.getTotal(), mode, response.getTookMs());
                    java.net.http.HttpRequest seReq = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(nexarankBaseUrl + "/search-events"))
                        .header("Content-Type", "application/json")
                        .header("X-Tenant-Id", "default")
                        .header("X-Project-Id", "main")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(seJson))
                        .build();
                    httpClient.sendAsync(seReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                    // Also track zero results separately
                    if (response.getTotal() == 0) {
                        String zrJson = String.format("{\"query\":\"%s\"}", finalQuery.replace("\"", "'"));
                        java.net.http.HttpRequest zrReq = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(nexarankBaseUrl + "/zero-results"))
                            .header("Content-Type", "application/json")
                            .header("X-Tenant-Id", "default")
                            .header("X-Project-Id", "main")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(zrJson))
                            .build();
                        httpClient.sendAsync(zrReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                    }
                } catch (Exception ze) {
                    log.debug("Failed to track search event: {}", ze.getMessage());
                }
                return ResponseEntity.ok(response);
            } catch (Exception e) {
                log.error("Search failed for query='{}': {}", finalQuery, e.getMessage(), e);
                return ResponseEntity.internalServerError()
                        .body(ErrorResponse.of("SEARCH_FAILED", "Search failed"));
            }
        });
    }

    @PostMapping("/click")
    public ResponseEntity<?> recordClick(@RequestBody Map<String, Object> body) {
        try {
            String sessionId = (String) body.getOrDefault("sessionId", "anonymous");
            String query     = (String) body.get("query");
            String productId = (String) body.get("productId");
            String productTitle = (String) body.getOrDefault("productTitle", "");
            String variantId    = (String) body.get("variantId");
            int position     = body.containsKey("position")
                    ? ((Number) body.get("position")).intValue() : 0;

            if (query == null || productId == null) {
                return ResponseEntity.badRequest()
                        .body(ErrorResponse.of("BAD_REQUEST", "query and productId are required"));
            }

            clickEventProducer.publish(
                    ClickEvent.of(sessionId, query, productId, productTitle, position, variantId));

            return ResponseEntity.accepted().build();
        } catch (Exception e) {
            log.error("Failed to record click event", e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of("CLICK_RECORD_FAILED", "Failed to record click"));
        }
    }

    @GetMapping("/ask")
    public ResponseEntity<?> ask(
            @RequestParam String q,
            @RequestParam(defaultValue = "hybrid") String mode,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "false") boolean compare
    ) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("BAD_REQUEST", "Query parameter 'q' is required"));
        }

        try {
            String searchQuery = queryRewriteService.rewrite(q);
            log.info("Ask: question='{}' -> searchQuery='{}'", q, searchQuery);

            SearchRequest req = new SearchRequest();
            req.setQuery(searchQuery);
            req.setMode(mode);
            req.setCategory(category);
            req.setMinPrice(minPrice);
            req.setMaxPrice(maxPrice);
            req.setPage(0);
            req.setSize(10);

            SearchResponse searchResponse = searchService.search(req);

            String ragCacheKey = q + ":" + compare;
            String answer = cacheService.getRagAnswer(ragCacheKey);
            boolean fromCache = answer != null;

            if (!fromCache) {
                if (compare && searchResponse.getHits().size() > 1) {
                    answer = ragService.compare(searchResponse.getHits());
                } else {
                    answer = ragService.answer(q, searchResponse.getHits());
                }
                cacheService.putRagAnswer(ragCacheKey, answer);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("question", q);
            result.put("answer", answer);
            result.put("mode", compare ? "comparison" : "answer");
            result.put("cached", fromCache);
            result.put("products", searchResponse.getHits());
            result.put("total", searchResponse.getTotal());
            result.put("tookMs", searchResponse.getTookMs());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Ask failed for question='{}': {}", q, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of("ASK_FAILED", "Ask failed"));
        }
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<?> getProduct(@PathVariable String id) {
        try {
            var hit = searchService.getById(id);
            if (hit == null) return ResponseEntity.status(404)
                    .body(ErrorResponse.of("PRODUCT_NOT_FOUND", "Product not found: " + id));
            return ResponseEntity.ok(hit);
        } catch (Exception e) {
            log.error("Product lookup failed for id='{}': {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of("LOOKUP_FAILED", "Product lookup failed"));
        }
    }
}
