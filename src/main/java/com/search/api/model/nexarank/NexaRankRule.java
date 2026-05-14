package com.search.api.model.nexarank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)

public class NexaRankRule {

    private String id;
    private String type;
    private String query;
    private List<String> pinnedIds;
    private String boostField;
    private String boostValue;
    private Float boostFactor;
    private List<String> synonyms;
    private boolean enabled;
    private String status;

    public enum RuleType {
        PIN, BOOST, BURY, SYNONYM
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public List<String> getPinnedIds() { return pinnedIds; }
    public void setPinnedIds(List<String> pinnedIds) { this.pinnedIds = pinnedIds; }
    public String getBoostField() { return boostField; }
    public void setBoostField(String boostField) { this.boostField = boostField; }
    public String getBoostValue() { return boostValue; }
    public void setBoostValue(String boostValue) { this.boostValue = boostValue; }
    public Float getBoostFactor() { return boostFactor; }
    public void setBoostFactor(Float boostFactor) { this.boostFactor = boostFactor; }
    public List<String> getSynonyms() { return synonyms; }
    public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
