# TODO

Learning goals queued up (added 2026-11-08). New packages start at `_13` because `_11_spanner` and `_12_bigquery` already exist.

- [ ] 1. Vertex AI (Gemini) for search
  - Build on module 08 (Gemini, RAG, function calling) and the 09/10 search comparison
  - Candidate: Gemini-powered query understanding / reranking on top of the product catalog
- [ ] 2. BigQuery
  - Extend module 12 (schema, CRUD, jobs, scheduled queries, transforms, analytics)
- [ ] 3. Workflows service (Google Cloud Workflows)
  - Orchestrate GCP services and HTTP calls with YAML workflows
- [ ] 4. Spring Boot backend -> Helm chart -> Kubernetes deployment
  - Containerize the app, write a Helm chart, deploy to GKE Autopilot (module 07)
  - Remember teardown: GKE costs money while the cluster is up
