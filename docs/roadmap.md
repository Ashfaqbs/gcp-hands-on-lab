# Roadmap - next modules (not started yet)

Parked here so a future session can execute directly without re-researching
or re-planning. Three threads, roughly independent, pick any order.

## Track A: "Rufus clone" - conversational shopping assistant (capstone)

Ties `_08_vertexai` and `_09_ai_commerce_search` together - the two pieces
already exist separately, this wires them into one thing.

**Plan:**
1. New package `_11_shopping_assistant`.
2. Reuse `_08`'s function-calling agent loop pattern (`SimpleAgentDemo`) as
   the base - same plan/execute/observe structure.
3. Define ONE tool: `searchCatalog(query: String) -> List<Product>` that
   calls the Retail Search API (`SearchServiceClient`, same pattern as
   `_09`'s `SearchQualityTest`) instead of hand-rolled RAG retrieval.
4. Re-import a smaller catalog (100-200 products is enough - reuse
   `ProductCatalogGenerator`, no need for all 703) since the catalog was
   purged and Retail doesn't keep data around for free.
5. System prompt: something like "You are a shopping assistant. Use
   searchCatalog to find real products before answering. Ask a clarifying
   question if the request is ambiguous (size, quantity, budget)."
6. Test conversations to try: multi-turn ("show me shampoo" -> "cheaper
   options?" -> "in the 500ml size"), ambiguous requests requiring a
   clarifying question, and requests the catalog genuinely can't satisfy
   (to see how it handles "no results" gracefully instead of hallucinating
   a product).
7. Optional stretch: a minimal chat UI (a single HTML page hitting a
   Spring REST endpoint) instead of only a console loop - only if the
   agent logic itself is working well first.
8. Cost: same per-token Vertex billing as `_08`, plus Retail Search calls
   (free tier covers a learning session, see `_09`'s pricing section) -
   nothing new to research there.
9. Teardown: same as `_09` - `CatalogPurgeDemo` (or a new smaller import
   for this alone) once done.

**Why this order:** zero new GCP concepts to learn, purely an integration
exercise - good warm-up before Track B/C which involve genuinely new
territory (ML, vector embeddings).

## Track B: ML fundamentals + Vertex AI's ML services

No ML background yet, so sequenced from easiest/most-guided to most
manual - each step should make the next step's concepts non-mysterious.

**Plan:**
1. **Vertex AI AutoML** (start here) - Console UI, tabular classification
   or regression on a small public dataset (e.g. a Kaggle CSV, or GCP's
   own sample datasets in Console). No code needed for the first pass -
   the point is to see train/test split, evaluation metrics (precision/
   recall/AUC), and feature importance explained BY the tool, before
   knowing the theory. Then repeat the same dataset via the Python/Java
   client to see the code-equivalent of every UI step (matches this
   repo's established UI+code pattern).
2. **Manual ML basics, briefly, NOT GCP-specific** - a tiny scikit-learn
   script (logistic regression or a decision tree on the same or a
   simpler dataset) run locally, just to see what AutoML was doing
   under the hood: fit/predict, a confusion matrix, over/underfitting by
   eye. Keep this small - the goal is intuition, not a full ML course.
3. **Vertex AI custom training** - package that same scikit-learn (or a
   basic PyTorch) script into a container, submit as a Vertex custom
   training job, deploy the resulting model to an endpoint, call it via
   the Java SDK. This is where "bring your own model to a managed
   platform" clicks.
4. **Vertex AI Vector Search** (formerly Matching Engine) - covered in
   Track C below since it's really a retail-search topic; sequence it
   after step 3 since embeddings will make more sense once basic model
   training isn't a black box anymore.
5. Skip for now unless it becomes relevant: Feature Store, Pipelines
   (MLOps orchestration) - only useful once there's more than one model/
   experiment to manage, premature for a first pass.

**Cost note to research fresh when starting:** AutoML training has a
real per-node-hour cost (not free-tier) - check
`cloud.google.com/vertex-ai/pricing` for current AutoML training rates
before starting step 1, and budget a small, short training run (smallest
dataset, shortest training budget the UI allows) to keep this cheap.

## Track C: Vector Search - a third search approach on the same catalog

Directly extends the `_09` (AI Commerce Search) vs `_10` (Elasticsearch)
comparison with a third, conceptually different approach: real
embeddings-based semantic search, not a managed product's built-in
semantic layer and not keyword full-text.

**Plan:**
1. New package `_12_vector_search` (or fold into `_11` if Track A also
   happens - they'd share the same catalog reuse).
2. Generate embeddings for the same 703 (or a subset) products' titles/
   descriptions via a Vertex AI embedding model call (see `_08`'s
   `SimpleRagDemo` for the exact embedding-call pattern already written).
3. Create a Vertex AI Vector Search index, upsert the embeddings, deploy
   to an endpoint (this has a real always-on cost while deployed -
   research current pricing at `cloud.google.com/vertex-ai/pricing`
   before deploying, and plan to undeploy/delete same-session like every
   other module here).
4. Re-run the SAME stratified 25-query subset used in `_10` (embed each
   query, nearest-neighbor search, compare titles) so this slots directly
   into the existing 3-way comparison table.
5. Expected finding to verify (don't assume it's true, check for real):
   Vector Search should do BEST on the "B-descriptive" bucket (natural-
   language queries) precisely because it's true semantic similarity, not
   keyword overlap - the bucket where both `_09` and `_10` struggled or
   only partially worked.
6. Document in a `package-info.java` the same way as every other module:
   concept flow (index -> embedding -> nearest-neighbor), cost formula,
   verification rigor, cleanup.

## Other identified gaps (lower priority, not part of the 3 tracks above)

- **Recommendations AI** (part of the Retail API, explicitly skipped in
  `_09` since it needs real user event history) - could synthesize fake
  view/add-to-cart/purchase events against the reused catalog to actually
  unlock and test this, instead of leaving it purely theoretical.
- **Cloud Run / Cloud Functions** - serverless compute, not yet touched
  (only GKE covered so far for "run my code" scenarios).
- **Pub/Sub** - event streaming, ties into the Kafka concepts already in
  `~/.claude/rules/infra.md` but never hands-on in this repo.
- **Cloud Build + Artifact Registry** - CI/CD pipeline, currently zero
  build automation in this repo (everything run via `mvn exec:java`
  locally).
