package com.search.api.controller;

import com.search.api.model.SearchRequest;
import com.search.api.model.SearchResponse;
import com.search.api.service.QueryRewriteService;
import com.search.api.service.RagService;
import com.search.api.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public SearchController(SearchService searchService,
                            QueryRewriteService queryRewriteService,
                            RagService ragService) {
        this.searchService = searchService;
        this.queryRewriteService = queryRewriteService;
        this.ragService = ragService;
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
            return ResponseEntity.badRequest().body(Map.of("error", "Query parameter 'q' is required"));
        }
        if (!mode.equals("hybrid") && !mode.equals("vector") && !mode.equals("keyword")) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be one of: hybrid, vector, keyword"));
        }
        size = Math.min(size, 100);

        String effectiveQuery = q;
        String rewrittenQuery = null;
        if (rewrite && !q.trim().equals("*")) {
            rewrittenQuery = queryRewriteService.rewrite(q);
            if (!rewrittenQuery.equals(q)) effectiveQuery = rewrittenQuery;
        }

        try {
            SearchRequest req = new SearchRequest();
            req.setQuery(effectiveQuery);
            req.setMode(mode);
            req.setCategory(category);
            req.setBrand(brand);
            req.setMinPrice(minPrice);
            req.setMaxPrice(maxPrice);
            req.setPage(page);
            req.setSize(size);

            SearchResponse response = searchService.search(req);
            if (rewrittenQuery != null && !rewrittenQuery.equals(q)) {
                response.setOriginalQuery(q);
                response.setRewrittenQuery(rewrittenQuery);
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Search failed for query='{}': {}", effectiveQuery, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Search failed", "detail", e.getMessage()));
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
            return ResponseEntity.badRequest().body(Map.of("error", "Query parameter 'q' is required"));
        }

        try {
            // Step 1: Rewrite question into search keywords
            String searchQuery = queryRewriteService.rewrite(q);
            log.info("Ask: question='{}' -> searchQuery='{}'", q, searchQuery);

            // Step 2: Search for relevant products
            SearchRequest req = new SearchRequest();
            req.setQuery(searchQuery);
            req.setMode(mode);
            req.setCategory(category);
            req.setMinPrice(minPrice);
            req.setMaxPrice(maxPrice);
            req.setPage(0);
            req.setSize(10);

            SearchResponse searchResponse = searchService.search(req);

            // Step 2: Generate answer or comparison via Ollama
            String answer;
            if (compare && searchResponse.getHits().size() > 1) {
                answer = ragService.compare(searchResponse.getHits());
            } else {
                answer = ragService.answer(q, searchResponse.getHits());
            }

            // Step 3: Return answer + products
            Map<String, Object> result = new HashMap<>();
            result.put("question", q);
            result.put("answer", answer);
            result.put("mode", compare ? "comparison" : "answer");
            result.put("products", searchResponse.getHits());
            result.put("total", searchResponse.getTotal());
            result.put("tookMs", searchResponse.getTookMs());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Ask failed for question='{}': {}", q, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ask failed", "detail", e.getMessage()));
        }
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<?> getProduct(@PathVariable String id) {
        try {
            var hit = searchService.getById(id);
            if (hit == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(hit);
        } catch (Exception e) {
            log.error("Product lookup failed for id='{}': {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Lookup failed", "detail", e.getMessage()));
        }
    }
}
