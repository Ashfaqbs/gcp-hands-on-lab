# Production readiness - what a search team needs beyond these modules

Every module's own `package-info.java` now has a **Production practices**
section covering what real work needs that the demo code deliberately
skipped (hotspotting, security, monitoring, cost control, code-level
patterns like client reuse). This doc is the complement: GCP services a
search team touches constantly that **aren't a module in this repo at all**,
plus the handful of cross-cutting practices that apply to every module at
once rather than any single one.

Nothing below is provisioned or coded - this is a reference for what to
reach for and why, written so encountering the need for one of these in real
work doesn't start from a blank page.

## Services genuinely missing from this repo

| Service | Why a search team needs it | Where it plugs into what's already here |
|---|---|---|
| **Pub/Sub** | The backbone of every "real-time" pattern referenced across the modules above: streaming UserEvents to Retail Search (`_09`'s Production practices #2), catalog change events driving incremental sync (`_09` #1), decoupling any producer from a slow/rate-limited downstream API. Without it, every one of those patterns collapses back into "call the API synchronously from the request path," which doesn't scale. | Sits between the catalog/inventory system and `_09`'s `UpdateProduct`/`WriteUserEvent` calls; also the natural transport for Elasticsearch (`_10`) ingestion pipelines. |
| **Secret Manager** | This repo's Cloud SQL demo (`_04_cloudsql`) reads `DB_PASSWORD` from an environment variable - fine for a local learning exercise, not how a real deployed service should handle a DB password, an Elasticsearch API key, or any other long-lived secret. Secret Manager gives versioned, IAM-controlled, audit-logged secret storage, with a documented rotation path - an env var has none of that. | Replaces the env-var pattern in `_04_cloudsql`, and would hold `_10_elasticsearch`'s API key/credentials in a real (security-enabled, see `_10`'s Production practices #1) deployment. |
| **Cloud Monitoring + Cloud Logging + Cloud Trace** (the observability triad) | Every module's Production practices section above says "track this metric" or "log this, never that" - Cloud Monitoring is where those metrics/alerts actually live (dashboards, alerting policies), Cloud Logging is the structured-log destination every GCP client library already writes to by default, and Cloud Trace gives distributed request tracing across a chain of calls (e.g. a search request that touches your API -> Retail Search -> a Vertex AI re-ranking call -> a Redis cache lookup) - without it, debugging a slow multi-hop search request means correlating timestamps across logs by hand. | Underlies every "monitoring"/"KPI" bullet in `_08`, `_09`, `_10`, `_11`'s new Production practices sections - none of those metrics have anywhere to live without this. |
| **Cloud Armor** | A public search API is a real target for scraping (a competitor harvesting your entire catalog via the search endpoint) and volumetric abuse - Cloud Armor is GCP's WAF + DDoS protection layer, sitting in front of a load balancer, with rate-limiting rules (e.g. cap requests/minute per IP) and pre-built rule sets (SQLi/XSS patterns) - the production answer to "what stops someone from hammering our search endpoint," which none of `_09`'s quota discussion addresses (quotas protect Google's infrastructure and your bill; Cloud Armor protects your own API surface). |
| **BigQuery** | The natural home for search-query logs, click/conversion event history, and A/B test results (see `_09`'s Production practices #3 and #5) once there's enough volume that ad-hoc log-grepping stops working - "which queries have the worst zero-results rate this month" or "did serving-config B actually convert better" are BigQuery SQL queries over exported event/log data, not something Cloud Monitoring dashboards are built for. Already on the roadmap (`docs/roadmap.md`'s "Other identified gaps") as a standalone module; called out again here specifically as the analytics backbone the KPI tracking in `_09`'s Production practices section assumes exists. |
| **Cloud CDN** | Caches HTTP responses at Google's edge, closer to the shopper than any regional cache - complements, doesn't replace, `_09`'s Production practices #4 (a Redis cache in front of Search reduces backend load/cost; a CDN in front of a public search-results HTML/JSON endpoint reduces latency for repeat/popular queries at the network edge, before the request even reaches your backend). Most relevant for cacheable, non-personalized responses (category browse pages more than personalized search results). |
| **API Gateway / Apigee** | If a search API is exposed to external partners/third parties (not just your own frontend), a gateway layer handles API-key issuance, per-consumer rate limiting/quotas, and request/response transformation without that logic living inside the search service itself - the production answer to "how do we give partner X limited, metered access to our search API" that this repo's internal, impersonated-service-account auth pattern doesn't address (that pattern is for your own backend calling GCP APIs, not for external callers calling YOUR API). |
| **Vertex AI Vector Search** | Already tracked as `docs/roadmap.md` Track C (a planned third catalog-search comparison point, embeddings-based semantic search) - listed here again specifically because it's the production-grade answer to `_08_vertexai`'s `SimpleRagDemo` doing manual cosine-similarity search in a loop: at real catalog scale, an approximate-nearest-neighbor index is a requirement, not an optimization. |
| **Cloud Tasks / Cloud Scheduler** | The reliable-execution layer behind several patterns referenced above: Cloud Scheduler triggers the periodic full-catalog reconciliation import (`_09` #1) and the Firestore export/backup cadence (`_06`'s Production practices #5) on a cron schedule; Cloud Tasks gives retryable, rate-limited async task execution (e.g. fanning out a large UserEvent backfill without overwhelming the Retail API's write quota). |

## Cross-cutting practices (apply to every module, not just one)

- **Infrastructure as Code.** Every resource in this repo was created via
  Console UI and/or `gcloud` CLI, deliberately - the point was learning what
  each click/command actually does. A real team manages the same resources
  via Terraform (or Google's own Deployment Manager equivalent) so
  environment creation is reviewable, repeatable, and diffable - "what
  changed in prod" becomes a git diff instead of a memory of which Console
  buttons got clicked in what order, and disaster recovery becomes
  `terraform apply` instead of manually recreating a dozen resources from
  notes.
- **CI/CD.** Nothing in this repo has automated build/test/deploy - every
  `mvn exec:java` run here is manual. A real pipeline (Cloud Build,
  GitHub Actions, or similar) runs tests (against emulators - see `_06`'s
  and `_11`'s Production practices sections on the Firestore/Spanner
  emulators - never real billed resources in CI) on every commit, and
  deploys through defined environments (dev -> staging -> prod) rather than
  a developer running code against the one shared project this repo used
  throughout.
- **Least-privilege IAM, consistently.** This repo's `backendDeveloper`
  custom role (see `_01_iam`) was deliberately extended incrementally,
  module by module, rather than granted broad access up front - the same
  discipline scales to a real team: a search-serving service's identity
  should hold exactly Search-read + minimal supporting permissions, never
  the same identity used for catalog-admin/import/purge operations (see
  `_09`'s Production practices #6) - separate identities per service/
  responsibility, not one shared "backend" identity for everything, once
  there's more than one team/service sharing a project.
- **A cost-alerting budget, not just manual audits.** This repo's own
  history includes multiple explicit "verify nothing is running that could
  bill me" passes done manually via `gcloud ... list` sweeps. A real project
  should have a Cloud Billing budget alert (Billing -> Budgets & alerts)
  configured to notify at defined spend thresholds - the automated,
  always-on version of the manual sweeps this repo relied on throughout its
  own development.

## See also

- [`docs/roadmap.md`](roadmap.md) - planned next modules (shopping-assistant
  capstone, ML fundamentals, Vector Search, plus the "other identified gaps"
  list this doc's table overlaps with and expands on for search-team context
  specifically).
- [`docs/auth-approach.md`](auth-approach.md) - the identity/auth pattern
  every module here already follows (impersonation, attached identity) -
  the foundation the "least-privilege IAM" practice above builds on.
