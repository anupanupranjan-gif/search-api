package com.search.api.service;

import ai.djl.inference.Predictor;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import java.util.Map;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchItem;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.nexarank.client.NexaRankEnrichedQuery;
import com.nexarank.client.NexaRankClient;
import com.search.api.model.SearchResponse.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final String INDEX = "products";

    private final ElasticsearchClient esClient;
    private final Predictor<String[], float[][]> embeddingPredictor;
    private final CacheService cacheService;
    private final NexaRankClient nexaRankClient;
    private final NexaRankQueryEnricher nexaRankEnricher;
    private final FacetClient facetClient;
    private final FacetAggregationBuilder facetAggregationBuilder;

    @Value("${search.vector.candidates:150}")
    private int vectorCandidates;

    @Value("${search.vector.k:50}")
    private int vectorK;

    @Value("${search.hybrid.vector-weight:0.7}")
    private float vectorWeight;

    @Value("${search.hybrid.keyword-weight:0.3}")
    private float keywordWeight;

    // Diversity (maxPerBrand/maxPerCategory) is enforced by over-fetching a small
    // multiple of the requested page size, then trimming client-side. Deliberately
    // capped and per-page (not globally correct across page boundaries) — a cheap
    // approximation was chosen over a fully-correct global re-ranking cache.
    @Value("${search.diversity.overfetch-multiplier:2}")
    private int diversityOverfetchMultiplier;

    @Value("${search.diversity.max-extra-fetch:40}")
    private int diversityMaxExtraFetch;

    // Hybrid = real fusion retrieval (Reciprocal Rank Fusion), not a linear blend of
    // raw BM25 + cosine scores. Elastic's own native `rrf` retriever implements the
    // same algorithm but is Platinum/Enterprise-licensed; this cluster runs Basic, so
    // this is an app-side equivalent: one _msearch round trip (BM25 channel + kNN
    // channel), fused in Java by rank rather than by score. rank-constant of 60
    // matches Elastic's own RRF default. Bounded to the top rrfWindowSize candidates
    // per channel — pages beyond that window return short/empty, the same accepted
    // "cheap, page-bounded, not globally correct" tradeoff already used by diversity.
    @Value("${search.hybrid.rrf.window-size:100}")
    private int rrfWindowSize;

    @Value("${search.hybrid.rrf.rank-constant:60}")
    private int rrfRankConstant;

    @Value("${nexarank.tenant-id:default}")
    private String nexaRankTenantId;

    @Value("${nexarank.project-id:main}")
    private String nexaRankProjectId;

    public SearchService(ElasticsearchClient esClient,
                         Predictor<String[], float[][]> embeddingPredictor,
                         CacheService cacheService,
                         NexaRankClient nexaRankClient,
                         NexaRankQueryEnricher nexaRankEnricher,
                         FacetClient facetClient,
                         FacetAggregationBuilder facetAggregationBuilder) {
        this.esClient = esClient;
        this.embeddingPredictor = embeddingPredictor;
        this.cacheService = cacheService;
        this.nexaRankClient = nexaRankClient;
        this.nexaRankEnricher = nexaRankEnricher;
        this.facetClient = facetClient;
        this.facetAggregationBuilder = facetAggregationBuilder;
    }

    public com.search.api.model.SearchResponse search(com.search.api.model.SearchRequest req) throws Exception {
        long start = System.currentTimeMillis();

        boolean isMatchAll = req.getQuery() == null || req.getQuery().isBlank()
                || req.getQuery().trim().equals("*");

        // Fetch enabled facets from NexaRank (always, even on cache hit)
        List<Map<String, Object>> facetConfigs = facetClient.getEnabledFacets(req.getSelectedFacets());

        // Check cache. The facet config set and the rules version are both folded into
        // the key so that a config/rule change in NexaRank immediately produces a fresh
        // cache entry instead of serving stale results for up to cache.search.ttl-seconds.
        // facetConfigs is cheap to refetch every request (above); rule enrichment is not
        // (it's the LLM-rewrite pipeline), so rules use a cheap version counter instead of
        // refetching the full enrichment just to detect staleness — see NR-100 (facets)
        // and the follow-up rules-cache bug this addresses.
        String facetSignature = String.valueOf(facetConfigs);
        String rulesVersion = cacheService.getRulesVersion(nexaRankTenantId, nexaRankProjectId);
        String cacheKey = cacheService.searchKey(req.getQuery(), req.getMode(),
                req.getCategory(), req.getBrand(), req.getMinPrice(), req.getMaxPrice(), req.getPage(),
                facetSignature, rulesVersion);
        CacheService.CachedSearch cached = cacheService.getSearchResults(cacheKey);
        if (cached != null) {
            long took = System.currentTimeMillis() - start;
            log.info("Cache HIT [{}] query='{}' took={}ms", req.getMode(), req.getQuery(), took);
            com.search.api.model.SearchResponse cachedResponse = new com.search.api.model.SearchResponse(
                    cached.totalHits(), req.getPage(), req.getSize(), req.getMode() + ":cached", took, cached.hits());
            cachedResponse.setFacets(cached.facets());
            return cachedResponse;
        }

        // Fetch NexaRank enrichment for this query
        NexaRankEnrichedQuery enriched = NexaRankEnrichedQuery.passthrough(req.getQuery());
        if (!isMatchAll) {
            enriched = nexaRankClient.enrich(req.getQuery(), req.getSessionId(), req.getSelectedFacets());
            if (enriched.hasRules()) {
                log.info("NexaRank: {} rules applied for query='{}'", enriched.getAppliedRulesCount(), req.getQuery());
                // Apply synonym expansion to query before search
                String expandedQuery = nexaRankEnricher.getExpandedQuery(req.getQuery(), enriched);
                if (!expandedQuery.equals(req.getQuery())) {
                    req.setQuery(expandedQuery);
                }
            }
        }
        final NexaRankEnrichedQuery finalEnriched = enriched;
        final List<Map<String, Object>> finalFacetConfigs = facetConfigs;

        SearchResult result = isMatchAll
                ? matchAllSearch(req, finalFacetConfigs)
                : switch (req.getMode()) {
                    case "vector"  -> vectorSearch(req, finalEnriched, finalFacetConfigs);
                    case "keyword" -> keywordSearch(req, finalEnriched, finalFacetConfigs);
                    default        -> hybridSearch(req, finalEnriched, finalFacetConfigs);
                };
        List<SearchHit> hits = result.hits();

        long took = System.currentTimeMillis() - start;
        log.info("Search [{}] query='{}' results={} took={}ms nexarank_rules={}",
                req.getMode(), req.getQuery(), hits.size(), took, finalEnriched.getAppliedRulesCount());

        // Cache the base (non-personalized) hits — personalization is per-session and
        // must never be baked into a response shared across every other session
        // searching the same thing. Applying it after the cache write, only on this
        // response, keeps the cache exactly as effective as before regardless of how
        // much traffic carries a sessionId (cache hits simply don't get personalized,
        // same trade-off already accepted for BOOST/BURY/PIN/SYNONYM rules on a hit).
        cacheService.putSearchResults(cacheKey, hits, result.facets(), result.totalHits());

        List<SearchHit> responseHits = applyPersonalization(hits, finalEnriched);

        com.search.api.model.SearchResponse searchResponse = new com.search.api.model.SearchResponse(
                result.totalHits(), req.getPage(), req.getSize(), req.getMode(), took, responseHits);
        searchResponse.setFacets(result.facets());
        return searchResponse;
    }

    // ── Match-all: for category browsing with no query ────────────────────────

    private SearchResult matchAllSearch(com.search.api.model.SearchRequest req,
                                      List<Map<String, Object>> facetConfigs) throws Exception {
        Query filterQuery = buildFilterQuery(req);

        Query finalQuery = filterQuery != null
                ? BoolQuery.of(b -> b.filter(filterQuery))._toQuery()
                : new MatchAllQuery.Builder().build()._toQuery();

        Map<String, Aggregation> aggs = facetAggregationBuilder.buildAggregations(facetConfigs);
        SearchRequest esReq = SearchRequest.of(r -> r
                .index(INDEX)
                .query(finalQuery)
                .from(req.getPage() * req.getSize())
                .size(req.getSize())
                .aggregations(aggs)
        );

        return executeSearch(esReq, facetConfigs);
    }

    // ── Hybrid: Reciprocal Rank Fusion of independent BM25 + kNN retrievals ────
    //
    // Elastic's guidance: retrieve BM25 and kNN as two independent candidate lists,
    // then fuse by RANK, not raw score — BM25 (unbounded, corpus-dependent) and
    // cosine similarity (bounded) aren't on comparable scales, so a fixed linear
    // blend of the two isn't meaningful across different queries. This is exactly
    // what Elastic's native `rrf` retriever does, but that requires a Platinum/
    // Enterprise license (confirmed via a live 403 against this Basic-licensed
    // cluster: "current license is non-compliant for [Reciprocal Rank Fusion]").
    // This method is the same algorithm implemented app-side: one _msearch round
    // trip carrying both queries (so it costs one network hop, not two), fused in
    // Java with the standard RRF formula (rank-constant 60, matching Elastic's own
    // default).
    private SearchResult hybridSearch(com.search.api.model.SearchRequest req,
                                   NexaRankEnrichedQuery enriched,
                                   List<Map<String, Object>> facetConfigs) throws Exception {
        float[] queryVector = embed(req.getQuery());

        Query bm25Query = buildBm25Query(req.getQuery());
        Query filterQuery = buildFilterQuery(req);

        Query bm25Base = filterQuery != null
                ? BoolQuery.of(b -> b.must(bm25Query).filter(filterQuery))._toQuery()
                : bm25Query;
        // BOOST/BURY only here — PIN is applied once, post-fusion, below (the same
        // limitation kNN already has: pinning can't be expressed inside a kNN clause,
        // so pinning is handled consistently in Java for the fused list instead).
        Query bm25Final = nexaRankEnricher.applyScoring(bm25Base, enriched);

        int k = rrfWindowSize;
        int candidates = Math.max(k * 3, vectorCandidates);
        Map<String, Aggregation> aggs = facetAggregationBuilder.buildAggregations(facetConfigs);

        final Query finalFilter = filterQuery;
        RequestItem bm25Item = RequestItem.of(i -> i
                .header(h -> h.index(INDEX))
                .body(b -> b.query(bm25Final).from(0).size(rrfWindowSize).aggregations(aggs)));

        RequestItem knnItem = RequestItem.of(i -> i
                .header(h -> h.index(INDEX))
                .body(b -> {
                    b.knn(knn -> {
                        var knnBuilder = knn.field("product_vector")
                                .queryVector(toList(queryVector))
                                .k(k)
                                .numCandidates(candidates);
                        if (finalFilter != null) knnBuilder.filter(finalFilter);
                        return knnBuilder;
                    });
                    b.size(rrfWindowSize);
                    return b;
                }));

        MsearchResponse<Map> msearchResponse = esClient.msearch(m -> m
                .index(INDEX)
                .searches(bm25Item, knnItem), Map.class);

        List<MultiSearchResponseItem<Map>> responses = msearchResponse.responses();
        MultiSearchItem<Map> bm25Result = responses.get(0).result();
        MultiSearchItem<Map> knnResult = responses.get(1).result();

        List<SearchHit> bm25Hits = parseHits(bm25Result.hits().hits());
        List<SearchHit> knnHits = parseHits(knnResult.hits().hits());

        Map<String, Object> facets = facetAggregationBuilder.extractFacets(bm25Result.aggregations(), facetConfigs);
        long totalHits = bm25Result.hits().total() != null
                ? bm25Result.hits().total().value()
                : bm25Hits.size();

        List<SearchHit> fused = fuseRrf(bm25Hits, knnHits, rrfRankConstant, req.getSize());
        if (enriched != null && enriched.hasPins()) {
            fused = applyPinsPostFusion(fused, enriched);
        }

        // Page window into the fused list — bounded by rrfWindowSize per channel,
        // same "cheap, page-bounded, not globally correct beyond the window" tradeoff
        // already accepted for diversity below.
        int fetchSize = effectiveFetchSize(req.getSize(), enriched);
        int from = req.getPage() * req.getSize();
        List<SearchHit> windowed = from < fused.size()
                ? fused.subList(from, Math.min(from + fetchSize, fused.size()))
                : Collections.emptyList();

        SearchResult result = new SearchResult(new ArrayList<>(windowed), facets, totalHits);
        return applyDiversity(result, enriched, req.getSize());
    }

    /**
     * Reciprocal Rank Fusion: score(d) = sum over each candidate list containing d
     * of 1/(rankConstant + rank), rank being the document's 1-based position in
     * that list. A document only needs to appear in ONE list to be included — this
     * (not the fusion formula itself) is what gives hybrid real recall beyond
     * keyword-only: a semantically close product with zero shared vocabulary can
     * still surface via the kNN list alone.
     */
    private List<SearchHit> fuseRrf(List<SearchHit> bm25Hits, List<SearchHit> knnHits, int rankConstant,
                                     int keywordVisibleSize) {
        Map<String, Double> rrfScore = new HashMap<>();
        Map<String, SearchHit> byId = new HashMap<>();
        List<String> order = new ArrayList<>();
        // "Semantic match" means: a keyword-only search wouldn't show this on the
        // results page the user is actually looking at — i.e. it's not in BM25's
        // top keywordVisibleSize, even though it may still be somewhere in BM25's
        // full rrfWindowSize candidate pool (which is fetched purely for fusion
        // quality, not something a keyword-only user would ever page down to).
        Set<String> bm25VisibleIds = new HashSet<>();

        for (int i = 0; i < bm25Hits.size(); i++) {
            SearchHit hit = bm25Hits.get(i);
            if (i < keywordVisibleSize) bm25VisibleIds.add(hit.getProductId());
            rrfScore.merge(hit.getProductId(), 1.0 / (rankConstant + i + 1), Double::sum);
            if (byId.putIfAbsent(hit.getProductId(), hit) == null) order.add(hit.getProductId());
        }
        for (int i = 0; i < knnHits.size(); i++) {
            SearchHit hit = knnHits.get(i);
            rrfScore.merge(hit.getProductId(), 1.0 / (rankConstant + i + 1), Double::sum);
            if (byId.putIfAbsent(hit.getProductId(), hit) == null) order.add(hit.getProductId());
        }

        List<SearchHit> fused = new ArrayList<>();
        for (String id : order) fused.add(byId.get(id));
        // Surface the RRF score as the hit's score so downstream diversity/
        // personalization (which sort by getScore()) operate on a meaningful signal.
        // semanticMatch flags a hit that never appeared in the BM25 candidate window at
        // all — i.e. keyword-only search couldn't have surfaced it, at any rank, within
        // the fetched window. That's the honest, demo-able "found via semantic
        // similarity" signal, distinct from a hit BM25 also matched but ranked lower.
        for (SearchHit hit : fused) {
            hit.setScore(rrfScore.getOrDefault(hit.getProductId(), 0.0).floatValue());
            hit.setSemanticMatch(!bm25VisibleIds.contains(hit.getProductId()));
        }
        fused.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return fused;
    }

    /**
     * Applies PIN once, on the fully-fused list, rather than per-channel. Unlike the
     * ES-native PinnedQuery used by keyword-only search, this can only pin a product
     * that's already among the fused candidates (matched BM25 or was in the kNN
     * window) — it can't force in a product unrelated to the query. Acceptable: a
     * pinned product is, in practice, almost always also a real match.
     */
    private List<SearchHit> applyPinsPostFusion(List<SearchHit> hits, NexaRankEnrichedQuery enriched) {
        List<String> pinIds = enriched.getPins().stream()
                .sorted((a, b) -> Integer.compare(a.position(), b.position()))
                .map(NexaRankEnrichedQuery.PinInstruction::productId)
                .toList();
        if (pinIds.isEmpty()) return hits;

        Map<String, SearchHit> byId = new HashMap<>();
        for (SearchHit h : hits) byId.put(h.getProductId(), h);

        List<SearchHit> result = new ArrayList<>();
        Set<String> pinned = new HashSet<>();
        for (String id : pinIds) {
            SearchHit hit = byId.get(id);
            if (hit != null) {
                result.add(hit);
                pinned.add(id);
            }
        }
        for (SearchHit h : hits) {
            if (!pinned.contains(h.getProductId())) result.add(h);
        }
        return result;
    }

    // ── Vector-only: kNN search ───────────────────────────────────────────────

    private SearchResult vectorSearch(com.search.api.model.SearchRequest req,
                                   NexaRankEnrichedQuery enriched,
                                   List<Map<String, Object>> facetConfigs) throws Exception {
        float[] queryVector = embed(req.getQuery());
        Query filterQuery   = buildFilterQuery(req);

        int fetchSize  = effectiveFetchSize(req.getSize(), enriched);
        int k          = Math.min(fetchSize, vectorK);
        int candidates = Math.max(k * 3, vectorCandidates);

        final Query finalFilter = filterQuery;
        final int finalK = k;
        SearchRequest esReq = SearchRequest.of(r -> {
            var builder = r.index(INDEX)
                    .knn(knn -> {
                        var knnBuilder = knn
                                .field("product_vector")
                                .queryVector(toList(queryVector))
                                .k(finalK)
                                .numCandidates(candidates);
                        if (finalFilter != null) {
                            knnBuilder.filter(finalFilter);
                        }
                        return knnBuilder;
                    })
                    .size(fetchSize);
            return builder;
        });

        // Note: PIN and personalization not applied to kNN — kNN doesn't support the
        // FunctionScore/PinnedQuery wrappers used elsewhere. Diversity still applies —
        // it's a post-hit-list trim, independent of how the candidates were retrieved.
        SearchResult result = executeSearch(esReq, facetConfigs);
        return applyDiversity(result, enriched, req.getSize());
    }

    // ── Keyword-only: BM25 multi-match ───────────────────────────────────────

    private SearchResult keywordSearch(com.search.api.model.SearchRequest req,
                                    NexaRankEnrichedQuery enriched,
                                    List<Map<String, Object>> facetConfigs) throws Exception {
        Query bm25Query   = buildBm25Query(req.getQuery());
        Query filterQuery = buildFilterQuery(req);

        Query baseQuery = filterQuery != null
                ? BoolQuery.of(b -> b.must(bm25Query).filter(filterQuery))._toQuery()
                : bm25Query;

        // Apply NexaRank rules
        Query finalQuery = nexaRankEnricher.applyEnrichment(baseQuery, enriched);

        Map<String, Aggregation> aggs = facetAggregationBuilder.buildAggregations(facetConfigs);
        int fetchSize = effectiveFetchSize(req.getSize(), enriched);
        SearchRequest esReq = SearchRequest.of(r -> r
                .index(INDEX)
                .query(finalQuery)
                .from(req.getPage() * req.getSize())
                .size(fetchSize)
                .aggregations(aggs)
        );

        SearchResult result = executeSearch(esReq, facetConfigs);
        return applyDiversity(result, enriched, req.getSize());
    }

    // ── Diversity: cheap per-page over-fetch + trim ───────────────────────────

    private int effectiveFetchSize(int requestedSize, NexaRankEnrichedQuery enriched) {
        if (enriched == null || !enriched.hasDiversity()) return requestedSize;
        int extra = Math.min(requestedSize * (diversityOverfetchMultiplier - 1), diversityMaxExtraFetch);
        return requestedSize + extra;
    }

    private SearchResult applyDiversity(SearchResult result, NexaRankEnrichedQuery enriched, int targetSize) {
        if (enriched == null || !enriched.hasDiversity() || result.hits().size() <= targetSize) {
            return result;
        }

        Integer maxPerBrand    = enriched.getMaxPerBrand();
        Integer maxPerCategory = enriched.getMaxPerCategory();
        List<SearchHit> hits   = result.hits();

        List<SearchHit> diversified = new ArrayList<>();
        Set<String> included = new HashSet<>();
        Map<String, Integer> brandCounts    = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();

        for (SearchHit hit : hits) {
            if (diversified.size() >= targetSize) break;
            String brand    = hit.getBrand();
            String category = hit.getCategory();
            boolean brandOk = maxPerBrand == null || brand == null
                    || brandCounts.getOrDefault(brand, 0) < maxPerBrand;
            boolean categoryOk = maxPerCategory == null || category == null
                    || categoryCounts.getOrDefault(category, 0) < maxPerCategory;
            if (brandOk && categoryOk) {
                diversified.add(hit);
                included.add(hit.getProductId());
                if (brand != null) brandCounts.merge(brand, 1, Integer::sum);
                if (category != null) categoryCounts.merge(category, 1, Integer::sum);
            }
        }

        // Over-fetch wasn't enough to fill a full page under the diversity limits —
        // backfill with the next-highest-ranked excluded hits rather than short-paging.
        if (diversified.size() < targetSize) {
            for (SearchHit hit : hits) {
                if (diversified.size() >= targetSize) break;
                if (!included.contains(hit.getProductId())) diversified.add(hit);
            }
        }

        log.debug("DIVERSITY trimmed {} candidates -> {} (maxPerBrand={}, maxPerCategory={})",
                hits.size(), diversified.size(), maxPerBrand, maxPerCategory);

        return new SearchResult(diversified, result.facets(), result.totalHits());
    }

    // ── Personalization: cheap in-memory re-score, applied after (never before)
    //    the cache boundary — see the comment in search() for why. ───────────────

    private List<SearchHit> applyPersonalization(List<SearchHit> hits, NexaRankEnrichedQuery enriched) {
        if (enriched == null || !enriched.hasPersonalization() || hits.isEmpty()) return hits;

        List<String> boostIds = enriched.getPersonalizedBoostIds();
        List<SearchHit> reranked = new ArrayList<>(hits);
        boolean anyMatched = false;

        for (SearchHit hit : reranked) {
            int rank = boostIds.indexOf(hit.getProductId());
            if (rank >= 0) {
                // Highest-clicked product gets the strongest (still modest) nudge —
                // deliberately much gentler than an explicit merchandising BOOST rule.
                double weight = Math.max(1.02, 1.20 - (0.02 * rank));
                hit.setScore((float) (hit.getScore() * weight));
                anyMatched = true;
            }
        }

        if (!anyMatched) return hits;

        reranked.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        log.debug("PERSONALIZATION re-ranked {} hits using {} boost ids", reranked.size(), boostIds.size());
        return reranked;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Query buildBm25Query(String queryText) {
        return MultiMatchQuery.of(mm -> mm
                .query(queryText)
                .fields("title^3", "description^1", "brand^2", "category^1.5")
                .type(TextQueryType.BestFields)
                .fuzziness("AUTO")
        )._toQuery();
    }

    private Query buildFilterQuery(com.search.api.model.SearchRequest req) {
        List<Query> filters = new ArrayList<>();

        if (req.getCategory() != null && !req.getCategory().isBlank()) {
            filters.add(TermQuery.of(t -> t
                    .field("category")
                    .value(FieldValue.of(req.getCategory()))
            )._toQuery());
        }

        if (req.getBrand() != null && !req.getBrand().isBlank()) {
            filters.add(TermQuery.of(t -> t
                    .field("brand")
                    .value(FieldValue.of(req.getBrand()))
            )._toQuery());
        }

        if (req.getMinPrice() != null || req.getMaxPrice() != null) {
            filters.add(RangeQuery.of(r -> {
                var rb = r.field("price");
                if (req.getMinPrice() != null) rb.gte(JsonData.of(req.getMinPrice()));
                if (req.getMaxPrice() != null) rb.lte(JsonData.of(req.getMaxPrice()));
                return rb;
            })._toQuery());
        }

        if (filters.isEmpty()) return null;
        if (filters.size() == 1) return filters.get(0);

        return BoolQuery.of(b -> b.filter(filters))._toQuery();
    }

    record SearchResult(List<SearchHit> hits, Map<String, Object> facets, long totalHits) {}

    private SearchResult executeSearch(SearchRequest esReq,
                                        List<Map<String, Object>> facetConfigs) throws Exception {
        SearchResponse<Map> response = esClient.search(esReq, Map.class);
        Map<String, Object> facets = facetAggregationBuilder.extractFacets(response.aggregations(), facetConfigs);
        long totalHits = response.hits().total() != null
                ? response.hits().total().value()
                : response.hits().hits().size();

        List<SearchHit> hits = parseHits(response.hits().hits());
        return new SearchResult(hits, facets, totalHits);
    }

    private List<SearchHit> parseHits(List<Hit<Map>> esHits) {
        List<SearchHit> hits = new ArrayList<>();
        for (Hit<Map> hit : esHits) {
            Map<String, Object> src = hit.source();
            if (src == null) continue;

            SearchHit sh = new SearchHit();
            sh.setProductId(hit.id());
            sh.setScore(hit.score() != null ? hit.score().floatValue() : 0f);
            sh.setTitle(str(src, "title"));
            sh.setDescription(str(src, "description"));
            sh.setCategory(str(src, "category"));
            sh.setBrand(str(src, "brand"));
            sh.setPrice(num(src, "price"));
            sh.setRating(num(src, "rating"));
            sh.setRatingCount(src.get("rating_count") instanceof Number n ? n.intValue() : null);

            Object vec = src.get("product_vector");
            if (vec instanceof List<?> list) {
                sh.setProductVector(list.stream()
                        .filter(v -> v instanceof Number)
                        .map(v -> ((Number) v).floatValue())
                        .toList());
            }

            hits.add(sh);
        }
        return hits;
    }

    public SearchHit getById(String id) throws Exception {
        var response = esClient.get(g -> g.index(INDEX).id(id), Map.class);
        if (!response.found()) return null;

        Map<String, Object> src = response.source();
        if (src == null) return null;

        SearchHit sh = new SearchHit();
        sh.setProductId(id);
        sh.setScore(1.0f);
        sh.setTitle(str(src, "title"));
        sh.setDescription(str(src, "description"));
        sh.setCategory(str(src, "category"));
        sh.setBrand(str(src, "brand"));
        sh.setPrice(num(src, "price"));
        sh.setRating(num(src, "rating"));
        sh.setRatingCount(src.get("rating_count") instanceof Number n ? n.intValue() : null);

        Object vec = src.get("product_vector");
        if (vec instanceof List<?> list) {
            sh.setProductVector(list.stream()
                    .filter(v -> v instanceof Number)
                    .map(v -> ((Number) v).floatValue())
                    .toList());
        }
        return sh;
    }

    private float[] embed(String text) throws Exception {
        float[] cached = cacheService.getEmbedding(text);
        if (cached != null) return cached;
        float[][] vectors = embeddingPredictor.predict(new String[]{text});
        float[] result = vectors[0];
        cacheService.putEmbedding(text, result);
        return result;
    }

    private List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    private String str(Map<String, Object> src, String key) {
        Object v = src.get(key);
        return v != null ? v.toString() : null;
    }

    private Double num(Map<String, Object> src, String key) {
        Object v = src.get(key);
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
