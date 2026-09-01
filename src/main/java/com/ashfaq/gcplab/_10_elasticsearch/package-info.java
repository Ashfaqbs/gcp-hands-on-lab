/**
 * <h2>What this module is</h2>
 * A self-hosted Elasticsearch comparison point for {@code _09_ai_commerce_search}.
 * There is no managed Elasticsearch product on GCP (Elastic Cloud on GCP
 * Marketplace exists but is a paid third-party SaaS billed separately from
 * GCP, not used here). Instead this module runs a single-node Elasticsearch
 * 8.15 in Docker on a plain Compute Engine VM - which is itself the more
 * common real-world pattern for teams who self-host search rather than pay
 * for a managed offering.
 *
 * <h2>Concept flow: cluster -&gt; index -&gt; document -&gt; fields</h2>
 * <pre>
 * Elasticsearch cluster      (one or more nodes; this module runs a single
 *                             node standing in as a 1-node "cluster")
 *   -&gt; index                 ("products" - roughly a table, but schema is
 *                             a "mapping" you define, not enforced by
 *                             default the way a SQL table is)
 *     -&gt; document             (one product, identified by {@code _id},
 *                             e.g. "p204" - a JSON object, indexed into a
 *                             shard under the hood)
 *       -&gt; fields              (title, description, category, brand,
 *                             attribute, price - each field's type comes
 *                             from the mapping: {@code text} fields are
 *                             tokenized/analyzed for full-text search,
 *                             {@code keyword} fields are matched exactly,
 *                             which is WHY category/brand/attribute were
 *                             mapped {@code keyword} here - they're for
 *                             filtering/faceting, not free-text search)
 * </pre>
 * An index can also be split into multiple <b>shards</b> (for horizontal
 * scale) and <b>replicas</b> (for HA) - both left at their single-node
 * defaults here since this is a one-node throwaway cluster.
 *
 * <h2>Why this exists</h2>
 * {@code _09_ai_commerce_search}'s own 100-query audit surfaced a real
 * finding: Google's AI Commerce Search, in its default configuration, is a
 * keyword-literal matcher, not a semantic one - it returned zero results on
 * roughly 40% of natural-language ("descriptive") queries. The natural
 * follow-up question is: is that worse, better, or about the same as a
 * plain self-hosted keyword search engine with zero tuning? This module
 * answers that with real data instead of assumption.
 *
 * <h2>How the VM and Elasticsearch were set up (2026-08-31)</h2>
 * <ol>
 *   <li>Firewall rule {@code allow-es-my-ip}: ingress TCP 9200, source range
 *       restricted to the caller's home IP {@code /32} (not {@code 0.0.0.0/0}) -
 *       {@code gcloud compute firewall-rules create allow-es-my-ip
 *       --direction=INGRESS --action=ALLOW --rules=tcp:9200
 *       --source-ranges=<my-ip>/32 --target-tags=es-node}</li>
 *   <li>VM {@code es-learning-node}: {@code e2-medium} (2 vCPU / 4GB - the
 *       practical minimum for a JVM-based single-node ES with 1GB heap),
 *       zone {@code us-central1-a}, Debian 12 image, tag {@code es-node}.
 *       Created via {@code gcloud compute instances create} with a
 *       {@code --metadata=startup-script} that installs Docker and runs:
 *       <pre>
 *       docker run -d --name es -p 9200:9200 -p 9300:9300 \
 *         -e "discovery.type=single-node" \
 *         -e "xpack.security.enabled=false" \
 *         -e "ES_JAVA_OPTS=-Xms1g -Xmx1g" \
 *         docker.elastic.co/elasticsearch/elasticsearch:8.15.0
 *       </pre>
 *       X-Pack security (TLS + password auth, on by default in ES 8.x) was
 *       deliberately disabled for this short-lived learning VM - the actual
 *       access control here is the firewall rule scoped to one IP, not ES's
 *       own auth. A real deployment would keep security on and additionally
 *       restrict network access; skip the VM-level shortcut there.</li>
 *   <li>Verified reachable: {@code curl http://<external-ip>:9200} returned
 *       the cluster info JSON (name, version 8.15.0, cluster_uuid) after
 *       ~60-90s for Docker to pull the ~1.2GB image and boot the JVM.</li>
 * </ol>
 *
 * <h2>Indexing (CatalogIndexDemo)</h2>
 * Both {@code CatalogIndexDemo} and {@code SearchQualityCompareDemo} read
 * the target node from the {@code ES_URL} environment variable rather than
 * a hardcoded address - the VM's external IP is ephemeral (a new one is
 * assigned every time the VM is recreated, and the VM used for this run has
 * since been deleted per the cleanup section below), so hardcoding it would
 * have silently gone stale.
 *
 * <p>Reuses the exact same {@code ProductCatalogGenerator} from
 * {@code _09_ai_commerce_search} (703 products, same template-based
 * generator, same synthetic local-supermarket catalog) so the comparison is
 * apples-to-apples on identical data. No Elasticsearch client library was
 * added as a dependency - plain {@code java.net.http.HttpClient} calling the
 * REST API directly (index creation with an explicit mapping, then the Bulk
 * API with newline-delimited JSON) was enough and kept {@code pom.xml}
 * untouched. Mapping: {@code title}/{@code description} as analyzed
 * {@code text}, {@code category}/{@code brand}/{@code attribute} (the
 * weight/volume/size field) as {@code keyword}, {@code price} as
 * {@code float}. Result: all 703 documents indexed with zero bulk errors,
 * confirmed via {@code _count}.
 *
 * <h2>Search comparison (SearchQualityCompareDemo)</h2>
 * Instead of re-running and re-scoring all 100 queries, a stratified subset
 * of 25 was reused - the SAME query numbers and text as 6-7 per bucket from
 * {@code _09}'s original 100, so results sit side by side against real,
 * already-scored Vertex output (see {@code _09_ai_commerce_search}'s
 * {@code search-experiments/ai-commerce-search-results.txt}). Query shape: a plain
 * {@code multi_match} across {@code title^2}, {@code description},
 * {@code attribute} - no synonyms, no custom analyzer, no tuning. The intent
 * was an honest "what you get for free" comparison on both sides, not a
 * best-case tuned Elasticsearch vs. a default Vertex.
 *
 * <p>Same 1-10 relevance rubric applied to both result sets (top 3 results
 * per query; 10 = precisely on target, 0 = no/irrelevant results):
 *
 * <pre>
 * Bucket              AI Commerce Search avg   Elasticsearch avg
 * A - exact (6q)              10.0                    10.0
 * B - descriptive (6q)         2.83                    7.17
 * C - weight (6q)               6.0                    8.83
 * D - size (7q)                 9.14                    9.29
 * Overall (25q)                 7.08                    8.84
 * </pre>
 *
 * <h2>The actual finding</h2>
 * Plain, untuned Elasticsearch outperformed AI Commerce Search's default
 * configuration on this catalog, specifically because of how each handles
 * literal tokens buried inside longer text. AI Commerce Search's default
 * index returned zero results for queries like {@code "i need something for
 * my dandruff"}, {@code "flour to make chapati"}, and {@code "snack for
 * movie night"} even though the words "dandruff" and "flour" appear verbatim
 * in product titles/descriptions - its default matching is closer to
 * whole-phrase/structured matching than free-text token matching.
 * Elasticsearch's standard analyzer, by contrast, tokenizes and matches on
 * any overlapping word by default, so it caught "dandruff" -&gt; "Anti-Dandruff
 * Shampoo" and "flour" -&gt; "Wheat Flour Atta" with zero configuration. The
 * same pattern showed up on bucket C: a query like {@code "10kg rice bag"}
 * returned nothing from AI Commerce Search (no literal "bag" or exact phrase
 * match) but Elasticsearch matched on "rice" and "10kg" tokens and returned
 * the correct product.
 *
 * <p>This does NOT mean Elasticsearch is "better" in general - it means AI
 * Commerce Search's real value (semantic/vector retrieval, learned ranking
 * from user behavior, personalization, autocomplete/spell-correction) is
 * opt-in configuration this exercise never turned on (see AI Commerce
 * Search's "search-quality tuning" and embedding-based retrieval features,
 * not used in {@code _09}'s default setup). A fair comparison would tune
 * both sides; this one deliberately compared both systems' out-of-the-box
 * defaults, which is itself a useful and often-overlooked data point: a
 * managed AI search product is not automatically smarter than a fully
 * unconfigured open-source keyword engine, and the "AI" branding does not
 * mean semantic matching is on by default.
 *
 * <h2>Cost model - self-hosted Elasticsearch vs. AI Commerce Search</h2>
 * Elasticsearch has no per-search or per-catalog-item charge - the entire
 * cost is the infrastructure it runs on. For this module: 1 x
 * {@code e2-medium} VM in {@code us-central1} costs roughly $0.033/hour
 * on-demand (~$24/month if left running 24/7; sustained-use discounts apply
 * automatically past ~25% monthly usage). Add persistent disk (10GB standard
 * persistent disk, ~$0.04/GB-month = ~$0.40/month) and negligible egress for
 * a learning workload. There is no volume-based pricing dimension at
 * all - the same VM serves 10 searches/day or 10,000 searches/day for
 * the same cost, until CPU/memory becomes the bottleneck and you scale the
 * node (vertically) or add nodes to a cluster (horizontally). Formula:
 *
 * <pre>
 * monthly_cost = (vm_hourly_rate x hours_running) + (disk_gb x disk_rate_per_gb)
 *              + (nodes - 1) x per_additional_node_cost   [once you cluster]
 * </pre>
 *
 * Compare to AI Commerce Search's pricing, which IS volume-based (per search
 * request, plus a per-1000-items-indexed component, per {@code _09}'s
 * pricing section - not confirmed against Google's primary pricing page in
 * that module either, same caveat applies here). The practical trade-off:
 * self-hosted Elasticsearch has a flat cost floor regardless of traffic
 * (you pay for the VM whether or not anyone searches) and adds real
 * operational burden - no managed scaling, no managed backups/snapshots
 * without extra setup, no managed security patching, you own uptime. AI
 * Commerce Search has zero infrastructure to manage but costs scale directly
 * with usage and lock you into Google's ranking behavior unless you invest
 * in its tuning features.
 *
 * <h2>Verification rigor</h2>
 * Nothing in the table above is estimated or fabricated - every number
 * comes from a real run: {@code CatalogIndexDemo}'s bulk response was
 * checked for {@code "errors":false} and the post-refresh {@code _count}
 * endpoint confirmed exactly 703 documents landed; every one of the 25
 * queries in {@code SearchQualityCompareDemo} was actually sent over HTTP
 * to the live node and its real top-3 titles captured verbatim into
 * {@code search-experiments/elasticsearch-results.txt} before scoring; the AI Commerce
 * Search side of the comparison is the same real, already-executed output
 * captured in {@code _09_ai_commerce_search}'s {@code
 * search-experiments/ai-commerce-search-results.txt} (including its genuine {@code NO RESULTS}
 * rows), not a re-imagined or re-scored version of it. Both raw output
 * files are kept in the repo root (not gitignored) specifically so the
 * scores above can be checked against the actual returned data rather than
 * taken on faith.
 *
 * <h2>Cleanup (2026-08-31)</h2>
 * Both the VM and the firewall rule are billable/reachable resources and
 * were torn down the same session immediately after the comparison run:
 * <pre>
 * gcloud compute instances delete es-learning-node --zone=us-central1-a --quiet
 * gcloud compute firewall-rules delete allow-es-my-ip --quiet
 * </pre>
 * Verified via {@code gcloud compute instances list} and
 * {@code gcloud compute firewall-rules list} returning to the pre-module
 * state (only the four GCP-default firewall rules remain, zero instances).
 *
 * <h2>Internal architecture: what "index a document" and "run a query" actually do</h2>
 * Elasticsearch is a distributed layer wrapped around Apache Lucene, and
 * almost everything about its behavior traces back to Lucene's data
 * structure - the INVERTED INDEX:
 * <pre>
 * CatalogIndexDemo -&gt; PUT /products/_doc/p204 (one JSON document)
 *   -&gt; ES routes the document to a SHARD (hash of the doc ID mod shard
 *      count - single shard here, since this is a 1-node throwaway cluster)
 *   -&gt; for each {@code text} field (title, description): the ANALYZER runs
 *      (lowercase -&gt; tokenize on whitespace/punctuation -&gt; here, the stock
 *      "standard" analyzer, no stemming/synonyms configured) producing a
 *      list of TERMS
 *   -&gt; each term is added to the shard's inverted index: term -&gt; list of
 *      document IDs containing it (the literal reverse of "document -&gt;
 *      list of terms," hence "inverted") - this is WHY "dandruff" as a
 *      substring inside a longer description is instantly findable: the
 *      analyzer already broke it out into its own standalone term at index
 *      time, independent of its position or surrounding words
 *   -&gt; {@code keyword} fields (category/brand/attribute) are stored
 *      UNANALYZED - the whole string is one term, which is why they match
 *      only on exact equality and are used for filtering/faceting, never
 *      partial text search
 *   -&gt; the write isn't immediately searchable - it lands in an in-memory
 *      buffer + a translog (for crash durability) and only becomes visible
 *      to search after the next REFRESH (default every 1s), which is why
 *      CatalogIndexDemo's bulk load is verified via {@code _count} rather
 *      than assuming instant visibility
 * SearchQualityCompareDemo -&gt; multi_match query -&gt; the SAME analyzer runs
 *   on the QUERY TEXT (so "i need something for my dandruff" tokenizes into
 *   the same kind of terms as indexing did) -&gt; each shard looks up matching
 *   terms in its own local inverted index (QUERY phase) -&gt; per-shard
 *   candidate doc IDs + relevance scores (BM25 by default - term frequency
 *   weighted by how rare/common the term is across the whole index) are
 *   merged and re-ranked by the coordinating node -&gt; top document IDs are
 *   then fetched from the shards holding them (FETCH phase) for the actual
 *   {@code _source} returned to the client - this two-phase query-then-fetch
 *   design is why Elasticsearch stays fast even with many shards: the
 *   expensive full-document fetch only ever happens for the final top-N
 *   results, never for every candidate considered during scoring.
 * </pre>
 * Clustering (not exercised here - this module is deliberately a 1-node
 * "cluster") adds a MASTER node that owns cluster state (which shards live
 * on which nodes, index settings) and REPLICA shards (a live copy of a
 * primary shard on a different node) - a query hits either the primary or
 * a replica interchangeably for load-balancing, and losing a node only
 * loses data if it held a primary with zero surviving replicas.
 *
 * <h2>System design takeaway</h2>
 * The single biggest design lever in Elasticsearch is the MAPPING decided
 * at index-creation time (text vs. keyword vs. numeric, and which analyzer
 * a text field uses) - unlike a SQL column, a field's type is very hard to
 * change after documents are already indexed (typically requires a full
 * reindex into a new index and an alias swap, not an ALTER TABLE). This
 * module's finding (untuned ES beating untuned AI Commerce Search on
 * natural-language queries) is really a mapping/analyzer story: the stock
 * "standard" analyzer's simple whitespace-and-lowercase tokenization
 * happens to be a strong default for catch-all keyword matching, but a real
 * production deployment would layer on a custom analyzer (synonyms,
 * stemming, stop-words, n-grams for typo tolerance) tuned to the actual
 * catalog's vocabulary - the "zero tuning" comparison in this module is
 * explicitly the floor both systems start from, not either one's ceiling.
 */
package com.ashfaq.gcplab._10_elasticsearch;
