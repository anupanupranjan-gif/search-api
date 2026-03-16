# search-api

A Spring Boot REST API that provides hybrid product search over a 34,000+ product catalog indexed in Elasticsearch. Supports three search modes — BM25 keyword, vector semantic, and hybrid — with faceted filtering by category, brand, and price.

Built as Phase 3 of the SearchX platform, a full-stack AI-powered eCommerce search system running on Kubernetes.

---

## What It Does

search-api receives a search query, generates a 384-dimension embedding vector using the `all-MiniLM-L6-v2` model via Deep Java Library (DJL), and executes one of three search strategies against Elasticsearch 8.12.2. Results are ranked by relevance and returned with product metadata and scores.

```
HTTP GET /api/v1/search?q=wireless+headphones&mode=hybrid
          │
          ▼
   EmbeddingService (DJL + PyTorch)
   all-MiniLM-L6-v2 → float[384]
          │
          ▼
   SearchService
   ├── hybrid:  script_score (0.7 × cosine + 0.3 × BM25)
   ├── vector:  kNN on product_vector field
   └── keyword: multi_match on title, description, brand, category
          │
          ▼
   Elasticsearch 8.12.2 (ECK, in-cluster)
          │
          ▼
   JSON response with hits, scores, total, tookMs
```

---

## API Endpoints

### Search
```
GET /api/v1/search
```

Parameters:

| Parameter | Required | Default | Description |
|---|---|---|---|
| `q` | yes | | Search query text |
| `mode` | no | `hybrid` | `hybrid`, `vector`, or `keyword` |
| `category` | no | | Filter by category (exact match) |
| `brand` | no | | Filter by brand (exact match) |
| `minPrice` | no | | Minimum price filter |
| `maxPrice` | no | | Maximum price filter |
| `page` | no | `0` | Page number (0-indexed) |
| `size` | no | `20` | Results per page (max 100) |

Example:
```bash
curl "http://localhost:8080/api/v1/search?q=wireless+headphones&mode=hybrid&category=All+Electronics&size=5"
```

Response:
```json
{
  "total": 3,
  "page": 0,
  "size": 5,
  "mode": "hybrid",
  "tookMs": 74,
  "hits": [
    {
      "productId": "B09XYZ",
      "title": "Sony WH-1000XM5 Wireless Headphones",
      "brand": "Sony",
      "category": "All Electronics",
      "price": 279.99,
      "rating": 4.7,
      "ratingCount": 12453,
      "score": 12.34,
      "description": "...",
      "productVector": [0.023, -0.041, ...]
    }
  ]
}
```

### Product Lookup
```
GET /api/v1/products/{id}
```

Returns a single product by Elasticsearch document ID.

---

## Search Modes

**Hybrid** (default): Combines vector cosine similarity and BM25 keyword scoring using a script score query. Weights are configurable — default is 70% vector, 30% BM25. Best for most queries.

**Vector**: Pure kNN search on the `product_vector` field using cosine similarity. Good for semantic/conceptual queries where exact keyword matches don't matter.

**Keyword**: Multi-match BM25 query across `title^3`, `brand^2`, `description^1`, `category^1.5` with fuzzy matching. Best for exact product searches.

**Match-all**: When `q=*` or empty, falls back to a `match_all` query with filters applied. Used by category browse pages in the UI.

---

## Stack

| Component | Version |
|---|---|
| Java | 25 |
| Spring Boot | 4.0.3 |
| Elasticsearch Java Client | 8.12.2 |
| DJL (Deep Java Library) | 0.28.0 |
| PyTorch (via DJL) | 2.2.2 |
| Embedding model | all-MiniLM-L6-v2 (384 dims) |

---

## Embedding Model

The API uses `sentence-transformers/all-MiniLM-L6-v2` loaded via DJL's model zoo. The model and PyTorch native libraries are bundled directly into the Docker image to avoid runtime downloads — important for air-gapped Kubernetes environments like Kind.

