package com.search.api.model;

public class SearchRequest {

    private String query;
    private String mode = "hybrid"; // hybrid | vector | keyword
    private String category;
    private String brand;
    private Double minPrice;
    private Double maxPrice;
    private int page = 0;
    private int size = 20;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public Double getMinPrice() { return minPrice; }
    public void setMinPrice(Double minPrice) { this.minPrice = minPrice; }

    public Double getMaxPrice() { return maxPrice; }
    public void setMaxPrice(Double maxPrice) { this.maxPrice = maxPrice; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    private java.util.Map<String, String> selectedFacets;
    private String sessionId;

    public java.util.Map<String, String> getSelectedFacets() { return selectedFacets; }
    public void setSelectedFacets(java.util.Map<String, String> selectedFacets) {
        this.selectedFacets = selectedFacets;
    }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
