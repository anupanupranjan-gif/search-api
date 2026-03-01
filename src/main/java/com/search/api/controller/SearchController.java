package com.search.api.controller;

import com.search.api.model.SearchRequest;
import com.search.api.model.SearchResponse;
import com.search.api.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * GET /api/v1/search?q=wireless+headphones&mode=hybrid&category=Electronics&minPrice=20&maxPrice=200&page=0&size=20
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "hybrid") String mode,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query parameter 'q' is required"));
        }

        if (!mode.equals("hybrid") && !mode.equals("vector") && !mode.equals("keyword")) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be one of: hybrid, vector, keyword"));
        }

        size = Math.min(size, 100); // hard cap

        try {
            SearchRequest req = new SearchRequest();
            req.setQuery(q);
            req.setMode(mode);
            req.setCategory(category);
            req.setBrand(brand);
            req.setMinPrice(minPrice);
            req.setMaxPrice(maxPrice);
            req.setPage(page);
            req.setSize(size);

            SearchResponse response = searchService.search(req);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Search failed for query='{}': {}", q, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Search failed", "detail", e.getMessage()));
        }
    }

    /**
     * GET /api/v1/products/{id}
     */
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
