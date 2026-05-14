package com.search.api.service;

import ai.djl.inference.Predictor;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.search.api.model.nexarank.NexaRankRule;
import com.search.api.model.SearchResponse.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final String INDEX = "products";

    private final ElasticsearchClient esClient;
    private final Predictor<String[], float[][]> embeddingPredictor;
    private final CacheService cacheService;
    private final NexaRankClient nexaRankClient;
    private final NexaRankQueryEnricher nexaRankEnricher;

    @Value("${search.vector.candidates:150}")
    private int vectorCandidates;

    @Value("${search.vector.k:50}")
    private int vectorK;

    @Value("${search.hybrid.vector-weight:0.7}")
    private float vectorWeight;

    @Value("${search.hybrid.keyword-weight:0.3}")
    private float keywordWeight;

    public SearchService(ElasticsearchClient esClient,
                         Predictor<String[], float[][]> embeddingPredictor,
                         CacheService cacheService,
                         NexaRankClient nexaRankClient,
                         NexaRankQueryEnricher nexaRankEnricher) {
        this.esClient = esClient;
        this.embeddingPredictor = embeddingPredictor;
        this.cacheService = cacheService;
        this.nexaRankClient = nexaRankClient;
        this.nexaRankEnricher = nexaRankEnricher;
    }

    public com.search.api.model.SearchResponse search(com.search.api.model.SearchRequest req) throws Exception {
        long start = System.currentTimeMillis();

        boolean isMatchAll = req.getQuery() == null || req.getQuery().isBlank()
                || req.getQuery().trim().equals("*");

        // Check cache
        String cacheKey = cacheService.searchKey(req.getQuery(), req.getMode(),
                req.getCategory(), req.getBrand(), req.getMinPrice(), req.getMaxPrice(), req.getPage());
        List<SearchHit> cachedHits = cacheService.getSearchResults(cacheKey);
        if (cachedHits != null) {
            long took = System.currentTimeMillis() - start;
            log.info("Cache HIT [{}] query='{}' took={}ms", req.getMode(), req.getQuery(), took);
            return new com.search.api.model.SearchResponse(cachedHits.size(), req.getPage(), req.getSize(),
                    req.getMode() + ":cached", took, cachedHits);
        }

        // Fetch NexaRank rules for this query
        List<NexaRankRule> rules = Collections.emptyList();
        if (!isMatchAll) {
            rules = nexaRankClient.getRulesForQuery(req.getQuery());
            if (!rules.isEmpty()) {
                log.info("NexaRank: {} rules found for query='{}'", rules.size(), req.getQuery());
                // Apply synonym expansion to query before search
                String expandedQuery = nexaRankEnricher.applyQueryExpansion(req.getQuery(), rules);
                if (!expandedQuery.equals(req.getQuery())) {
                    req.setQuery(expandedQuery);
                }
            }
        }

        final List<NexaRankRule> finalRules = rules;

        List<SearchHit> hits = isMatchAll
                ? matchAllSearch(req)
                : switch (req.getMode()) {
                    case "vector"  -> vectorSearch(req, finalRules);
                    case "keyword" -> keywordSearch(req, finalRules);
                    default        -> hybridSearch(req, finalRules);
                };

        long took = System.currentTimeMillis() - start;
        log.info("Search [{}] query='{}' results={} took={}ms nexarank_rules={}",
                req.getMode(), req.getQuery(), hits.size(), took, finalRules.size());

        cacheService.putSearchResults(cacheKey, hits);

        return new com.search.api.model.SearchResponse(hits.size(), req.getPage(), req.getSize(), req.getMode(), took, hits);
    }

    // ── Match-all: for category browsing with no query ────────────────────────

    private List<SearchHit> matchAllSearch(com.search.api.model.SearchRequest req) throws Exception {
        Query filterQuery = buildFilterQuery(req);

        Query finalQuery = filterQuery != null
                ? BoolQuery.of(b -> b.filter(filterQuery))._toQuery()
                : new MatchAllQuery.Builder().build()._toQuery();

        SearchRequest esReq = SearchRequest.of(r -> r
                .index(INDEX)
                .query(finalQuery)
                .from(req.getPage() * req.getSize())
                .size(req.getSize())
        );

        return executeSearch(esReq);
    }

    // ── Hybrid: weighted combination of vector + BM25 scores ─────────────────

    private List<SearchHit> hybridSearch(com.search.api.model.SearchRequest req,
                                          List<NexaRankRule> rules) throws Exception {
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
        Query finalQuery = nexaRankEnricher.applyRules(scriptScoreQuery, rules);

        SearchRequest esReq = SearchRequest.of(r -> r
                .index(INDEX)
                .query(finalQuery)
                .from(req.getPage() * req.getSize())
                .size(req.getSize())
        );

        return executeSearch(esReq);
    }

    // ── Vector-only: kNN search ───────────────────────────────────────────────

    private List<SearchHit> vectorSearch(com.search.api.model.SearchRequest req,
                                          List<NexaRankRule> rules) throws Exception {
        float[] queryVector = embed(req.getQuery());
        Query filterQuery   = buildFilterQuery(req);

        int k          = Math.min(req.getSize(), vectorK);
        int candidates = Math.max(k * 3, vectorCandidates);

        final Query finalFilter = filterQuery;
        SearchRequest esReq = SearchRequest.of(r -> {
            var builder = r.index(INDEX)
                    .knn(knn -> {
                        var knnBuilder = knn
                                .field("product_vector")
                                .queryVector(toList(queryVector))
                                .k(k)
                                .numCandidates(candidates);
                        if (finalFilter != null) {
                            knnBuilder.filter(finalFilter);
                        }
                        return knnBuilder;
                    })
                    .size(req.getSize());
            return builder;
        });

        // Note: PIN rules not applied to kNN — kNN doesn't support pinned query wrapper
        // BOOST/BURY applied via rescore if needed in future
        return executeSearch(esReq);
    }

    // ── Keyword-only: BM25 multi-match ───────────────────────────────────────

    private List<SearchHit> keywordSearch(com.search.api.model.SearchRequest req,
                                           List<NexaRankRule> rules) throws Exception {
        Query bm25Query   = buildBm25Query(req.getQuery());
        Query filterQuery = buildFilterQuery(req);

        Query baseQuery = filterQuery != null
                ? BoolQuery.of(b -> b.must(bm25Query).filter(filterQuery))._toQuery()
                : bm25Query;

        // Apply NexaRank rules
        Query finalQuery = nexaRankEnricher.applyRules(baseQuery, rules);

        SearchRequest esReq = SearchRequest.of(r -> r
                .index(INDEX)
                .query(finalQuery)
                .from(req.getPage() * req.getSize())
                .size(req.getSize())
        );

        return executeSearch(esReq);
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

    private List<SearchHit> executeSearch(SearchRequest esReq) throws Exception {
        SearchResponse<Map> response = esClient.search(esReq, Map.class);

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
