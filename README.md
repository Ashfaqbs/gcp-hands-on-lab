# GCP Hands-On Lab

A from-scratch, hands-on tour of core Google Cloud services, built as a
Spring Boot 4 / Java 17 project. Every module is created and verified via
**both** the Console UI and the Java SDK, torn down the same session if it's
billable, and documented in-place - each package's `package-info.java` is
the "how we built this" record, not a separate wiki that goes stale.

No tutorials were copy-pasted blind: every module includes real bugs hit
along the way (SDK quirks, auth dead ends, naming/branding changes) and how
they were actually resolved, plus a **Cost** section per module explaining
what's metered, a formula, and where to check current pricing.

## Modules

| # | Package | Service | What it covers |
|---|---------|---------|-----------------|
| 01 | [`_01_iam`](src/main/java/com/ashfaq/gcplab/_01_iam) | IAM | Custom roles - define permission bundles, verify via API |
| 02 | [`_02_identities_bindings`](src/main/java/com/ashfaq/gcplab/_02_identities_bindings) | IAM | Service accounts, policy bindings, impersonation, proving permission boundaries |
| 03 | [`_03_storage`](src/main/java/com/ashfaq/gcplab/_03_storage) | Cloud Storage | Buckets/objects, uniform bucket-level access |
| 04 | [`_04_cloudsql`](src/main/java/com/ashfaq/gcplab/_04_cloudsql) | Cloud SQL (PostgreSQL) | Managed RDBMS, Cloud SQL Java Connector, full CRUD |
| 05 | [`_05_redis`](src/main/java/com/ashfaq/gcplab/_05_redis) | Memorystore for Redis | Private-VPC-only caching, SSH bastion tunnel pattern |
| 06 | [`_06_firestore`](src/main/java/com/ashfaq/gcplab/_06_firestore) | Firestore | Document DB, Native vs. MongoDB-compatibility mode |
| 07 | [`_07_gke`](src/main/java/com/ashfaq/gcplab/_07_gke) | GKE | Autopilot cluster, deploy/expose/verify/teardown |
| 08 | [`_08_vertexai`](src/main/java/com/ashfaq/gcplab/_08_vertexai) | Agent Platform (Vertex AI) | Raw prompting, hand-rolled RAG, a function-calling agent loop |
| 09 | [`_09_ai_commerce_search`](src/main/java/com/ashfaq/gcplab/_09_ai_commerce_search) | AI Commerce Search (Retail) | 703-product synthetic catalog, 100-query relevance audit |
| 10 | [`_10_elasticsearch`](src/main/java/com/ashfaq/gcplab/_10_elasticsearch) | Elasticsearch (self-hosted) | Same catalog, same queries, compared head-to-head against module 09 |
| 11 | [`_11_spanner`](src/main/java/com/ashfaq/gcplab/_11_spanner) | Cloud Spanner | Globally-consistent, horizontally-scalable SQL - contrasted against module 04's single-VM Cloud SQL |

## What's next

[`docs/roadmap.md`](docs/roadmap.md) - three planned tracks: a shopping-
assistant capstone (wiring module 08's agent loop to module 09's search as
a tool), an ML-fundamentals-through-Vertex-AI sequence, and Vertex AI
Vector Search as a third catalog-search comparison alongside modules 09/10.

## Cross-cutting docs

- [`docs/gcp-hierarchy.md`](docs/gcp-hierarchy.md) - org/folder/project/resource + separate billing-account tree
- [`docs/local-setup.md`](docs/local-setup.md) - gcloud CLI / ADC setup for running this locally
- [`docs/auth-approach.md`](docs/auth-approach.md) - which auth pattern each service uses and why (impersonation vs. password vs. attached identity)

## A genuine finding worth reading

Modules 09 and 10 run the *identical* synthetic catalog and a stratified set
of the *identical* search queries through Google's AI Commerce Search and a
self-hosted Elasticsearch node, scored with the same rubric. The result
wasn't a foregone conclusion: plain, untuned Elasticsearch actually
outperformed AI Commerce Search's default configuration on natural-language
queries (7.17/10 vs. 2.83/10), because Vertex's default index needs closer
literal/phrase alignment while Elasticsearch's stock analyzer catches any
overlapping word token. Full write-up and raw result files are in
`_09_ai_commerce_search` and `_10_elasticsearch`'s `package-info.java`,
alongside the [full 100-query scored audit](search-experiments/ai-commerce-search-audit.md).

## Running it

```bash
./mvnw compile
mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._01_iam.IamRoleLifecycleDemo
```

Each module's `package-info.java` documents its own prerequisites (which
APIs to enable, what to create in Console first) and exact run commands for
every demo class in that package. Nothing here is currently deployed or
running - see each module's "cleanup" section for the teardown commands used.

## Stack

Java 17, Spring Boot 4.1.1, Maven. GCP client libraries via
`com.google.cloud:libraries-bom`. No Elasticsearch client library dependency
was added for module 10 - plain `java.net.http.HttpClient` against the REST
API was enough.
