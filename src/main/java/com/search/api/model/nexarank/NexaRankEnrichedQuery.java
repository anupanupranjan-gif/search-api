// Copyright (c) 2026 Anup Ranjan. Licensed under Apache 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
package com.search.api.model.nexarank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Client-side model for NexaRank enrichment response.
 * Mirrors com.nexarank.api.model.EnrichedQuery
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NexaRankEnrichedQuery {

    private String originalQuery;
    private String expandedQuery;
    private String engineType;
    private List<BoostInstruction> boosts;
    private List<PinInstruction> pins;
    private List<BuryInstruction> buries;
    private String redirectUrl;
    private Map<String, Object> engineDsl;
    private List<String> appliedRules;
    private long processingMs;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BoostInstruction(String field, String value, float factor) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PinInstruction(String productId, int position) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuryInstruction(String field, String value, float factor) {}

    public static NexaRankEnrichedQuery passthrough(String query) {
        NexaRankEnrichedQuery r = new NexaRankEnrichedQuery();
        r.setOriginalQuery(query);
        r.setExpandedQuery(query);
        r.setBoosts(List.of());
        r.setPins(List.of());
        r.setBuries(List.of());
        r.setAppliedRules(List.of());
        return r;
    }

    public boolean hasRules() {
        return (appliedRules != null && !appliedRules.isEmpty());
    }

    public int getAppliedRulesCount() {
        return appliedRules == null ? 0 : appliedRules.size();
    }

    public boolean hasBoosts() { return boosts != null && !boosts.isEmpty(); }
    public boolean hasPins()   { return pins != null && !pins.isEmpty(); }
    public boolean hasBuries() { return buries != null && !buries.isEmpty(); }
    public boolean isRedirect(){ return redirectUrl != null && !redirectUrl.isBlank(); }

    // Getters and setters
    public String getOriginalQuery() { return originalQuery; }
    public void setOriginalQuery(String q) { this.originalQuery = q; }

    public String getExpandedQuery() { return expandedQuery; }
    public void setExpandedQuery(String q) { this.expandedQuery = q; }

    public String getEngineType() { return engineType; }
    public void setEngineType(String t) { this.engineType = t; }

    public List<BoostInstruction> getBoosts() { return boosts; }
    public void setBoosts(List<BoostInstruction> boosts) { this.boosts = boosts; }

    public List<PinInstruction> getPins() { return pins; }
    public void setPins(List<PinInstruction> pins) { this.pins = pins; }

    public List<BuryInstruction> getBuries() { return buries; }
    public void setBuries(List<BuryInstruction> buries) { this.buries = buries; }

    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String url) { this.redirectUrl = url; }

    public Map<String, Object> getEngineDsl() { return engineDsl; }
    public void setEngineDsl(Map<String, Object> dsl) { this.engineDsl = dsl; }

    public List<String> getAppliedRules() { return appliedRules; }
    public void setAppliedRules(List<String> rules) { this.appliedRules = rules; }

    public long getProcessingMs() { return processingMs; }
    public void setProcessingMs(long ms) { this.processingMs = ms; }
}
