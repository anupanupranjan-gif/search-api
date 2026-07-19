// Copyright (c) 2026 Anup Ranjan. Licensed under Apache 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
package com.search.api.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import com.nexarank.client.NexaRankEnrichedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase 17: Updated to consume NexaRankEnrichedQuery DSL
 * instead of raw List<NexaRankRule>.
 *
 * Translation logic moved to nexarank-api adapters.
 * This class now just applies the pre-translated instructions to ES queries.
 */
@Service
public class NexaRankQueryEnricher {

    private static final Logger log = LoggerFactory.getLogger(NexaRankQueryEnricher.class);

    /**
     * Get the expanded query string after SYNONYM rule application.
     */
    public String getExpandedQuery(String originalQuery, NexaRankEnrichedQuery enriched) {
        if (enriched == null || enriched.getExpandedQuery() == null) return originalQuery;
        if (!enriched.getExpandedQuery().equals(originalQuery)) {
            log.info("NexaRank SYNONYM expanded '{}' -> '{}'",
                originalQuery, enriched.getExpandedQuery());
        }
        return enriched.getExpandedQuery();
    }

    /**
     * Apply enrichment instructions to an ES query.
     * Handles BOOST, BURY (via FunctionScore) and PIN (via PinnedQuery).
     */
    public Query applyEnrichment(Query baseQuery, NexaRankEnrichedQuery enriched) {
        if (enriched == null || !enriched.hasRules()) return baseQuery;

        Query result = baseQuery;

        // Apply boosts and buries via FunctionScore. Personalization is deliberately
        // NOT applied here — this query gets cached and shared across every session
        // searching the same thing, so a per-session boost can't be baked into it.
        // See SearchService.applyPersonalization(), applied post-cache instead.
        if (enriched.hasBoosts() || enriched.hasBuries()) {
            result = applyFunctionScore(result, enriched);
        }

        // Apply pins via PinnedQuery (wraps the function score result)
        if (enriched.hasPins()) {
            result = applyPins(result, enriched);
        }

        log.debug("NexaRank enrichment applied: {} rules, boosts={}, pins={}, buries={}",
            enriched.getAppliedRulesCount(),
            enriched.getBoosts().size(),
            enriched.getPins().size(),
            enriched.getBuries().size());

        return result;
    }

    private Query applyFunctionScore(Query baseQuery, NexaRankEnrichedQuery enriched) {
        return FunctionScoreQuery.of(fs -> {
            fs.query(baseQuery);
            fs.scoreMode(FunctionScoreMode.Multiply);
            fs.boostMode(FunctionBoostMode.Multiply);

            // Add boost functions
            for (NexaRankEnrichedQuery.BoostInstruction boost : enriched.getBoosts()) {
                fs.functions(f -> f
                    .filter(TermQuery.of(t -> t
                        .field(boost.field())
                        .value(FieldValue.of(boost.value()))
                    )._toQuery())
                    .weight((double) boost.factor())
                );
                log.debug("BOOST {}={} x{}", boost.field(), boost.value(), boost.factor());
            }

            // Add bury functions (low weight)
            for (NexaRankEnrichedQuery.BuryInstruction bury : enriched.getBuries()) {
                fs.functions(f -> f
                    .filter(TermQuery.of(t -> t
                        .field(bury.field())
                        .value(FieldValue.of(bury.value()))
                    )._toQuery())
                    .weight((double) bury.factor())
                );
                log.debug("BURY {}={} x{}", bury.field(), bury.value(), bury.factor());
            }

            return fs;
        })._toQuery();
    }

    private Query applyPins(Query baseQuery, NexaRankEnrichedQuery enriched) {
        List<String> ids = enriched.getPins().stream()
            .sorted((a, b) -> Integer.compare(a.position(), b.position()))
            .map(NexaRankEnrichedQuery.PinInstruction::productId)
            .toList();

        log.debug("PIN {} products", ids.size());

        return PinnedQuery.of(p -> p
            .ids(ids)
            .organic(baseQuery)
        )._toQuery();
    }
}
