package com.search.api.service;

import ai.djl.inference.Predictor;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import java.util.Map;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
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

        // Check cache
        String cacheKey = cacheService.searchKey(req.getQuery(), req.getMode(),
                req.getCategory(), req.getBrand(), req.getMinPrice(), req.getMaxPrice(), req.getPage());
        CacheService.CachedSearch cached = cacheService.getSearchResults(cacheKey);
        if (cached != null) {
            long took = System.currentTimeMillis() - start;
            log.info("Cache HIT [{}] query='{}' took={}ms", req.getMode(), req.getQuery(), took);
            com.search.api.model.SearchResponse cachedResponse = new com.search.api.model.SearchResponse(
                    cached.hits().size(), req.getPage(), req.getSize(), req.getMode() + ":cached", took, cached.hits());
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
        cacheService.putSearchResults(cacheKey, hits, result.facets());

        List<SearchHit> responseHits = applyPersonalization(hits, finalEnriched);

        com.search.api.model.SearchResponse searchResponse = new com.search.api.model.SearchResponse(
                responseHits.size(), req.getPage(), req.getSize(), req.getMode(), took, responseHits);
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

    // ── Hybrid: weighted combination of vector + BM25 scores ─────────────────

    private SearchResult hybridSearch(com.search.api.model.SearchRequest req,
                                   NexaRankEnrichedQuery enriched,
                                   List<Map<String, Object>> facetConfigs) throws Exception {
        float[] queryVector = embed(req.getQuery());

        String scriptSource = """
            double vectorScore = cosineSimilarity(params.query_vector, 'product_vector') + 1.0;
            double bm25Score   = _score;
            return (params.vectorWeight * vectorScore) + (params.keywordWeight * bm25Score);
        """;

        Query bm25Query = buildBm25Query(req.getQuery());
        Query filterQuery = buildFilterQuery(req);

        Query scriptScoreQuery = ScriptScoreQuery.of(ss -> ss
                .query(filterQuery != null
                        ? BoolQuery.of(b -> b.must(bm25Query).filter(filterQuery))._toQuery()
                        : bm25Query)
                .script(s -> s.inline(i -> i
                        .source(scriptSource)
                        .params(Map.of(
                                "query_vector", JsonData.of(toList(queryVector)),
                                "vectorWeight", JsonData.of(vectorWeight),
                                "keywordWeight", JsonData.of(keywordWeight)
                        ))))
        )._toQuery();

        // Apply NexaRank rules
        Query finalQuery = nexaRankEnricher.applyEnrichment(scriptScoreQuery, enriched);

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

        return new SearchResult(diversified, result.facets());
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

    record SearchResult(List<SearchHit> hits, Map<String, Object> facets) {}

    private SearchResult executeSearch(SearchRequest esReq,
                                        List<Map<String, Object>> facetConfigs) throws Exception {
        SearchResponse<Map> response = esClient.search(esReq, Map.class);
        Map<String, Object> facets = facetAggregationBuilder.extractFacets(response, facetConfigs);

        List<SearchHit> hits = new ArrayList<>();
        for (Hit<Map> hit : response.hits().hits()) {
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
        return new SearchResult(hits, facets);
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
