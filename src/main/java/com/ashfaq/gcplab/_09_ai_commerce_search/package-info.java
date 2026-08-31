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
 * titles were visible, via {@code search-quality-results.txt}). Fixed with
 * {@code CatalogExportDemo}, which dumps the exact same generator output to
 * {@code catalog-export.json} (all 703 products, plain JSON array - id,
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
 * <a href="https://claude.ai/code/artifact/3a3a54b4-7bfa-413d-ad1e-a7a2e6c48544">Search Relevance Audit</a>
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
 */
package com.ashfaq.gcplab._09_ai_commerce_search;
