package com.search.api.model;

import java.util.List;
import java.util.Map;

public class SearchResponse {
    private long total;
    private int page;
    private int size;
    private String mode;
    private long tookMs;
    private List<SearchHit> hits;
    private String originalQuery;
    private String rewrittenQuery;
    private Map<String, Object> facets;
    private String redirectUrl;
    private String recoveredQuery;   // NR-59: the alternative query actually used
    private boolean recovered;       // NR-59: true when the LLM-suggested retry is what's being shown

    public SearchResponse(long total, int page, int size, String mode, long tookMs, List<SearchHit> hits) {
        this.total = total;
        this.page = page;
        this.size = size;
        this.mode = mode;
        this.tookMs = tookMs;
        this.hits = hits;
    }

    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public String getMode() { return mode; }
    public long getTookMs() { return tookMs; }
    public List<SearchHit> getHits() { return hits; }
    public Map<String, Object> getFacets() { return facets; }
    public void setFacets(Map<String, Object> facets) { this.facets = facets; }

    public String getOriginalQuery() { return originalQuery; }
    public void setOriginalQuery(String originalQuery) { this.originalQuery = originalQuery; }
    public String getRewrittenQuery() { return rewrittenQuery; }
    public void setRewrittenQuery(String rewrittenQuery) { this.rewrittenQuery = rewrittenQuery; }
    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String redirectUrl) { this.redirectUrl = redirectUrl; }
    public String getRecoveredQuery() { return recoveredQuery; }
    public void setRecoveredQuery(String recoveredQuery) { this.recoveredQuery = recoveredQuery; }
    public boolean isRecovered() { return recovered; }
    public void setRecovered(boolean recovered) { this.recovered = recovered; }

    public static class SearchHit {
        private String productId;
        private String title;
        private String description;
        private String category;
        private String brand;
        private Double price;
        private Double rating;
        private Integer ratingCount;
        private float score;
        private List<Float> productVector;
        private Boolean semanticMatch;

        public String getProductId() { return productId; }
        public void setProductId(String productId) { this.productId = productId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getBrand() { return brand; }
        public void setBrand(String brand) { this.brand = brand; }
        public Double getPrice() { return price; }
        public void setPrice(Double price) { this.price = price; }
        public Double getRating() { return rating; }
        public void setRating(Double rating) { this.rating = rating; }
        public Integer getRatingCount() { return ratingCount; }
        public void setRatingCount(Integer ratingCount) { this.ratingCount = ratingCount; }
        public float getScore() { return score; }
        public void setScore(float score) { this.score = score; }
        public List<Float> getProductVector() { return productVector; }
        public void setProductVector(List<Float> productVector) { this.productVector = productVector; }
        public Boolean getSemanticMatch() { return semanticMatch; }
        public void setSemanticMatch(Boolean semanticMatch) { this.semanticMatch = semanticMatch; }
    }
}
