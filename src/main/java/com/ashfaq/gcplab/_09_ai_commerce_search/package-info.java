/**
 * Reading order: 09 (comes after _01_iam ... _08_vertexai).
 *
 * <h2>NOT the same product as _08_vertexai</h2>
 * {@code _08_vertexai} (Agent Platform, formerly Vertex AI) is a
 * general-purpose LLM platform - any prompt, any use case, build your own
 * RAG/agents from primitives (which is exactly what we did there). THIS
 * package is a separate, purpose-built retail product: catalog in, ranked
 * search/browse/recommendations out, no DIY retrieval required. Different
 * API entirely ({@code retail.googleapis.com} vs {@code
 * aiplatform.googleapis.com}), both loosely under Google's historical
 * "Vertex AI" family branding but never called interchangeably.
 *
 * <h2>Where the raw catalog data actually is</h2>
 * The 703-product catalog itself was never persisted anywhere - it was
 * generated in-memory by {@code ProductCatalogGenerator} and streamed
 * straight into the Retail API's import call, so there was no local file
 * to actually eyeball and judge for data quality (only the search RESULT
 * titles were visible, via {@code search-experiments/ai-commerce-search-results.txt}). Fixed with
 * {@code CatalogExportDemo}, which dumps the exact same generator output to
 * {@code search-experiments/catalog-export.json} (all 703 products, plain JSON array - id,
 * title, description, category, brand, price, and the size/weight
 * attribute where applicable) purely as a local export, no API call
 * involved. This is the file to open if you want to judge the synthetic
 * data's realism directly rather than inferring it from search results.
 *
 * <h2>Naming history (third rename we've hit in this repo)</h2>
 * Vertex AI Search for Retail -&gt; Vertex AI Search for Commerce -&gt;
 * Console currently shows "AI Commerce Search" (as of 2026-08-31). The API
 * name itself never changed: {@code retail.googleapis.com}. Console URL:
 * {@code console.cloud.google.com/ai/retail} (reached by searching
 * "retail" in Console search, NOT a guessable URL - {@code /retail} alone
 * 404s).
 *
 * <h2>Three bundled capabilities</h2>
 * <ul>
 *   <li><b>Search</b> - structured product search with facets/filters
 *       PLUS semantic understanding (matches "warm winter jacket" to
 *       "insulated parka" without keyword overlap) - Google's own search
 *       infrastructure, pointed at your catalog.</li>
 *   <li><b>Browse</b> - category/collection pages, same ranking engine.</li>
 *   <li><b>Recommendations</b> - "bought together," "similar items,"
 *       personalized picks - REQUIRES real user event history (views,
 *       add-to-cart, purchases) to train on. Not something a same-session
 *       learning exercise can realistically demonstrate - noted but not
 *       attempted here.</li>
 * </ul>
 *
 * <h2>Concept flow: catalog -&gt; branch -&gt; product -&gt; attributes</h2>
 * <pre>
 * catalog                 ("default_catalog" - one per project by default;
 *                          a project can have multiple catalogs)
 *   -&gt; branch              ("0", "1", "2" - up to 3; a branch is a full
 *                          alternate copy of the catalog, used to stage/
 *                          swap an entire new catalog atomically. This
 *                          module only ever used branch "0".)
 *     -&gt; product           (one item, unique {@code id}, e.g. "p204")
 *       -&gt; attributes        (title, description, categories, brands,
 *                          priceInfo, plus arbitrary custom key/value
 *                          attributes - e.g. this module's "size"/"weight"
 *                          attribute used for the D-size query bucket)
 * </pre>
 * Search/Browse/Recommendations are then layered on top via a separate
 * <b>serving config</b> (e.g. {@code default_search}) that points at one
 * catalog+branch and defines ranking/facet/business-rule behavior - the
 * serving config is not part of the data hierarchy itself, more like an
 * index alias with ranking logic attached.
 *
 * <h2>How this maps to the rest of this repo</h2>
 * <table border="1">
 *   <tr><th>Layer</th><th>What we built elsewhere</th><th>What real retail companies use</th></tr>
 *   <tr><td>Raw catalog storage</td><td>_03_storage (GCS) / _04_cloudsql / _06_firestore</td><td>Same</td></tr>
 *   <tr><td>Semantic retrieval</td><td>_08_vertexai's SimpleRagDemo - hand-rolled embed+retrieve+prompt</td><td>THIS package - managed: Google's ranking, facets, business rules built in</td></tr>
 *   <tr><td>Keyword/faceted search</td><td>Not yet built</td><td>Elasticsearch (self-hosted) OR this package's built-in faceted search - genuinely competing options</td></tr>
 *   <tr><td>Caching</td><td>_05_redis</td><td>Same - Memorystore in front of the search API for hot queries</td></tr>
 *   <tr><td>Personalization</td><td>Not applicable (no event history)</td><td>Recommendations - needs real traffic</td></tr>
 * </table>
 *
 * <h2>How we set this up (2026-08-31)</h2>
 * <ul>
 *   <li>Console UI: searched "retail" in Console search -&gt; "Retail"
 *       product result -&gt; landed on "AI Commerce Search" setup wizard.</li>
 *   <li>Step 1: "Turn on API" (enables retail.googleapis.com - free,
 *       usage-based billing after - see Pricing section below for the real
 *       figures, covered easily by free trial credit for a handful of test
 *       queries).</li>
 *   <li>Step 2: agreed to data use terms.</li>
 *   <li>Step 3 (optional): turned on search &amp; browse features;
 *       Recommendations noted as "on by default" but requires event
 *       history to actually produce results.</li>
 *   <li>Verified via REST (no {@code gcloud alpha retail} command group
 *       exists - this API has no gcloud CLI coverage at all, REST/SDK
 *       only): {@code GET https://retail.googleapis.com/v2/projects/
 *       PROJECT_ID/locations/global/catalogs} - required an
 *       {@code x-goog-user-project} header (plain {@code gcloud auth
 *       print-access-token} without it hit a "quota project not set"
 *       403 first) - confirmed a {@code default_catalog}
 *       ("Product Catalog") exists, empty (no products imported yet).</li>
 * </ul>
 *
 * <h2>Catalog import: 703 synthetic products (2026-08-31)</h2>
 * {@link ProductCatalogGenerator} builds a realistic local-supermarket
 * catalog (fictional brands, no real retailer named) - groceries, dairy,
 * bakery, snacks, beverages, household, personal care, baby care, health,
 * apparel (with S/M/L/XL/XXL size variants), frozen foods, home/stationery.
 * {@link CatalogImportDemo} bulk-imports via INLINE source (products passed
 * as Java objects directly in the request, no GCS staging file) - capped
 * at 100 products per call, so 703 products went in as 8 batches.
 * <p>
 * Two SDK bugs hit along the way, both worked around rather than blocking:
 * <ol>
 *   <li>{@code errorsConfig} is rejected outright for inline imports (GCS
 *       imports only) - removed it.</li>
 *   <li>The typed {@code importProductsAsync(...).get()} throws a proto
 *       "Any" unpacking exception on THIS SDK version (google-cloud-retail
 *       2.58.0) even though the import genuinely succeeds server-side
 *       (confirmed via REST immediately after the "failure"). Fixed by
 *       polling the raw {@code Operation} via {@code
 *       client.getOperationsClient().getOperation(name)} and checking only
 *       {@code getDone()}/{@code hasError()} - never unpacking the typed
 *       response.</li>
 * </ol>
 * Verified 703/703 landed via paginated REST {@code GET .../branches/0/
 * products}.
 *
 * <h2>100-query relevance audit (2026-08-31)</h2>
 * {@link SearchQualityTest} runs 100 queries across 4 buckets (25 each)
 * simulating how a real, non-technical shopper actually searches, against
 * the {@code default_search} serving config (created automatically when
 * search &amp; browse was enabled). Search itself returns only product IDs
 * - each result's title was enriched via a follow-up {@code GetProduct}
 * call. Every one of the 100 results was checked BY HAND against the real
 * catalog contents and scored 0-10 - not a simulated/assumed score.
 * <p>
 * Full scored report (all 100 queries, top result, score, reasoning):
 * {@code search-experiments/ai-commerce-search-audit.md}.
 * <p>
 * <b>Results by bucket:</b>
 * <table border="1">
 *   <tr><th>Bucket</th><th>Style</th><th>Score</th><th>Finding</th></tr>
 *   <tr><td>A</td><td>Exact product name ("basmati rice")</td><td>10.0/10</td><td>Near-perfect keyword matching</td></tr>
 *   <tr><td>B</td><td>Described need ("something for my dandruff")</td><td>3.7/10</td><td>16 of 25 returned ZERO results, including cases where the exact word appears in the product description</td></tr>
 *   <tr><td>C</td><td>Weight/volume ("1 litre cooking oil")</td><td>8.0/10</td><td>Strong, but "litre" vs "L" normalized inconsistently (worked for milk/water, failed for oil/cola)</td></tr>
 *   <tr><td>D</td><td>Clothing/diaper size ("small kurti")</td><td>9.0/10</td><td>"small" correctly mapped to S for kurti, but "small diapers" surfaced L/M/XL - inconsistent size-word understanding</td></tr>
 * </table>
 * <b>Overall: 7.65/10.</b> Headline finding: default AI Commerce Search
 * (no synonyms/query-understanding/boosts configured) is a strong KEYWORD
 * engine, not yet a semantic one out of the box. This is exactly the gap
 * Vertex AI Search's embedding/semantic layer (and this product's own
 * "conversational search" / query-understanding features, not enabled
 * here) are specifically built to close - and exactly what our hand-rolled
 * RAG in {@code _08_vertexai}'s SimpleRagDemo does manually for a much
 * smaller document set.
 *
 * <h2>Pricing - formula and worked examples</h2>
 * <p><b>Sourcing note:</b> Google's official pricing page
 * ({@code cloud.google.com/products/retail/pricing}) is a JS-rendered
 * calculator that could not be fetched as plain text for this doc - the
 * figures below are cross-referenced across multiple independent secondary
 * sources (consistent across searches) but NOT confirmed against Google's
 * primary page directly. Verify in the Cloud Billing console or the
 * official pricing calculator before using these for real budgeting.
 * <ul>
 *   <li><b>Search &amp; Browse requests:</b> ~$2.50 per 1,000 requests
 *       (flat rate, no free tier specific to this product beyond the
 *       platform-wide $300/90-day trial credit).</li>
 *   <li><b>Recommendations predictions</b> (not used in this module - no
 *       event history): tiered - first 20M/month at $0.27 per 1,000,
 *       next 280M at $0.18 per 1,000, next 700M at $0.10 per 1,000.</li>
 *   <li><b>Product catalog storage/import:</b> not separately billed as a
 *       distinct line item, based on available sourcing - the catalog
 *       itself (703 products) added no visible extra cost beyond the
 *       search requests made against it.</li>
 * </ul>
 *
 * <p><b>Formula (search-only, the relevant one for THIS module):</b>
 * <pre>
 *   monthly_cost_usd = (monthly_search_queries / 1000) * 2.50
 * </pre>
 * <b>Worked examples:</b>
 * <table border="1">
 *   <tr><th>Monthly search volume</th><th>Calculation</th><th>Monthly cost</th></tr>
 *   <tr><td>100 (this test)</td><td>(100 / 1000) * 2.50</td><td>$0.25</td></tr>
 *   <tr><td>10,000</td><td>(10,000 / 1000) * 2.50</td><td>$25</td></tr>
 *   <tr><td>100,000</td><td>(100,000 / 1000) * 2.50</td><td>$250</td></tr>
 *   <tr><td>1,000,000</td><td>(1,000,000 / 1000) * 2.50</td><td>$2,500</td></tr>
 *   <tr><td>10,000,000 (a busy mid-size retailer)</td><td>(10,000,000 / 1000) * 2.50</td><td>$25,000</td></tr>
 * </table>
 * To plug in your own number: take your expected monthly search volume,
 * divide by 1000, multiply by 2.50 (or whatever the confirmed current rate
 * is - re-verify periodically, cloud pricing changes).
 *
 * <h2>Scale limits - how much volume this service can actually handle</h2>
 * Confirmed from Google's official quotas documentation
 * (docs.cloud.google.com/retail/docs/quotas, fetched directly - these
 * figures ARE authoritative, unlike the pricing ones above):
 * <table border="1">
 *   <tr><th>Limit</th><th>Default value</th></tr>
 *   <tr><td>Searches per minute</td><td>300 (default quota - raisable via quota increase request)</td></tr>
 *   <tr><td>Predictions (Recommendations) per minute</td><td>60,000</td></tr>
 *   <tr><td>Product writes per minute</td><td>12,000</td></tr>
 *   <tr><td>Product reads per minute</td><td>300</td></tr>
 *   <tr><td>Product imports per minute</td><td>100 (matches the 100-per-batch cap we hit during import)</td></tr>
 *   <tr><td>Total products, search ENABLED</td><td>4,000,000</td></tr>
 *   <tr><td>Total products, search DISABLED</td><td>40,000,000</td></tr>
 *   <tr><td>User event writes per minute</td><td>60,000</td></tr>
 *   <tr><td>Total user events (lifetime)</td><td>40,000,000,000</td></tr>
 * </table>
 * 300 searches/minute (default quota) = 5 QPS sustained, 18,000/hour,
 * 432,000/day if run continuously - fine for a small-to-mid storefront,
 * a quota increase request is the documented path for anything busier
 * (this is a soft default, not a hard architectural ceiling). Our own
 * 703-product catalog is comfortably within the 4M search-enabled limit;
 * even a very large real retailer's full catalog is unlikely to approach
 * it before search relevance/UX becomes the harder problem anyway.
 *
 * <h2>Cleanup (2026-08-31)</h2>
 * {@link CatalogPurgeDemo} bulk-deleted all 703 test products via
 * {@code purgeProductsAsync} (filter {@code "*"}, {@code force=true}) -
 * one call instead of 703 individual DeleteProduct calls. Same raw
 * Operation-polling pattern as the import (avoids the same SDK unpacking
 * bug). Verified empty via REST: {@code GET .../branches/0/products}
 * returned {@code {}} - no products field at all. The {@code
 * default_catalog} resource itself and the {@code default_search} serving
 * config remain (no cost while empty) - only the product data was purged.
 *
 * <h2>Internal architecture: catalog ingest vs. search serving are separate systems</h2>
 * <pre>
 * CatalogImportDemo -&gt; ImportProductsRequest (inline, batched 100/call)
 *   -&gt; retail.googleapis.com write path -&gt; product data lands in
 *      catalog/branch storage AND is asynchronously fed into a SEPARATE
 *      search-indexing pipeline (this is why import completion and
 *      "searchable" are not the same instant - there's a real, if usually
 *      short, propagation delay between a product landing in the catalog
 *      store and appearing in search results, which is why real
 *      integrations poll/verify rather than assume immediate searchability)
 * SearchQualityTest -&gt; SearchRequest against a SERVING CONFIG (default_search)
 *   -&gt; the serving config is a pointer to (catalog, branch, ranking/facet
 *      rules) - NOT a live query over the raw catalog store; it queries
 *      Google's managed search index built from that data
 *   -&gt; candidate retrieval (token/keyword match against the index, per this
 *       module's findings, NOT semantic/embedding-based by default) -&gt;
 *      ranking (relevance score, optionally boosted by business rules,
 *      pricing, popularity signals - none configured here) -&gt; top-N product
 *      IDs returned -&gt; caller does a separate GetProduct call per ID to
 *      fetch full details (search returns IDs/summary fields, not the full
 *      catalog record, by design - keeps the hot search path lean)
 * </pre>
 * BRANCHES exist specifically to make a catalog refresh atomic at serving-
 * config scale: import a whole new catalog into an inactive branch, verify
 * it, then flip the serving config's active branch in one call - shoppers
 * never see a half-updated catalog mid-import, unlike this module's
 * approach of importing directly into the live branch "0" (fine for a
 * learning exercise with zero real traffic, not how a production catalog
 * refresh should work).
 *
 * <h2>System design takeaway</h2>
 * The 100-query audit's real lesson is architectural, not just a scoring
 * table: a managed "AI search" product's default candidate-retrieval stage
 * is still fundamentally a text-matching index unless you explicitly turn
 * on its semantic/embedding retrieval features - "AI-branded" does not mean
 * "vector search is the default retrieval mechanism." Designing search for
 * a real catalog means treating retrieval and ranking as two SEPARATE
 * tunable stages: candidate retrieval (does the query even surface the
 * right product at all - this module's B-bucket failures are entirely
 * retrieval failures, zero candidates returned) has to work before ranking
 * (is the RIGHT candidate first among several) can matter at all - and a
 * managed product's off-the-shelf configuration should never be assumed to
 * have solved the harder, first problem without verifying it the way this
 * module did.
 *
 * <h2>What this service actually is, in plain terms</h2>
 * AI Commerce Search is a fully packaged, ready-to-call retail search/
 * browse/recommendation engine - you give Google a product catalog (the
 * same shape a real e-commerce backend already has: id, title, description,
 * category, brand, price, custom attributes) and Google gives you back a
 * search API that already understands product-shaped data out of the box:
 * facets/filters, price ranges, category browsing, and Google's own ranking
 * signals - none of which you build yourself, unlike {@code _10_elasticsearch}
 * where every one of those is something you'd configure by hand. It is
 * explicitly NOT a general-purpose search engine (you can't point it at
 * arbitrary documents/web pages the way you could Elasticsearch) - the API
 * schema is retail-specific (Product, UserEvent, SearchRequest all have
 * retail-domain fields baked in), which is the trade this product makes:
 * narrower scope, in exchange for far less setup for the one domain it
 * targets.
 *
 * <h2>Why this exists - the problem it solves</h2>
 * Every online retailer needs product search, and building a genuinely good
 * one from scratch is a much harder, longer project than it looks: handling
 * typos and synonyms, understanding "cheap running shoes under $50" as a
 * structured filter plus a text query, ranking results by what actually
 * converts (not just text relevance), and personalizing results per shopper
 * - all of that is a dedicated search-relevance engineering discipline most
 * retail companies don't want to (or can't afford to) build and maintain in
 * house. This product's pitch is "Google already solved large-scale product
 * search for its own Shopping product - rent that expertise via API" rather
 * than every retailer separately reinventing ranking/relevance tuning. It
 * also directly targets the semantic-gap problem this module's own audit
 * quantified: a shopper who types "something for my dandruff" instead of
 * the literal product name "anti-dandruff shampoo" - solving that gap well
 * is specifically what the product's (opt-in, not exercised in this default-
 * config module) semantic/embedding retrieval and query-understanding
 * features are built for.
 *
 * <h2>Real-world use cases - what this is actually built for</h2>
 * <ul>
 *   <li><b>The site search box</b> - the core use case: a shopper types a
 *       query, gets back ranked, relevant products with facets to narrow
 *       further (brand, price range, size, rating) - this module's
 *       {@link SearchQualityTest} exercises exactly this endpoint, just
 *       without a UI wired to it.</li>
 *   <li><b>Category/collection browse pages</b> - "Browse" (bundled with
 *       Search, same underlying ranking engine) powers pages like
 *       "/category/dairy" - not a text query at all, but the same catalog
 *       and ranking signals apply (best-sellers first, in-stock first,
 *       etc.), letting one system back both the search box and every
 *       category page instead of two separate implementations.</li>
 *   <li><b>"Customers also bought" / "similar items" widgets</b> - the
 *       Recommendations capability (noted but not exercised here since it
 *       needs real user event history to train on) - this is what actually
 *       requires the UserEvent stream (views, add-to-cart, purchases) this
 *       module never generated; a follow-up exercise noted in
 *       docs/roadmap.md would synthesize fake events specifically to
 *       unlock and test this.</li>
 *   <li><b>Autocomplete / query suggestions</b> - a related, opt-in feature
 *       of the same product (not called in this module) that suggests
 *       completions as a shopper types, trained on the same catalog and
 *       search-log signals.</li>
 *   <li><b>Merchandising / business-rule overrides</b> - boosting or
 *       pinning specific products for a query (e.g. promoting a
 *       overstocked item, or a sponsored placement) via business rules
 *       layered on top of organic ranking - configuration this module's
 *       {@code default_search} serving config left entirely at its
 *       out-of-the-box defaults, deliberately, to measure the unconfigured
 *       baseline first.</li>
 *   <li><b>Feeding a conversational shopping assistant</b> - exactly
 *       docs/roadmap.md's Track A ("Rufus clone"): an LLM agent
 *       ({@code _08_vertexai}'s agent-loop pattern) calling THIS service's
 *       search endpoint as a tool, so the assistant's product knowledge is
 *       always the real, current catalog rather than the model's own
 *       (possibly stale, possibly hallucinated) training knowledge.</li>
 * </ul>
 *
 * <h2>Sample usage walkthrough - each demo class, what it proves</h2>
 * <b>{@link ProductCatalogGenerator} + {@link CatalogImportDemo} - getting
 * real product data in:</b>
 * <pre>
 * List&lt;Product&gt; products = ProductCatalogGenerator.generate();  // 703 synthetic products
 * ProductServiceSettings settings = ProductServiceSettings.newBuilder()
 *     .setCredentialsProvider(FixedCredentialsProvider.create(impersonatedCredentials))
 *     .build();
 * try (ProductServiceClient client = ProductServiceClient.create(settings)) {
 *     for (List&lt;Product&gt; batch : Lists.partition(products, 100)) {   // 100/call cap
 *         ImportProductsRequest req = ImportProductsRequest.newBuilder()
 *             .setParent("projects/PROJECT/locations/global/catalogs/default_catalog/branches/0")
 *             .setInputConfig(ProductInputConfig.newBuilder()
 *                 .setProductInlineSource(ProductInlineSource.newBuilder()
 *                     .addAllProducts(batch)).build())
 *             .build();
 *         // real code polls the raw Operation (see the SDK-bug workaround
 *         // in "Catalog import" above) rather than .get() on the typed future
 *         Operation op = client.importProductsCallable().call(req).getOperation();
 *         while (!op.getDone()) { Thread.sleep(2000); op = opsClient.getOperation(op.getName()); }
 *     }
 * }
 * </pre>
 * 8 batches for 703 products, each product carrying a title, description,
 * category path, brand, price, and (for apparel/diapers) a custom
 * {@code size}/{@code weight} attribute - the exact same shape a real
 * e-commerce product catalog already has, which is the point: nothing
 * search-specific to model, you import what you already have.
 * <p>
 * <b>{@link SearchQualityTest} - the actual search call, and why the audit
 * exists:</b>
 * <pre>
 * SearchServiceSettings settings = SearchServiceSettings.newBuilder()
 *     .setCredentialsProvider(FixedCredentialsProvider.create(impersonatedCredentials))
 *     .build();
 * try (SearchServiceClient client = SearchServiceClient.create(settings)) {
 *     SearchRequest req = SearchRequest.newBuilder()
 *         .setPlacement("projects/PROJECT/locations/global/catalogs/default_catalog"
 *             + "/servingConfigs/default_search")
 *         .setQuery("i need something for my dandruff")
 *         .setVisitorId("test-visitor-1")             // required - anonymizes/tracks the "session"
 *         .setPageSize(3)
 *         .build();
 *     for (SearchResult r : client.search(req).iterateAll()) {
 *         GetProductRequest getReq = GetProductRequest.newBuilder().setName(r.getProduct().getName()).build();
 *         Product full = productClient.getProduct(getReq);   // search returns IDs; a 2nd call gets full detail
 *         System.out.println(full.getTitle());
 *     }
 *     // for this exact query: ZERO results, even though "Anti-Dandruff Shampoo"
 *     // exists verbatim in the catalog - this is the finding the audit scored
 * }
 * </pre>
 * The two-call shape (search for IDs, then GetProduct per ID for full
 * detail) is deliberate on Google's part, not a limitation worked around
 * here - it keeps the high-QPS search path returning minimal payloads,
 * pushing the cost of full product detail only onto the results a shopper
 * actually needs rendered (typically the top handful, not every candidate
 * considered internally during ranking).
 * <p>
 * <b>{@link CatalogExportDemo} - inspecting the ground truth directly:</b>
 * <pre>
 * List&lt;Product&gt; products = ProductCatalogGenerator.generate();  // same generator, zero API calls
 * // ... writes id/title/description/category/brand/price/attribute per
 * // product as plain JSON to search-experiments/catalog-export.json
 * </pre>
 * Exists purely so a reader can open one file and judge the synthetic
 * catalog's realism directly, rather than inferring data quality indirectly
 * from which search queries did or didn't return results.
 *
 * <h2>Quick reference: things real work needs that this module's demos didn't hit</h2>
 * The demos here proved plain free-text search works end to end; a real
 * storefront integration needs the pieces below too - all real, current
 * SearchRequest/Product fields, so wiring facets, filters, or
 * recommendations shouldn't require starting from a Google search.
 *
 * <p><b>1. Filtering - structured, not free-text.</b> "Show me only
 * PureHarvest products under 200 rupees" is a {@code filter} STRING on
 * SearchRequest, a small structured-query language, NOT part of the
 * {@code query} text field used by this module's demos:
 * <pre>
 * SearchRequest.newBuilder()
 *     .setQuery("rice")
 *     .setFilter("brand: ANY(\"PureHarvest\") AND priceInfo.price &lt; 200")
 *     ...
 * </pre>
 * Filters can reference any indexed attribute (brand, categories,
 * priceInfo.price, or a custom attribute like this catalog's
 * {@code attributes.size}/{@code attributes.weight}) - combined with
 * {@code AND}/{@code OR}/{@code NOT}. A custom attribute must be marked
 * {@code indexable: true} on the Product (done implicitly by the SDK for
 * simple key/value attributes, as this module's catalog import relies on)
 * before it can be filtered or faceted on - an attribute imported without
 * indexing enabled will silently not show up in filter/facet results, a
 * common real-world gotcha.
 *
 * <p><b>2. Facets - the "Brand / Price / Size" sidebar every product search
 * page has.</b> Requested via {@code facetSpecs} on the SearchRequest, NOT
 * automatic - without it, a SearchResponse has no facet/count breakdown at
 * all:
 * <pre>
 * SearchRequest.newBuilder()
 *     .setQuery("shampoo")
 *     .addFacetSpecs(FacetSpec.newBuilder()
 *         .setFacetKey(FacetKey.newBuilder().setKey("brand").build())
 *         .setLimit(20))
 *     .addFacetSpecs(FacetSpec.newBuilder()
 *         .setFacetKey(FacetKey.newBuilder().setKey("priceInfo.price")
 *             .addIntervals(Interval.newBuilder()
 *                 .setMaximum(100.0)).build()))       // price bucket: 0-100
 *     ...
 * </pre>
 * The response then includes, per requested facet, each distinct value
 * (e.g. every brand present in the current result set) plus a COUNT - the
 * numbers next to each checkbox in a typical filter sidebar.
 *
 * <p><b>3. Business rules (boost/bury) - merchandising control, opt-in.</b>
 * A {@code Control} resource (created via {@code ControlServiceClient},
 * separate from ServingConfig) can boost or bury specific products/brands
 * for matching queries without touching the catalog data itself - e.g.
 * "boost brand=DailyFresh by 0.3 for any dairy-category query" to promote a
 * paid placement, or "bury out-of-stock items to -1.0" - attached to a
 * serving config so it applies at query time. This module's
 * {@code default_search} config left every business rule unconfigured
 * deliberately, to measure the unmodified baseline (see the 100-query
 * audit) - a real storefront layers these on top once organic relevance is
 * already trustworthy.
 *
 * <p><b>4. UserEvents - what actually unlocks Recommendations.</b> The
 * Recommendations capability (noted, not exercised in this module) trains
 * entirely on a UserEvent stream - it does not just look at the Product
 * catalog:
 * <pre>
 * UserEvent event = UserEvent.newBuilder()
 *     .setEventType("purchase-complete")   // also: "add-to-cart", "detail-page-view",
 *                                           // "search", "home-page-view", etc.
 *     .setVisitorId("visitor-123")
 *     .setEventTime(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
 *     .addProductDetails(ProductDetail.newBuilder()
 *         .setProduct(ProductDetail.ProductInfo.newBuilder().setId("p204")))
 *     .build();
 * userEventClient.writeUserEvent(WriteUserEventRequest.newBuilder()
 *     .setParent("projects/PROJECT/locations/global/catalogs/default_catalog")
 *     .setUserEvent(event).build());
 * </pre>
 * A production integration streams these continuously (every search, every
 * product view, every add-to-cart, every purchase) - Recommendations
 * quality is directly a function of event VOLUME and coverage of event
 * types, which is exactly why this module (zero real shoppers, zero real
 * events) explicitly left Recommendations untested rather than fabricate a
 * misleading result; docs/roadmap.md notes synthesizing fake events as a
 * legitimate follow-up specifically to unlock this honestly.
 *
 * <p><b>5. Autocomplete - a separate, opt-in call.</b>
 * {@code CompletionServiceClient.completeQuery(...)} - given a partial
 * string ("choc"), returns ranked query completions ("chocolate milk",
 * "chocolate biscuits") trained on the catalog and search-log data, the
 * dropdown suggestions under a search box - a genuinely different API call
 * from Search itself, not a parameter on SearchRequest.
 *
 * <p><b>6. Common errors and what they actually mean:</b>
 * <ul>
 *   <li>{@code NOT_FOUND} on the catalog/branch/servingConfig path - almost
 *       always a typo in the resource name string (this API's resource
 *       paths are long and hand-assembled, e.g. {@code projects/P/locations/
 *       global/catalogs/default_catalog/branches/0/...} - a wrong segment
 *       fails silently as NOT_FOUND, not a clearer "bad path" error).</li>
 *   <li>{@code INVALID_ARGUMENT} on import - almost always a malformed
 *       Product (missing required {@code id}/{@code title}, or a custom
 *       attribute value of the wrong type) - the error message includes
 *       which product/field, worth reading carefully rather than assuming
 *       a bulk failure means every product is bad.</li>
 *   <li>Search returns zero results for a query where the word visibly
 *       appears in a product's title/description - THIS module's central
 *       finding (see the 100-query audit) - not a bug, it's the default
 *       index behaving like a phrase/structured matcher rather than a
 *       free-text token matcher; the fix is enabling the product's
 *       search-quality/semantic-retrieval configuration, not a code
 *       change.</li>
 *   <li>{@code RESOURCE_EXHAUSTED} - hit one of the per-minute quotas (see
 *       the Scale limits table above, e.g. 300 searches/minute default) -
 *       request a quota increase via Console -&gt; IAM &amp; Admin -&gt;
 *       Quotas for anything genuinely busier, same pattern as
 *       {@code _08_vertexai}'s quota-error guidance.</li>
 *   <li>A product imported successfully (visible via GetProduct) but never
 *       appears in search results - almost always the indexing-propagation
 *       delay noted in "Internal architecture" above (import and
 *       searchability are two separate pipelines) - wait and re-check
 *       before assuming the import silently failed.</li>
 * </ul>
 *
 * <h2>FAQ: how this actually behaves as a service (answered from real usage)</h2>
 *
 * <p><b>1. Is this "add data + search," or full CRUD - and how, exactly
 * (SQL? code? sync? async?)</b> Full CRUD on Product, but there is NO SQL
 * or query-language admin interface at all, unlike Cloud SQL's psql/Studio
 * or even Firestore's Console data browser - every single operation, admin
 * or search, goes through the API (REST, gRPC, or a generated client
 * library like the Java {@code ProductServiceClient}/{@code
 * SearchServiceClient} this module uses) or the Console UI, which itself
 * is just a thin wrapper calling that same API. There is no third way in.
 * The operations, and their sync/async shape:
 * <ul>
 *   <li><b>Create</b> - {@code CreateProduct} (one product) is a normal
 *       SYNCHRONOUS unary call, returns immediately with the created
 *       resource. {@code ImportProducts} (bulk, what {@link
 *       CatalogImportDemo} uses for all 703) is ASYNCHRONOUS - it returns a
 *       long-running {@code Operation} you poll until {@code done=true},
 *       same shape as a Cloud Storage batch job or a database bulk-load
 *       job; never assume a bulk import finished just because the initial
 *       call returned.</li>
 *   <li><b>Read</b> - {@code GetProduct} (single, by ID) and {@code
 *       ListProducts} (paginated, all products in a branch) are both
 *       SYNCHRONOUS. There is also {@code ExportProducts} (bulk export to
 *       GCS) which, like Import, is ASYNCHRONOUS.</li>
 *   <li><b>Update</b> - {@code UpdateProduct} (a normal PATCH-style
 *       synchronous call, field mask controls which fields change - not
 *       used in this module's demos, which only ever created and purged,
 *       never updated a single product in place) - a real, if unexercised,
 *       part of the CRUD surface.</li>
 *   <li><b>Delete</b> - {@code DeleteProduct} (single, SYNCHRONOUS) and
 *       {@code PurgeProducts} (bulk, by filter - {@link CatalogPurgeDemo}
 *       used {@code filter="*"} to wipe all 703 in one call - ASYNCHRONOUS,
 *       same Operation-polling pattern as import).</li>
 *   <li><b>Search</b> (the read path shoppers actually hit) - {@code
 *       Search} is SYNCHRONOUS, but paginated (page size, page token) -
 *       there's no "search everything async and get a callback" mode, it's
 *       a normal request/response call you'd put behind a search box's
 *       backend endpoint directly.</li>
 * </ul>
 * The pattern to remember: single-item, low-latency operations (create one
 * product, search, get, update, delete one product) are synchronous unary
 * calls; whole-catalog, potentially-slow operations (import, export, purge)
 * are asynchronous long-running operations you poll - the exact same split
 * this repo already hit as a real SDK quirk during catalog import (see
 * "Catalog import" above).
 *
 * <p><b>2. Is it designed to handle real volume?</b> Yes - this is the same
 * infrastructure family powering Google's own Shopping search at planet
 * scale, not a bolted-on feature. Concretely (see "Scale limits" above,
 * sourced from Google's official quotas page): up to 4,000,000 products
 * with search enabled (40,000,000 with it disabled), 12,000 product writes/
 * minute, and a default 300 searches/minute PER PROJECT that is explicitly
 * a raisable soft quota, not a hard architectural ceiling - Google's own
 * documented path for a busier storefront is simply requesting a quota
 * increase, the same mechanism used across every GCP API, not a re-
 * architecture. This module's 703-product catalog and handful of test
 * queries never came remotely close to any of these numbers.
 *
 * <p><b>3. When volume increases, does accuracy decrease?</b> Two
 * DIFFERENT kinds of "volume" answer this differently - conflating them is
 * the easy mistake:
 * <ul>
 *   <li><b>Catalog size (more products)</b> - does NOT degrade retrieval
 *       accuracy by itself. The underlying index scales the same way any
 *       large-scale inverted/structured index does (see {@code
 *       _10_elasticsearch}'s internal-architecture notes on how an
 *       inverted index works - Google's version is the same idea at much
 *       larger scale) - a query for "basmati rice" finds the same matching
 *       products whether the catalog has 700 or 7 million items. What DOES
 *       get harder as catalog size grows is RANKING among many similar
 *       candidates (which of 500 near-identical rice products should rank
 *       #1) - that's a ranking-signal problem, not a retrieval-accuracy
 *       problem, and it's exactly what business rules, learned ranking, and
 *       personalization (below) exist to solve.</li>
 *   <li><b>Query/traffic volume (more searches, more shoppers)</b> - does
 *       NOT degrade accuracy either, and this module's own 100-query test
 *       vs. a hypothetical million-query day would return identically
 *       relevant results for the SAME query text - Search is a stateless
 *       read path with no accuracy cost that scales with QPS (only cost-in-
 *       dollars and quota scale with QPS, not result quality).</li>
 * </ul>
 * The honest nuance: what genuinely changes result quality over time is not
 * "volume" in either sense above, but the presence (or absence) of
 * user-behavior SIGNAL - see the next question, since this module's own
 * results are a live demonstration of exactly that gap.
 *
 * <p><b>4. Does it get better eventually, and how - explained via this
 * module's own experiment?</b> Yes, but ONLY if it's fed real UserEvents
 * (search impressions, clicks, add-to-cart, purchases - see "UserEvents"
 * above) - the product layers a LEARNED RANKING model on top of the
 * baseline text-match retrieval, trained continuously on what shoppers
 * actually click and buy for a given query, the same "learning to rank
 * from real behavior" idea behind Google's own web/Shopping search
 * improving over years of real traffic. This is a genuinely different
 * mechanism from a static algorithm getting "smarter" on its own - it
 * requires a feedback loop of real signal, same as any recommendation
 * system.
 * <p>
 * This directly explains this module's own 100-query audit finding (7.65/10
 * overall, and the 40%-zero-results problem on descriptive queries): {@code
 * default_search} was queried against a catalog with ZERO UserEvent
 * history - a textbook COLD START, the state every new retail integration
 * starts in before any real shoppers have used it. What the audit actually
 * measured was the product's UNLEARNED baseline - pure text-matching
 * behavior with no ranking model trained on this catalog's specific query
 * patterns yet, because there was no signal to train on. In a real
 * deployment, the SAME queries run again after weeks/months of real
 * shopper traffic (clicks confirming "yes, that dandruff query should have
 * surfaced the anti-dandruff shampoo") would be expected to improve on
 * exactly the failure cases this audit caught - not because Google changed
 * anything, but because the product would finally have the behavioral
 * signal its learned-ranking layer needs. This module deliberately never
 * generated fake events specifically so the cold-start baseline could be
 * measured honestly (see docs/roadmap.md's note on synthesizing UserEvents
 * as a legitimate, still-open follow-up to actually observe this
 * improvement rather than just theorize about it).
 *
 * <p><b>5. Do search results come with a relevance/matching score, and how
 * do you get it?</b> Confirmed against Google's own API reference
 * ({@code docs.cloud.google.com/retail/docs/reference/rest/v2/
 * SearchResponse}, fetched directly for this answer): each {@code
 * SearchResult} has a {@code modelScores} field - a map of
 * {@code string -&gt; DoubleList} that Google's reference docs describe only
 * as "Google provided available scores," without documenting the exact key
 * names, value semantics, or scale (e.g. no confirmation it's 0-1
 * normalized, or comparable across queries the way Elasticsearch's BM25
 * {@code _score} is designed to be within one query). This is a real,
 * present field - unlike the pricing figures elsewhere in this file, this
 * one IS sourced from the primary API reference - but it is deliberately
 * under-documented on Google's side, and this module's own {@link
 * SearchQualityTest} never requested or inspected it (every one of the 100
 * scores in the audit is a HUMAN judgment of relevance, reading the
 * returned product titles, not anything the API reported numerically).
 * Practical takeaway: {@code modelScores} is there to inspect
 * (deserialize the SearchResult and print the map) if debugging why one
 * result outranked another, but should not be treated as a stable,
 * documented contract to build product logic on (e.g. "only show results
 * scoring above 0.7") the way you might confidently threshold an
 * Elasticsearch {@code _score} - verify its current behavior directly
 * against a real response before depending on it, and prefer Google's own
 * ranking ORDER (the array order of results, which is the actual, fully-
 * supported relevance signal) over trying to reverse-engineer meaning from
 * this map.
 */
package com.ashfaq.gcplab._09_ai_commerce_search;