The model cache is at `/root/.djl.ai/cache` and the PyTorch native libs are at `/root/.djl.ai/pytorch` inside the container.

Query embeddings are L2-normalized to unit vectors, matching the normalization applied during indexing, which enables cosine similarity via dot product.

---

## SSL Configuration

Elasticsearch runs under ECK (Elastic Cloud on Kubernetes) with a self-signed TLS certificate. The ES client is configured to trust all certificates using a custom `SSLContext` with `NoopHostnameVerifier`. This is intentional for a local Kind cluster — do not use in production without proper cert management.

---

## Local Development

```bash
# Port-forward ECK Elasticsearch
kubectl port-forward svc/searchx-es-http -n elasticsearch 9200:9200 &

# Run with env vars
ES_HOST=localhost \
ES_PORT=9200 \
ES_SCHEME=https \
ES_SSL_ENABLED=true \
ES_SSL_VERIFY=false \
ES_USERNAME=elastic \
ES_PASSWORD=<eck-password> \
mvn spring-boot:run
```

Test:
```bash
curl "http://localhost:8080/api/v1/search?q=headphones&size=3"
```

---

## Kubernetes Deployment

The service runs as a 2-replica Deployment in the `default` namespace, managed by ArgoCD via Helm charts in [search-infra](https://github.com/anupanupranjan-gif/search-infra).

Accessible via nginx ingress at `/api/v1/`.

Environment variables are set via Helm values:

| Variable | Description |
|---|---|
| `ES_HOST` | Elasticsearch host (in-cluster DNS) |
| `ES_PORT` | Elasticsearch port (9200) |
| `ES_SCHEME` | `https` (ECK uses TLS) |
| `ES_SSL_ENABLED` | `true` |
| `ES_SSL_VERIFY` | `false` (self-signed cert) |
| `ES_USERNAME` | `elastic` |
| `ES_PASSWORD` | ECK-generated secret |

---

## Observability

Spring Boot Actuator exposes Prometheus metrics at `/actuator/prometheus`, scraped by the in-cluster Prometheus stack via a `ServiceMonitor`. Key metrics:

- `http_server_requests_seconds_count` — request count by URI, status, outcome
- `http_server_requests_seconds_sum` — cumulative latency
- `http_server_requests_seconds_max` — max observed latency

These feed the [observability-console](https://github.com/anupanupranjan-gif/observability-console) AI ops tool.

---

## Project Structure

```
search-api/
├── src/main/java/com/search/api/
│   ├── SearchApiApplication.java
│   ├── config/
│   │   ├── ElasticsearchConfig.java   # ES client with SSL trust-all
│   │   └── EmbeddingConfig.java       # DJL predictor setup
│   ├── controller/
│   │   └── SearchController.java      # GET /api/v1/search, /products/{id}
│   ├── model/
│   │   ├── SearchRequest.java
│   │   └── SearchResponse.java
│   └── service/
│       └── SearchService.java         # hybrid/vector/keyword/match-all logic
├── src/main/resources/
│   └── application.yml
├── djl-cache/                         # Bundled model cache (not in git)
├── djl-pytorch/                       # Bundled PyTorch native libs (not in git)
├── Dockerfile
└── pom.xml
```

---

## Part of SearchX

This repo is one component of the SearchX platform:

- [search-catalog-indexer](https://github.com/anupanupranjan-gif/search-catalog-indexer) — Spring Batch pipeline that indexes 34,311 products with vector embeddings
- [search-ui](https://github.com/anupanupranjan-gif/search-ui) — React eCommerce frontend (home, search, category pages)
- [prometheus-mcp](https://github.com/anupanupranjan-gif/prometheus-mcp) — Prometheus MCP server that exposes search-api metrics to AI assistants
- [observability-console](https://github.com/anupanupranjan-gif/observability-console) — AI-powered ops console linked from Grafana
- [search-infra](https://github.com/anupanupranjan-gif/search-infra) — Kubernetes manifests, Helm charts, ArgoCD, ECK, Terraform
