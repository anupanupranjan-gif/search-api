package com.search.api.model;

import java.util.List;

public class SearchResult {

    private long total;
    private int size;
    private int from;
    private List<SearchHit> hits;

    public SearchResult(long total, int size, int from, List<SearchHit> hits) {
        this.total = total;
        this.size = size;
        this.from = from;
        this.hits = hits;
    }

    public long getTotal() { return total; }
    public int getSize() { return size; }
    public int getFrom() { return from; }
    public List<SearchHit> getHits() { return hits; }

    public static class SearchHit {
        private String id;
        private double score;
        private String title;
        private String description;
        private String brand;
        private String category;
        private double price;
        private double rating;
        private boolean inStock;

        // Builder
        public static Builder builder() { return new Builder(); }

        public String getId() { return id; }
        public double getScore() { return score; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getBrand() { return brand; }
        public String getCategory() { return category; }
        public double getPrice() { return price; }
        public double getRating() { return rating; }
        public boolean isInStock() { return inStock; }

        public static class Builder {
            private final SearchHit hit = new SearchHit();

            public Builder id(String id) { hit.id = id; return this; }
            public Builder score(double score) { hit.score = score; return this; }
            public Builder title(String title) { hit.title = title; return this; }
            public Builder description(String description) { hit.description = description; return this; }
            public Builder brand(String brand) { hit.brand = brand; return this; }
            public Builder category(String category) { hit.category = category; return this; }
            public Builder price(double price) { hit.price = price; return this; }
            public Builder rating(double rating) { hit.rating = rating; return this; }
            public Builder inStock(boolean inStock) { hit.inStock = inStock; return this; }
            public SearchHit build() { return hit; }
        }
    }
}
