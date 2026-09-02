/**
 * Reading order: 12 (comes after _01_iam ... _11_spanner).
 *
 * <h2>Plain-English primer (read this first if BigQuery is brand new to you)</h2>
 * <p><b>What category is it?</b> BigQuery is a DATA WAREHOUSE (OLAP -
 * online ANALYTICAL processing), not an OLTP database - the same broad
 * category as Snowflake or Redshift, and a genuinely different tool from
 * every other database module in this repo ({@code _04_cloudsql},
 * {@code _06_firestore}, {@code _11_spanner}). Those exist to answer "give
 * me THIS one row, fast, right now, and let me change it" thousands of
 * times a second (an application's live traffic). BigQuery exists to
 * answer "scan across billions of rows and give me an aggregate answer"
 * (yesterday's total revenue by category, this month's search click-
 * through rate by query bucket) - a fundamentally different access pattern,
 * and the reason its whole internal design (see Internal architecture
 * below) looks nothing like Postgres under the hood despite speaking SQL
 * on the surface.
 *
 * <p><b>The hierarchy - and the one big surprise coming from RDBMS/Mongo:</b>
 * <pre>
 * Project                   (same GCP project every other module uses -
 *                             BigQuery has NO separate "instance" concept
 *                             at all, unlike every other DB module here)
 *   -&gt; Dataset               ("learning_bq" - roughly a SQL "schema"/
 *                             database - a named container for tables,
 *                             with its own location (region) and IAM
 *                             permissions)
 *     -&gt; Table               ("employees" - real schema: named, typed
 *                             columns, defined via CREATE TABLE)
 *       -&gt; row                (a normal row - but see "no primary key
 *                             enforcement" below)
 * </pre>
 * The surprise: there is NO "instance" layer, and this is not an
 * oversimplification the way "instance = just capacity" was for Spanner -
 * BigQuery genuinely has nothing to provision, reserve, or leave running.
 * Every other database module in this repo required creating a
 * VM/instance/capacity slab FIRST, before any data could exist -
 * {@code _04_cloudsql}'s instance, {@code _05_redis}'s instance,
 * {@code _11_spanner}'s instance, even {@code _06_firestore}'s database
 * (which at least has a "location" chosen once). BigQuery has none of
 * that: {@code CREATE SCHEMA} (this module's {@link SchemaDemo}) is the
 * FIRST thing that happens, full stop - there's no earlier "buy some
 * capacity" step, because compute is rented per-query, not reserved ahead
 * of time (see "on-demand" in Internal architecture below). This is also
 * why BigQuery is the one module in this repo with a genuine, permanent,
 * always-on free tier rather than a time-limited trial - see Cost below.
 *
 * <p><b>The second surprise: primary keys exist but aren't enforced.</b>
 * {@link SchemaDemo}'s {@code CREATE TABLE} has no {@code PRIMARY KEY}
 * clause at all (BigQuery does support declaring PK/FK constraints as of
 * recent versions, but purely as METADATA for query optimization and
 * external tools - unlike Postgres, inserting a second row with a
 * duplicate {@code employee_id} would NOT be rejected even with a PK
 * declared). This is a direct consequence of the OLAP access pattern: a
 * warehouse ingesting millions of rows from an ETL pipeline every hour
 * cannot afford a uniqueness-check-per-insert the way a low-volume OLTP
 * table can - deduplication in BigQuery is something a query does (e.g.
 * {@code ROW_NUMBER() OVER (PARTITION BY employee_id)}), not something the
 * table enforces on write.
 *
 * <h2>Cost - the one module in this repo with a genuine permanent free tier</h2>
 * Confirmed directly against Google's Always Free documentation (not a
 * time-limited trial like Cloud SQL's 30 days or a from-the-first-minute
 * charge like Spanner): <b>1 TiB of query processing per month, and 10 GiB
 * of storage per month, free forever</b>, on top of the general $300/90-day
 * trial credit this whole project already has. This module's entire
 * footprint - one dataset, one table, a handful of single-row DML
 * statements - processed a few KILOBYTES total, not gigabytes, so real
 * spend was $0 with enormous margin, not "$0 because it was torn down
 * quickly" the way Spanner/Cloud SQL/Redis's $0 depended on session
 * length. Confirmed empirically: the {@code employees} table's metadata
 * showed {@code numBytes: "0"} even mid-module (BigQuery rounds very small
 * tables down), and the dataset was deleted cleanly with
 * {@code bigquery#datasetList} returning empty afterward.
 * <p>
 * Pricing beyond the free tier, for real workloads (figures cross-
 * referenced, not fetched from Google's primary pricing page directly - it
 * is JS-rendered and did not return plain text for this doc, same sourcing
 * caveat as {@code _09_ai_commerce_search}'s pricing section; verify at
 * {@code cloud.google.com/bigquery/pricing} before budgeting a real
 * workload): on-demand queries are billed per TiB of data actually SCANNED
 * by the query (not returned - a {@code SELECT *} with no WHERE clause on
 * a huge table scans and bills for the whole table even if you only look
 * at the first 10 rows), commonly cited around $6.25/TiB; storage splits
 * into ACTIVE (any table modified in the last 90 days) and cheaper
 * LONG-TERM (untouched 90+ days, roughly half the active rate,
 * automatically applied with zero configuration needed). Formula:
 * <pre>
 * monthly_cost = max(0, tib_scanned_by_queries - 1) x query_rate_per_tib
 *              + max(0, avg_gib_stored - 10) x storage_rate_per_gib
 * </pre>
 * The practical implication worth internalizing: unlike every VM-backed
 * database in this repo, an IDLE BigQuery dataset costs nothing beyond its
 * (often free-tier-covered) storage - there is no hourly "the instance
 * exists" charge to remember to tear down, which is exactly why this
 * module's dataset could be created, exercised, and deleted with no time
 * pressure at all, a genuinely different risk profile from
 * {@code _11_spanner}'s "billed from the first minute" module.
 *
 * <h2>Internal architecture: Dremel, columnar storage, and separated
 * storage/compute</h2>
 * <pre>
 * EmployeeCrudDemo.read() -&gt; bigquery.query(sql)
 *   -&gt; submitted as a JOB (the SAME mechanism for DDL, DML, and SELECT -
 *      there is no separate "admin API" the way Spanner has
 *      DatabaseAdminClient vs. DatabaseClient; every operation in this
 *      module, {@link SchemaDemo} and {@link EmployeeCrudDemo} alike, is
 *      the identical bigquery.query(...) call)
 *   -&gt; the query planner (Dremel, Google's internal distributed query
 *      execution engine - the technology BigQuery is built on and
 *      originally published research about) builds an execution tree and
 *      allocates SLOTS (units of query-execution compute - CPU/RAM/
 *      network, borrowed transiently from a massive shared pool for the
 *      duration of one query, not reserved ahead of time on the on-demand
 *      pricing model this module used - see Cost above)
 *   -&gt; data itself lives in Capacitor, Google's columnar storage format,
 *      on Colossus (the same distributed storage substrate underlying GCS,
 *      see {@code _03_storage}'s Internal architecture notes) - COLUMNAR
 *      means a query touching only 2 of a table's 20 columns physically
 *      reads only those 2 columns' data off disk, not whole rows the way
 *      a row-oriented RDBMS like Postgres does; this is THE core reason
 *      BigQuery can aggregate across billions of rows in seconds - it
 *      never reads bytes it doesn't need for the query at hand
 *   -&gt; STORAGE and COMPUTE are fully separate systems that scale
 *      independently - a query's slot allocation is temporary and
 *      unrelated to how much data is stored; this is the architectural
 *      reason there's no "instance" to provision (see the primer above) -
 *      storage just exists in Colossus, and compute is borrowed per-query
 *      from a shared global pool, the polar opposite of Cloud SQL/Spanner
 *      where compute capacity is a persistent, reserved, always-billed
 *      resource independent of whether a query is running this second
 * </pre>
 * DML statements ({@link EmployeeCrudDemo}'s UPDATE/DELETE) run through
 * this exact same job/slot/Dremel pipeline as a SELECT - there is no
 * separate row-level write path the way Postgres's MVCC/WAL or Spanner's
 * Paxos-replicated splits provide; a WHERE-clause DML statement is
 * executed as a distributed rewrite of the affected columnar storage
 * blocks, which is powerful for bulk changes and genuinely wasteful for
 * single-row OLTP-style updates (see Production practices below).
 *
 * <h2>How we understood the RDBMS flow, mapped explicitly</h2>
 * Coming from a background of real RDBMS (Postgres/MySQL) and MongoDB, the
 * cleanest way to place BigQuery: it LOOKS like a SQL database on the
 * surface (real CREATE TABLE, real typed columns, real SELECT/INSERT/
 * UPDATE/DELETE - all four demonstrated end to end in
 * {@link EmployeeCrudDemo}) but behaves like neither an RDBMS nor MongoDB
 * underneath:
 * <table border="1">
 *   <tr><th></th><th>RDBMS (Cloud SQL)</th><th>MongoDB-family (Firestore)</th><th>BigQuery</th></tr>
 *   <tr><td>Optimized for</td><td>many small transactional reads/writes</td><td>many small transactional reads/writes</td><td>few large analytical scans</td></tr>
 *   <tr><td>Storage layout</td><td>row-oriented</td><td>document-oriented</td><td>column-oriented</td></tr>
 *   <tr><td>PK uniqueness</td><td>enforced</td><td>enforced (document ID)</td><td>NOT enforced (metadata only)</td></tr>
 *   <tr><td>Compute vs. storage</td><td>bundled (one VM)</td><td>bundled (managed, but not exposed)</td><td>fully separate, compute is per-query</td></tr>
 *   <tr><td>"Instance" to provision</td><td>yes</td><td>no (but a location/mode choice)</td><td>no - none at all</td></tr>
 *   <tr><td>Typical latency</td><td>milliseconds</td><td>milliseconds</td><td>seconds (even a trivial query has real per-job overhead)</td></tr>
 * </table>
 * The practical rule that falls out of this comparison, and the one worth
 * remembering above all the syntax similarity: reach for BigQuery when the
 * question is "what happened, in aggregate, across a lot of data" (this
 * repo's own {@code docs/roadmap.md} already flags it as the natural home
 * for search-query logs and click/conversion analysis) - never as a
 * replacement for Cloud SQL/Spanner/Firestore's job of serving a live
 * application's request-by-request reads and writes, even though today's
 * demo just proved the SQL syntax for doing exactly that technically works.
 *
 * <h2>Doing it via Console UI (mirrors Cloud SQL Studio / Spanner Studio)</h2>
 * <ol>
 *   <li>Console search bar -&gt; type "BigQuery" -&gt; the BigQuery product
 *       page (Console URL: {@code console.cloud.google.com/bigquery}).</li>
 *   <li><b>No "create instance" step at all</b> - the Explorer panel on the
 *       left shows the current project directly; right-click it -&gt;
 *       "Create dataset". Name it (e.g. {@code learning_bq}), pick a
 *       location/region (US multi-region is the free-tier-friendly
 *       default), leave default table expiration unset unless every table
 *       in this dataset should auto-delete after N days (a genuinely useful
 *       setting for a scratch/experiment dataset).</li>
 *   <li>Inside the new dataset -&gt; "Create table" - choose "Empty table",
 *       name it, and either use the visual schema editor (add
 *       {@code employee_id}/{@code name}/{@code role}/{@code created_at}
 *       as fields with types) or toggle "Edit as text" and paste a schema
 *       definition directly.</li>
 *   <li><b>The Query editor</b> (top of the BigQuery page, always visible)
 *       is where SELECT/INSERT/UPDATE/DELETE/CREATE TABLE all get typed and
 *       run - the exact same {@code bigquery.query(...)} pipeline this
 *       module's Java code calls, just typed by hand instead of
 *       constructed in code. Before running, Console shows an ESTIMATED
 *       bytes-to-be-scanned figure next to the Run button - this is worth
 *       genuinely watching once tables get large, since it's a live preview
 *       of what that specific query will cost against the free-tier/
 *       on-demand pricing above, BEFORE spending the query.</li>
 *   <li>"Job history" (left nav) shows every query/DDL/DML job ever run
 *       against the project, each with its actual bytes-processed and
 *       slot-time figures - the Console equivalent of this module's
 *       REST-based job/table inspection used to confirm cost above.</li>
 *   <li>When done - delete the DATASET (right-click it -&gt; "Delete"),
 *       which removes every table inside it in one step, same
 *       "delete the parent, not each child" pattern as Spanner's instance
 *       deletion - though here there's no ongoing cost being stopped by
 *       doing so, purely tidiness, since nothing bills while idle.</li>
 * </ol>
 *
 * <h2>Real data: take, massage, persist, verify (ProductAnalyticsDemo)</h2>
 * {@link EmployeeCrudDemo} proves the SQL surface works on one row -
 * {@link ProductAnalyticsDemo} is the module's actual realistic BigQuery
 * workload, end to end, using real data instead of one fabricated row:
 * <pre>
 * 1. TAKE:    reuse the exact same 703-product synthetic catalog
 *             ({@code com.ashfaq.gcplab._09_ai_commerce_search.ProductCatalogGenerator})
 *             that {@code _09} and
 *             {@code _10} already reuse for apples-to-apples comparisons -
 *             same source of truth, no new fixture invented for this module.
 * 2. MASSAGE: flatten each retail-API {@code Product} proto (nested
 *             categories/brands lists, a custom-attributes map) into a flat
 *             row shape (id/title/category/brand/price/attribute_key/
 *             attribute_value) - the same extraction logic
 *             {@code _09}'s CatalogExportDemo already established, reused
 *             here rather than reinvented.
 * 3. PERSIST: a real bulk LOAD JOB via {@code BigQuery.writer(...)} -
 *             streams newline-delimited JSON directly into a load job with
 *             zero GCS staging file needed - NOT 703 individual DML INSERT
 *             statements (which Production practices #1 below explains is
 *             both slow and a real quota risk).
 * 4. VERIFY:  BEFORE ever touching BigQuery, plain Java independently
 *             computes two facts about the in-memory product list: the
 *             exact row count, and which product has the single highest
 *             price. AFTER the load, the same two facts are re-derived via
 *             real SQL against BigQuery ({@code SELECT COUNT(*)}, and
 *             {@code ORDER BY price DESC LIMIT 1}) and compared line-by-line
 *             against Java's independently-computed answer, printing an
 *             explicit VERIFIED/MISMATCH per check - not "the demo ran
 *             without an exception," an actual assertion that the
 *             persisted data and the query engine agree with what the
 *             source data actually contains.
 * </pre>
 * A real run against this catalog: {@code VERIFIED: row count - expected
 * 703, BigQuery reports 703} and {@code VERIFIED: highest-priced product -
 * expected p602 at 1233.0, BigQuery reports p602 at 1233.0} - both checks
 * passed, confirmed by the demo's own exit behavior (it throws if either
 * mismatches, rather than printing a mismatch and continuing as if nothing
 * happened). The table's reported size after loading all 703 rows:
 * {@code numBytes: 59121} (~58 KB) - confirming by direct measurement, not
 * assumption, how enormously this module's entire real workload sits inside
 * the 1 TiB/10 GiB free tier described in the Cost section above.
 * <p>
 * The actual payoff query - the reason this data belongs in a warehouse at
 * all, not achievable by looking at any single row - is the {@code GROUP BY
 * category} aggregation: average price and product count per category
 * across all 703 products in one query, the kind of "massage" analytical
 * question ({@code _09}'s Production practices section flags exactly this -
 * search-query logs and click/conversion analysis at aggregate scale) that
 * is BigQuery's actual reason for existing, versus the row-level CRUD
 * {@link EmployeeCrudDemo} exists purely to make the OLTP-vs-OLAP contrast
 * concrete.
 *
 * <h2>Filter/map/transform, and does it persist itself? (TransformAndPersistDemo)</h2>
 * A direct, common question coming from a stream-processing background
 * (Flink, per this repo's own {@code ~/.claude/rules/infra.md}): can
 * BigQuery filter and map data the way a Flink job would, and does the
 * transformed result get stored automatically? Yes to filter/map (both are
 * plain SQL - {@code WHERE} is a filter, computed {@code SELECT}
 * expressions are a map, exactly what {@code ProductAnalyticsDemo}'s
 * {@code GROUP BY} query already demonstrated for aggregation); NO to
 * automatic persistence - a bare {@code SELECT}'s result is NOT saved
 * anywhere durable, it's returned to the caller (or briefly cached, ~24h,
 * keyed to the exact query text) and then gone. {@link
 * TransformAndPersistDemo} proves both halves with a real run rather than
 * asserting it: {@code CREATE TABLE ... AS SELECT} (CTAS) wraps a FILTER
 * ({@code WHERE category = 'Apparel'}) and a MAP (a computed tax-inclusive
 * price column, an uppercased brand column) into one statement whose
 * result becomes a genuinely new, separate, permanent table - proven by
 * running a SEPARATE, LATER query against just that new table (not the
 * original CTAS job's own output) and getting a real, independently-
 * verified answer back (200 Apparel rows, matching a plain Java stream
 * filter over the same in-memory catalog computed BEFORE the CTAS ran).
 * <p>
 * The deeper point this proves architecturally: BigQuery has NO notion of
 * "the output of my last query" as a first-class thing the way a REPL or a
 * Flink job's downstream operator does - every persistence boundary is
 * explicit, either a full {@code CREATE TABLE ... AS SELECT} /
 * {@code INSERT INTO ... SELECT} (a one-time, job-triggered materialization
 * - what this demo does), a MATERIALIZED VIEW (the closest BigQuery
 * equivalent to "define the transform once and keep it kept up to date" -
 * Google auto-refreshes it incrementally as the underlying table changes,
 * not exercised in this module but the right tool for a transform that
 * needs to stay fresh rather than be recomputed by hand), or a SCHEDULED
 * QUERY (the same CTAS/INSERT pattern this demo used, run automatically on
 * a cron-like schedule - the standard BigQuery-native alternative to
 * "write a small ETL job" for periodic batch transforms).
 * <p>
 * <b>How this actually compares to Flink, concretely:</b>
 * <table border="1">
 *   <tr><th></th><th>Flink</th><th>BigQuery</th></tr>
 *   <tr><td>Input</td><td>an unbounded LIVE stream (Kafka/Pub-Sub)</td><td>data already sitting at rest in a table</td></tr>
 *   <tr><td>When a transform runs</td><td>continuously, event-by-event or micro-batch</td><td>once per query/job, or on a schedule (Scheduled Queries)</td></tr>
 *   <tr><td>Typical latency</td><td>milliseconds to low seconds</td><td>seconds or more per job, even on tiny data (see this module's own per-job overhead note in Production practices)</td></tr>
 *   <tr><td>What "storage" means</td><td>keyed operator STATE, checkpointed for fault-tolerance - not a queryable dataset by itself</td><td>the table itself - genuinely durable, columnar, directly queryable by anyone with access</td></tr>
 *   <tr><td>Where results go</td><td>a SINK the job writes to (a topic, a DB, a file) - Flink itself isn't where you go query the result later</td><td>the target table IS where you query the result later, if you persisted it (this section) - or nowhere, if you didn't (a bare SELECT)</td></tr>
 * </table>
 * The practical takeaway for a search team using both: they are not
 * competing tools solving the same problem at different scales - Flink is
 * the right place for continuous, low-latency filter/map/enrichment on a
 * LIVE event stream (e.g. real-time UserEvent enrichment before it lands
 * anywhere, see {@code _09_ai_commerce_search}'s Production practices
 * UserEvents section), and BigQuery is the right place for that same data
 * to land DURABLY once Flink is done with it, so it can be queried,
 * joined, and aggregated across historical volume later - the standard
 * real-world architecture is Flink (or Dataflow, GCP's managed Flink/Beam
 * runner) reading from Pub/Sub, transforming in-flight, and writing its
 * sink output directly into a BigQuery table (via the Storage Write API),
 * not a choice between the two for the same job.
 *
 * <h2>Production practices - what this demo skips that real work needs</h2>
 *
 * <p><b>1. DML is not a replacement for a real OLTP database - the central
 * lesson of this module's own {@link EmployeeCrudDemo}, and exactly why
 * {@link ProductAnalyticsDemo} loads via a bulk JOB instead.</b> It proved
 * UPDATE/DELETE work, deliberately, specifically to make this point
 * concrete: each DML statement here is a full query JOB (real job-
 * submission latency, real per-job overhead, no row-level locking the way
 * Postgres/Spanner provide) - using BigQuery for high-frequency single-row
 * application writes (a user's profile update, a shopping-cart change) is
 * a genuine anti-pattern, both slow (seconds of job overhead per write,
 * versus milliseconds for Cloud SQL/Spanner/Firestore) and subject to
 * BigQuery's DML rate quotas (a real per-table daily limit on DML
 * statements exists specifically to prevent this misuse). A search team's
 * real BigQuery workload is almost always the opposite shape: periodic
 * BULK loads (a nightly export of search logs/click events from Pub/Sub or
 * a data pipeline, landing as one large load job) feeding analytical
 * queries, not row-by-row mutation traffic.
 *
 * <p><b>2. Partitioning and clustering - the actual cost/performance
 * levers at real scale, not exercised by this module's tiny table.</b> A
 * table PARTITIONED by date (e.g. {@code PARTITION BY DATE(created_at)})
 * lets a query with a date filter scan only the relevant partitions
 * instead of the whole table - directly reduces bytes scanned, directly
 * reduces cost (see the Cost formula above - cost IS bytes scanned).
 * CLUSTERING (e.g. {@code CLUSTER BY employee_id}) further sorts data
 * within each partition so filters on the clustering column skip
 * irrelevant blocks too. For a search team's query-log table (naturally
 * date-partitioned by when each search happened, naturally clustered by
 * something like query-bucket or user segment), this is the single biggest
 * lever on both query cost and latency at real volume - a table without it
 * scans everything, every time, regardless of how narrow the actual
 * question is.
 *
 * <p><b>3. Query cost estimation BEFORE running, not after.</b> Console's
 * live bytes-estimate (see the UI walkthrough above) is the manual version
 * of a real discipline: a service that constructs BigQuery SQL
 * programmatically should use "dry run" mode
 * ({@code QueryJobConfiguration.Builder.setDryRun(true)}) to get the
 * estimated bytes processed WITHOUT actually running the query or being
 * billed for it, and can reject/flag a query whose estimate exceeds a
 * sane threshold before submitting it for real - the practical guardrail
 * against an accidentally-unfiltered query silently scanning (and billing
 * for) an entire multi-terabyte table.
 *
 * <p><b>4. Access control is dataset-level IAM plus (optionally) row/
 * column-level security.</b> This module granted {@code backendDeveloper}
 * project-wide BigQuery permissions (see "How we set this up" below) -
 * fine for a single-developer learning project, and a real anti-pattern at
 * team scale. Production BigQuery access control layers: IAM roles scoped
 * to a specific DATASET (not project-wide) via
 * {@code bigquery.datasets.getIamPolicy}/{@code setIamPolicy} - e.g. an
 * analytics team's identity gets {@code roles/bigquery.dataViewer} on the
 * search-logs dataset specifically, not blanket access to every dataset in
 * the project - plus, for genuinely sensitive columns (PII in a user-
 * events table), COLUMN-LEVEL security via policy tags (Data Catalog) and
 * ROW-LEVEL security via {@code CREATE ROW ACCESS POLICY}, neither
 * exercised in this module's single-table demo but standard for any
 * dataset holding real user data.
 *
 * <p><b>5. Slot reservations - for predictable cost at real query volume.</b>
 * This module used on-demand pricing throughout (pay per query, the right
 * default for unpredictable/low volume, which is exactly this module's
 * profile). A team running BigQuery as a genuine, continuous analytics
 * workload (dashboards refreshed constantly, a search team's daily
 * relevance/CTR reporting pipeline) often switches to a flat-rate
 * RESERVATION (buy a fixed number of slots for predictable monthly cost
 * instead of per-TiB-scanned billing) once on-demand spend becomes large
 * and volume becomes predictable enough that flat-rate is cheaper - a
 * capacity-planning decision analogous to Spanner's manual-vs-autoscaler
 * choice, evaluated once real usage patterns are known, not decided up
 * front.
 *
 * <p><b>6. Testing - the BigQuery emulator (newer, more limited than
 * Firestore's/Spanner's), or a scratch dataset with auto-expiry.</b> A
 * community/Google-provided BigQuery emulator exists but has real
 * limitations (not every SQL feature is supported) - the more common
 * pragmatic pattern for CI/local testing is a real but ISOLATED scratch
 * dataset with a short default table expiration set (see the UI
 * walkthrough's "default table expiration" note) so test tables self-clean
 * even if a test run doesn't tear down explicitly - closer in spirit to
 * this module's manual create-then-drop cycle, made automatic.
 *
 * <p><b>7. Code-level habit: reuse the {@code BigQuery} client, same rule
 * as every other module.</b> {@code BigQueryOptions...getService()} builds
 * a client managing its own HTTP transport - one instance for a service's
 * lifetime (a Spring {@code @Bean}), never per-request, the same "build
 * once, reuse everywhere" rule already stated for every other GCP client
 * in this repo ({@code _06_firestore}, {@code _08_vertexai},
 * {@code _09_ai_commerce_search}, {@code _11_spanner}) - this module's
 * separate {@code main()}-per-CLI-invocation demos each build their own
 * client only because each run IS the whole process lifetime.
 *
 * <h2>How we set this up (2026-09-02, torn down same session)</h2>
 * <ul>
 *   <li>Enabled {@code bigquery.googleapis.com} (auto-enabled several
 *       related sub-APIs too: bigqueryconnection, bigquerystorage, etc. -
 *       not individually used by this module's simple query-job pattern).</li>
 *   <li>Extended {@code backendDeveloper} (same PATCH pattern as every
 *       prior module) with BigQuery's data-plane and job permissions:
 *       {@code bigquery.datasets.create/get/delete/getIamPolicy},
 *       {@code bigquery.tables.create/get/getData/updateData/delete/list},
 *       {@code bigquery.jobs.create/get/list} - every one accepted on the
 *       first attempt, unlike Spanner's operation-polling permission that
 *       needed a second grant.</li>
 *   <li>{@link SchemaDemo} created the {@code learning_bq} dataset and
 *       {@code employees} table via plain {@code CREATE SCHEMA}/
 *       {@code CREATE TABLE} DDL - both submitted through the same
 *       {@code bigquery.query(...)} call every other operation in this
 *       module uses, no separate admin API.</li>
 *   <li>{@link EmployeeCrudDemo} ran the full cycle against a fixed UUID
 *       row: create (parameterized {@code INSERT}) -&gt; read (confirmed the
 *       row, including the auto-set {@code CURRENT_TIMESTAMP()}) -&gt; update
 *       (parameterized {@code UPDATE ... WHERE}, confirmed the role field
 *       changed on re-read) -&gt; delete (parameterized {@code DELETE ...
 *       WHERE}) -&gt; read again (confirmed gone) - every step actually run
 *       and its output checked.</li>
 *   <li>Verified near-zero footprint directly via REST: the table's
 *       {@code numBytes} reported {@code "0"} mid-module (BigQuery rounds
 *       very small tables down), confirming the whole exercise stayed
 *       enormously inside the 1 TiB query / 10 GiB storage free tier.</li>
 *   <li>{@link ProductAnalyticsDemo} then ran the real bulk-data pattern:
 *       generated the same 703-product catalog {@code _09}/{@code _10}
 *       reuse, computed expected row-count and max-price facts in plain
 *       Java, bulk-loaded all 703 as NDJSON via a real load job (no GCS
 *       staging), then re-derived both facts via SQL against BigQuery and
 *       compared - both checks printed {@code VERIFIED}, confirmed by the
 *       demo's own exit behavior (throws on any mismatch rather than
 *       printing and continuing). Table size after load confirmed via
 *       REST: {@code numBytes: 59121} (~58 KB, 703 rows) - real evidence of
 *       how far inside the free tier this entire module's data footprint
 *       sits.</li>
 *   <li>{@link TransformAndPersistDemo} then ran the filter+map+persist
 *       proof: a CTAS filtering to Apparel-only and adding a computed
 *       tax-inclusive price plus an uppercased brand column, verified
 *       against an independent Java-side {@code Stream.filter(...).count()}
 *       over the same in-memory catalog (200 Apparel products both sides,
 *       printed {@code VERIFIED}), then confirmed durable via a SEPARATE
 *       follow-up query against the new {@code apparel_with_tax} table
 *       alone. REST confirmed both {@code products} and
 *       {@code apparel_with_tax} existed as genuinely separate tables
 *       side by side before teardown.</li>
 *   <li>Torn down via a single {@code DELETE .../datasets/learning_bq
 *       ?deleteContents=true} REST call (removes the dataset and every
 *       table inside it - {@code employees}, {@code products}, and
 *       {@code apparel_with_tax} all - in one step, HTTP 204 confirmed)
 *       rather than dropping each table individually. Confirmed via REST
 *       ({@code GET .../datasets} returning an empty list, no
 *       {@code datasets} field at all) that zero datasets remain. Unlike
 *       every other paid module in this repo, there was no time-pressure
 *       teardown here - an idle BigQuery dataset costs nothing beyond its
 *       (free-tier-covered) storage, so this cleanup was pure tidiness, not
 *       cost-avoidance.</li>
 * </ul>
 */
package com.ashfaq.gcplab._12_bigquery;
