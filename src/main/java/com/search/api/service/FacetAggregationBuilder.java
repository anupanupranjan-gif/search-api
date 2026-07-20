package com.search.api.service;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.HistogramBucket;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FacetAggregationBuilder {

    private static final Logger log = LoggerFactory.getLogger(FacetAggregationBuilder.class);

    public Map<String, Aggregation> buildAggregations(List<Map<String, Object>> facetConfigs) {
        Map<String, Aggregation> aggs = new LinkedHashMap<>();

        for (Map<String, Object> facet : facetConfigs) {
            String fieldName = (String) facet.get("fieldName");
            String facetType = (String) facet.get("facetType");
            if (fieldName == null || facetType == null) continue;

            try {
                switch (facetType) {
                    case "TERMS", "BOOLEAN" -> {
                        int maxValues = facet.get("maxValues") instanceof Number n
                                ? n.intValue() : 10;
                        aggs.put("facet_" + fieldName, Aggregation.of(a -> a
                                .terms(t -> t.field(fieldName).size(maxValues))));
                    }
                    case "RANGE" -> {
                        double interval = facet.get("rangeInterval") instanceof Number n
                                ? n.doubleValue() : 50.0;
                        aggs.put("facet_" + fieldName, Aggregation.of(a -> a
                                .histogram(h -> h.field(fieldName).interval(interval).minDocCount(1))));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to build aggregation for field {}: {}", fieldName, e.getMessage());
            }
        }
        return aggs;
    }

    public Map<String, Object> extractFacets(Map<String, Aggregate> aggregations,
                                              List<Map<String, Object>> facetConfigs) {
        Map<String, Object> facets = new LinkedHashMap<>();

        for (Map<String, Object> facet : facetConfigs) {
            String fieldName = (String) facet.get("fieldName");
            String facetType = (String) facet.get("facetType");
            String displayLabel = (String) facet.get("displayLabel");
            boolean showCount = Boolean.TRUE.equals(facet.get("showCount"));
            String aggKey = "facet_" + fieldName;

            if (fieldName == null || !aggregations.containsKey(aggKey)) continue;

            try {
                Map<String, Object> facetResult = new LinkedHashMap<>();
                facetResult.put("fieldName", fieldName);
                facetResult.put("displayLabel", displayLabel != null ? displayLabel : fieldName);
                facetResult.put("facetType", facetType);
                facetResult.put("showCount", showCount);

                if ("TERMS".equals(facetType)) {
                    var termsAgg = aggregations.get(aggKey).sterms();
                    List<Map<String, Object>> buckets = new ArrayList<>();
                    for (StringTermsBucket bucket : termsAgg.buckets().array()) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("value", bucket.key().stringValue());
                        if (showCount) b.put("count", bucket.docCount());
                        buckets.add(b);
                    }
                    facetResult.put("buckets", buckets);

                } else if ("BOOLEAN".equals(facetType)) {
                    // ES aggregates boolean fields as long terms (0/1) with a keyAsString "true"/"false"
                    var termsAgg = aggregations.get(aggKey).lterms();
                    List<Map<String, Object>> buckets = new ArrayList<>();
                    for (LongTermsBucket bucket : termsAgg.buckets().array()) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("value", bucket.keyAsString() != null ? bucket.keyAsString() : String.valueOf(bucket.key()));
                        if (showCount) b.put("count", bucket.docCount());
                        buckets.add(b);
                    }
                    facetResult.put("buckets", buckets);

                } else if ("RANGE".equals(facetType)) {
                    var histAgg = aggregations.get(aggKey).histogram();
                    List<Map<String, Object>> buckets = new ArrayList<>();
                    for (HistogramBucket bucket : histAgg.buckets().array()) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("from", bucket.key());
                        b.put("to", bucket.key() + (facet.get("rangeInterval") instanceof Number n
                                ? n.doubleValue() : 50.0));
                        if (showCount) b.put("count", bucket.docCount());
                        buckets.add(b);
                    }
                    facetResult.put("buckets", buckets);
                }

                facets.put(fieldName, facetResult);
            } catch (Exception e) {
                log.warn("Failed to extract facet {}: {}", fieldName, e.getMessage());
            }
        }
        return facets;
    }
}
