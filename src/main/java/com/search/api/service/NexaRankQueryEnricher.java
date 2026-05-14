package com.search.api.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.json.JsonData;
import com.search.api.model.nexarank.NexaRankRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class NexaRankQueryEnricher {

    private static final Logger log = LoggerFactory.getLogger(NexaRankQueryEnricher.class);

    /**
     * Applies NexaRank rules to expand the query string.
     * SYNONYM rules expand the query before ES query building.
     */
    public String applyQueryExpansion(String query, List<NexaRankRule> rules) {
        for (NexaRankRule rule : rules) {
            if ("SYNONYM".equals(rule.getType()) && rule.getSynonyms() != null) {
                String synonyms = String.join(" ", rule.getSynonyms());
                String expanded = query + " " + synonyms;
                log.info("NexaRank SYNONYM expanded '{}' -> '{}'", query, expanded);
                return expanded;
            }
        }
        return query;
    }

    /**
     * Wraps an existing ES query with NexaRank rules.
     * Applies BOOST, BURY, and PIN rules.
     */
    public Query applyRules(Query baseQuery, List<NexaRankRule> rules) {
        if (rules == null || rules.isEmpty()) return baseQuery;

        Query result = baseQuery;

        for (NexaRankRule rule : rules) {
            result = switch (rule.getType()) {
                case "BOOST" -> applyBoost(result, rule);
                case "BURY"  -> applyBury(result, rule);
                case "PIN"   -> applyPin(result, rule);
                default      -> result;
            };
        }

        return result;
    }

    private Query applyBoost(Query baseQuery, NexaRankRule rule) {
        if (rule.getBoostField() == null || rule.getBoostValue() == null) return baseQuery;

        float factor = rule.getBoostFactor() != null ? rule.getBoostFactor() : 1.5f;

        log.info("NexaRank BOOST applied: {}={} factor={}",
                rule.getBoostField(), rule.getBoostValue(), factor);

        return FunctionScoreQuery.of(fs -> fs
                .query(baseQuery)
                .functions(f -> f
                        .filter(TermQuery.of(t -> t
                                .field(rule.getBoostField())
                                .value(FieldValue.of(rule.getBoostValue()))
                        )._toQuery())
                        .weight((double) factor)
                )
                .scoreMode(FunctionScoreMode.Multiply)
                .boostMode(FunctionBoostMode.Multiply)
        )._toQuery();
    }

    private Query applyBury(Query baseQuery, NexaRankRule rule) {
        if (rule.getBoostField() == null || rule.getBoostValue() == null) return baseQuery;

        float factor = rule.getBoostFactor() != null ? rule.getBoostFactor() : 0.1f;

        log.info("NexaRank BURY applied: {}={} factor={}",
                rule.getBoostField(), rule.getBoostValue(), factor);

        return FunctionScoreQuery.of(fs -> fs
                .query(baseQuery)
                .functions(f -> f
                        .filter(TermQuery.of(t -> t
                                .field(rule.getBoostField())
                                .value(FieldValue.of(rule.getBoostValue()))
                        )._toQuery())
                        .weight((double) factor)
                )
                .scoreMode(FunctionScoreMode.Multiply)
                .boostMode(FunctionBoostMode.Multiply)
        )._toQuery();
    }

    private Query applyPin(Query baseQuery, NexaRankRule rule) {
        if (rule.getPinnedIds() == null || rule.getPinnedIds().isEmpty()) return baseQuery;

        log.info("NexaRank PIN applied: ids={}", rule.getPinnedIds());

        List<String> ids = rule.getPinnedIds();

        return PinnedQuery.of(p -> p
                .ids(ids)
                .organic(baseQuery)
        )._toQuery();
    }
}
