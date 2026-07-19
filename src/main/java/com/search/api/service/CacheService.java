package com.search.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.search.api.model.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cache.search.ttl-seconds:300}")
    private long searchTtl;

    @Value("${cache.embedding.ttl-seconds:3600}")
    private long embeddingTtl;

    @Value("${cache.rag.ttl-seconds:600}")
    private long ragTtl;

    public CacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ── Search results cache ──────────────────────────────────────────────────

    public record CachedSearch(List<SearchResponse.SearchHit> hits, Map<String, Object> facets) {}

    public CachedSearch getSearchResults(String key) {
        try {
            String val = redis.opsForValue().get("search:" + key);
            if (val == null) return null;
            log.debug("Cache HIT search: {}", key);
            return objectMapper.readValue(val, CachedSearch.class);
        } catch (Exception e) {
            log.warn("Cache read error for search key {}: {}", key, e.getMessage());
            return null;
        }
    }

    public void putSearchResults(String key, List<SearchResponse.SearchHit> hits, Map<String, Object> facets) {
        try {
            String val = objectMapper.writeValueAsString(new CachedSearch(hits, facets));
            redis.opsForValue().set("search:" + key, val, searchTtl, TimeUnit.SECONDS);
            log.debug("Cache SET search: {} (TTL {}s)", key, searchTtl);
        } catch (Exception e) {
            log.warn("Cache write error for search key {}: {}", key, e.getMessage());
        }
    }

    // ── Embedding vector cache ────────────────────────────────────────────────

    public float[] getEmbedding(String text) {
        try {
            String val = redis.opsForValue().get("emb:" + text.hashCode());
            if (val == null) return null;
            log.debug("Cache HIT embedding: {}", text.substring(0, Math.min(30, text.length())));
            List<Double> list = objectMapper.readValue(val, new TypeReference<List<Double>>() {});
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) arr[i] = list.get(i).floatValue();
            return arr;
        } catch (Exception e) {
            log.warn("Cache read error for embedding: {}", e.getMessage());
            return null;
        }
    }

    public void putEmbedding(String text, float[] vector) {
        try {
            float[] v = vector;
            java.util.List<Float> list = new java.util.ArrayList<>(v.length);
            for (float f : v) list.add(f);
            String val = objectMapper.writeValueAsString(list);
            redis.opsForValue().set("emb:" + text.hashCode(), val, embeddingTtl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Cache write error for embedding: {}", e.getMessage());
        }
    }

    // ── RAG answer cache ──────────────────────────────────────────────────────

    public String getRagAnswer(String key) {
        try {
            String val = redis.opsForValue().get("rag:" + key.hashCode());
            if (val != null) log.info("Cache HIT RAG: {}", key.substring(0, Math.min(50, key.length())));
            return val;
        } catch (Exception e) {
            log.warn("Cache read error for RAG key: {}", e.getMessage());
            return null;
        }
    }

    public void putRagAnswer(String key, String answer) {
        try {
            redis.opsForValue().set("rag:" + key.hashCode(), answer, ragTtl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Cache write error for RAG key: {}", e.getMessage());
        }
    }

    // ── Cache key builders ────────────────────────────────────────────────────

    public String searchKey(String q, String mode, String category, String brand,
                            Double minPrice, Double maxPrice, int page) {
        return String.format("%s:%s:%s:%s:%s:%s:%d",
                q, mode,
                category != null ? category : "",
                brand != null ? brand : "",
                minPrice != null ? minPrice : "",
                maxPrice != null ? maxPrice : "",
                page).hashCode() + "";
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    public long getKeyCount(String prefix) {
        try {
            var keys = redis.keys(prefix + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            return -1;
        }
    }
}
