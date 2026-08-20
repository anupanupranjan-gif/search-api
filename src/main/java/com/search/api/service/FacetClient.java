package com.search.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

// NEVER java.net.http.HttpClient here - Java 25 AArch64 bug (see CLAUDE.md CRITICAL
// Build Rules). This class originally used it and every call silently returned an
// empty facet list in production (no exception logged, just a non-200 the code
// didn't even print) - confirmed live 2026-08-20: the exact same request succeeded
// instantly from a plain curl pod, but failed 100% of the time through this class's
// HttpClient instance. Rewritten on HttpURLConnection to match the rest of the
// codebase's established workaround.
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

    @Value("${nexarank.cache-ttl-seconds:30}")
    private long cacheTtlSeconds;

    private final ObjectMapper objectMapper;

    private volatile List<Map<String, Object>> cachedFacets = null;
    private volatile long facetCacheExpiry = 0;
    private volatile String cachedToken = null;
    private volatile long tokenExpiry = 0;

    @PostConstruct
    public void init() {
        log.info("FacetClient initialized with baseUrl={} enabled={}", baseUrl, enabled);
    }

    public FacetClient() {
        this.objectMapper = new ObjectMapper();
    }

    private HttpURLConnection openConnection(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        return conn;
    }

    private String readBody(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
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

            HttpURLConnection conn = openConnection(baseUrl + "/api/v1/facets?enabledOnly=true", "GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);

            int status = conn.getResponseCode();
            String body = readBody(conn);

            if (status == 200) {
                List<Map<String, Object>> facets = objectMapper.readValue(
                        body, new TypeReference<List<Map<String, Object>>>() {});
                cachedFacets = facets;
                facetCacheExpiry = System.currentTimeMillis() + (cacheTtlSeconds * 1000);
                log.info("FacetClient: fetched {} enabled facets from NexaRank", facets.size());
                return facets;
            } else {
                log.warn("FacetClient: facets fetch returned HTTP {}: {}", status, body);
            }
        } catch (Exception e) {
            log.warn("FacetClient: Failed to fetch facets from NexaRank: {}", e.getMessage());
        }
        log.warn("FacetClient: returning empty facets list");
        return Collections.emptyList();
    }


    /**
     * Fetch facets filtered by visibility rules for the given context.
     * Bypasses cache since context changes per request.
     * TODO NR-42: move to NexaRank Java SDK v1.0.3
     */
    public List<Map<String, Object>> getEnabledFacets(java.util.Map<String, String> selectedFacets) {
        if (!enabled || selectedFacets == null || selectedFacets.isEmpty()) {
            return getEnabledFacets();
        }
        try {
            String token = getToken();
            if (token == null) return getEnabledFacets();

            StringBuilder url = new StringBuilder(baseUrl + "/api/v1/facets?enabledOnly=true");
            for (java.util.Map.Entry<String, String> entry : selectedFacets.entrySet()) {
                url.append("&facet_")
                   .append(java.net.URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                   .append("=")
                   .append(java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
            }

            HttpURLConnection conn = openConnection(url.toString(), "GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);

            int status = conn.getResponseCode();
            String body = readBody(conn);

            if (status == 200) {
                List<Map<String, Object>> facets = objectMapper.readValue(
                        body, new TypeReference<List<Map<String, Object>>>() {});
                log.debug("FacetClient: fetched {} context-aware facets for context={}",
                        facets.size(), selectedFacets);
                return facets;
            }
        } catch (Exception e) {
            log.warn("FacetClient: context-aware facet fetch failed: {}", e.getMessage());
        }
        return getEnabledFacets();
    }

    private String getToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken;
        }
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("username", username, "password", password));

            HttpURLConnection conn = openConnection(baseUrl + "/api/v1/auth/login", "POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            String respBody = readBody(conn);

            if (status == 200) {
                // Login response carries extra fields (role, permissions[], etc.) beyond the
                // token - read as a tree instead of Map<String,String> so those don't break
                // deserialization (permissions is a JSON array, not a String).
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(respBody);
                cachedToken = node.has("token") ? node.get("token").asText() : null;
                tokenExpiry = System.currentTimeMillis() + (23 * 60 * 60 * 1000);
                return cachedToken;
            } else {
                log.warn("FacetClient login returned HTTP {}: {}", status, respBody);
            }
        } catch (Exception e) {
            log.warn("FacetClient login failed: {}", e.getMessage());
        }
        return null;
    }
}
