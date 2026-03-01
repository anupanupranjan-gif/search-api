package com.search.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductDocument {

    private String id;
    private String title;
    private String description;
    private String brand;
    private String category;
    private double price;
    private double rating;

    @JsonProperty("inStock")
    private boolean inStock;

    @JsonProperty("product_vector")
    private List<Float> productVector;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }

    public boolean isInStock() { return inStock; }
    public void setInStock(boolean inStock) { this.inStock = inStock; }

    public List<Float> getProductVector() { return productVector; }
    public void setProductVector(List<Float> productVector) { this.productVector = productVector; }
}
