/**
 * Reading order: 11 (comes after _01_iam ... _10_elasticsearch).
 *
 * <h2>Plain-English primer (read this first if Spanner is brand new to you)</h2>
 * <p><b>What category is it?</b> Spanner is a RELATIONAL database - SQL,
 * schema, tables, joins, ACID transactions - so mentally it sits closer to
 * Postgres than to MongoDB. But its ENGINEERING is closer to how MongoDB/
 * Cassandra scale (auto-sharding across machines). Google's own label for
 * it: "the only database that's both horizontally scalable AND strongly
 * consistent" - a combination the CAP theorem says should be impossible.
 * Spanner gets away with it using TrueTime, a globally-synchronized atomic-
 * clock system across every Google datacenter, instead of the usual
 * consistency-vs-availability trade-off (see Internal architecture below
 * for how that actually works under the hood). Short version: Postgres's
 * data model + MongoDB's horizontal-scaling instinct + transactions that
 * never break, even across continents.
 *
 * <p><b>The hierarchy - mapped to what an RDBMS/Mongo background already
 * knows:</b>
 * <pre>
 * Instance                  - NOT really a database concept at all. It's a
 *                              slab of reserved compute capacity (nodes, or
 *                              fractional "processing units") you buy up
 *                              front - like reserving "this much CPU/RAM
 *                              budget" before a database even exists yet.
 *   -&gt; Database              - ONE logical database, ONE schema, shared
 *                              across the whole instance's capacity. An
 *                              instance can host multiple databases.
 *     -&gt; Table               - real SQL DDL: CREATE TABLE employees (...)
 *                              PRIMARY KEY (...) - schema-WIDE, defined
 *                              once, not per-node.
 *       -&gt; Row                - a normal row, same as any RDBMS.
 * </pre>
 * <b>The one genuinely new idea, and the most common misconception:</b> it
 * is tempting to picture this like a sharded MongoDB cluster - "each node
 * is basically its own mini-database, holding its own slice of the
 * schema." Spanner is NOT that. There is one schema, one database, full
 * stop. What Spanner actually does is slice each TABLE's ROWS into chunks
 * called <b>splits</b> (by primary-key range) and scatter those chunks
 * across the instance's nodes automatically and invisibly, rebalancing as
 * data grows - you never choose which node holds which data, and a node
 * never "owns" a table or a schema, it just holds a shifting subset of ROWS
 * from potentially every table. A <b>interleaved table</b> (the second new
 * concept, no RDBMS equivalent) is a schema hint - stronger than a foreign
 * key - that says "always physically co-locate this child row on the SAME
 * split as its parent row," so a parent-&gt;child join never has to cross
 * nodes; a plain foreign key still joins correctly without this, it just
 * may touch more nodes to do it. If you've used Postgres with the Citus
 * extension, or heard of CockroachDB, that's the closest familiar analogy -
 * both were explicitly inspired by Google's public Spanner research paper:
 * one SQL schema, rows auto-sharded across a cluster of machines under the
 * hood, joins still work correctly across shards, just potentially slower
 * when the joined rows aren't co-located.
 *
 * <p><b>Full join capability, yes - with a performance nuance.</b> Because
 * it's genuinely relational (unlike Firestore/Mongo), every SQL join works,
 * including across tables whose rows landed on different nodes - Spanner
 * runs a real distributed join and merges results transparently, exactly
 * as if it were one machine. The join is always CORRECT; interleaving
 * (above) is purely a lever for making PARENT-CHILD joins specifically
 * faster by guaranteeing co-location, not a requirement for joins to work
 * at all.
 *
 * <p><b>Why this exists / when to actually reach for it:</b>
 * <ul>
 *   <li>Cloud SQL (Postgres, {@code _04_cloudsql}) = one managed VM (or
 *       VM + replica) - the right, cheaper default until write volume
 *       outgrows one machine, at which point there is no horizontal path
 *       forward at all, ever.</li>
 *   <li>Firestore/MongoDB = scales horizontally with ease, but gives up
 *       joins and strict relational structure to get there.</li>
 *   <li>Spanner = keeps SQL/joins/transactions AND scales horizontally,
 *       paid for with real dollars (no free tier, billed even sitting
 *       fully idle) and a bit more schema-design thought up front (splits,
 *       interleaving).</li>
 * </ul>
 * Real-world fits: global ledgers/banking, e-commerce order/inventory
 * systems that outgrew one DB server, multiplayer game state, ad-serving
 * systems - Google's own Ads and Google Play backends run on Spanner for
 * exactly this reason. For roughly 95% of applications - including every
 * other module in this repo - Cloud SQL remains the right, cheaper answer;
 * Spanner is a deliberate reach for a specific scale problem, never a
 * default choice.
 *
 * <p><b>Doing it entirely via Console UI (no code, mirrors _04_cloudsql's
 * Cloud SQL Studio pattern):</b>
 * <ol>
 *   <li>Console search bar -&gt; type "Spanner" -&gt; the Spanner product
 *       page.</li>
 *   <li><b>Create Instance</b>: name it (e.g. {@code learning-spanner}).
 *       Configuration: Regional (cheaper, one region) vs. Multi-region
 *       (pricier, survives a whole region going down) - pick Regional,
 *       {@code us-central1}. Compute capacity is the important cost knob -
 *       Console DEFAULTS to "1 node"; instead choose <b>Processing
 *       units</b> and set it to <b>100</b> (the smallest allowed unit,
 *       1/10th of a node) - this single field is the biggest lever on the
 *       bill. Click Create.</li>
 *   <li><b>Create a database</b> (inside the instance page -&gt;
 *       "Databases" tab -&gt; Create Database): name it (e.g.
 *       {@code learning-db}). The wizard lets you paste {@code CREATE
 *       TABLE} DDL right there, or create it empty and add schema
 *       afterward.</li>
 *   <li><b>Spanner Studio</b> (left nav, inside the database) - a built-in
 *       SQL editor, the same experience as {@code _04_cloudsql}'s Cloud SQL
 *       Studio: run {@code CREATE TABLE}, {@code INSERT}, {@code SELECT},
 *       {@code UPDATE} directly in the browser, no client library or local
 *       tool needed.</li>
 *   <li><b>When done - delete the INSTANCE, not just the database.</b>
 *       Deleting the instance kills every database inside it and stops
 *       billing immediately - this is the step that actually protects the
 *       wallet, because an empty database costs exactly the same as a full
 *       one (see below).</li>
 * </ol>
 * <p><b>The one fact worth internalizing above everything else:</b> Spanner
 * bills for the CAPACITY reserved, not for what was actually done with it -
 * same as Cloud SQL and Memorystore Redis, unlike Firestore/GCS which bill
 * by real usage. An idle, empty Spanner instance costs exactly the same,
 * every hour, as one being hammered with traffic.
 *
 * <h2>Cloud Spanner = globally-consistent, horizontally-scalable RDBMS</h2>
 * The module this one is meant to be read against is {@code _04_cloudsql}:
 * both speak SQL, both have tables/rows/transactions, both feel like a
 * normal relational database from application code - but they solve
 * fundamentally different scaling problems. Cloud SQL is a single managed
 * VM (or a primary + replicas) - great fit, and much cheaper, for anything
 * that fits comfortably on one machine. Spanner is Google's own internally-
 * built, globally-distributed database (the same lineage of technology
 * {@code _06_firestore}'s internal-architecture notes point to) exposed as
 * a product - it shards data across many machines automatically and keeps
 * strong consistency across those shards even across regions, something no
 * single-VM database can do without giving up either consistency or
 * availability (the classic CAP-theorem trade-off Spanner is famous for
 * appearing to sidestep, via Google's TrueTime globally-synchronized clock
 * infrastructure - see Internal architecture below).
 *
 * <h2>Cost - the reason this module is deliberately smaller than the others</h2>
 * Spanner has NO free tier and NO trial-sized minimum the way Cloud SQL's
 * 30-day trial gave a large instance for $0 - every instance is billed from
 * the first minute, priced by "compute capacity" (a node, or fractional
 * "processing units" - 100 processing units = 1/10th of a node, the
 * smallest unit purchasable and what this module used) plus storage plus
 * network egress:
 * <pre>
 * monthly_cost = (processing_units / 1000 x node_hourly_rate x hours_running)
 *              + (storage_gb x storage_rate_per_gb_month)
 *              + (egress_gb x egress_rate_per_gb)
 * </pre>
 * At 100 processing units in us-central1, the rate is roughly
 * $0.65-0.90/hour depending on current pricing (single-region config,
 * cheaper than multi-region since multi-region synchronously replicates
 * across 3+ regions for its stronger SLA) - this module's actual instance
 * lifetime was well under 15 minutes (create -&gt; schema -&gt; CRUD cycle -&gt;
 * delete), so real spend was a small fraction of a dollar. Unlike Cloud
 * SQL/Redis (also always-on billing, but at least $0 during a trial
 * window), there was no free window here at all - which is exactly why
 * this module's instance was created, exercised, and deleted in one
 * continuous session rather than left running between steps, and why the
 * table below shows processing units rather than defaulting to a full node
 * (1000 processing units) the way Console's wizard suggests. Pricing
 * reference: {@code cloud.google.com/spanner/pricing}.
 *
 * <h2>Concept flow: instance -&gt; database -&gt; table -&gt; row, plus interleaving</h2>
 * <pre>
 * Spanner instance         (the allocated compute capacity: "learning-spanner",
 *                            100 processing units, regional-us-central1 -
 *                            NOT the data itself, purely provisioned capacity
 *                            that one or more databases share)
 *   -&gt; database             ("learning-db" - a project/instance can host
 *                            multiple databases, each with its own schema,
 *                            sharing the instance's compute capacity)
 *     -&gt; table              ("employees" - real SQL DDL, a proper schema,
 *                            unlike Firestore's schemaless documents)
 *       -&gt; row               (one employee record, keyed by employee_id)
 * </pre>
 * The one structural concept with no equivalent in Cloud SQL/Postgres:
 * <b>INTERLEAVED tables</b> (not used in this module's single-table demo,
 * but core to real Spanner schema design) - a child table can be declared
 * physically interleaved inside its parent (e.g. {@code orders} interleaved
 * inside {@code customers}), which tells Spanner to co-locate a parent row
 * and all its children on the SAME shard/split. This is how Spanner gets
 * SQL-JOIN-like locality without breaking its horizontal-sharding model -
 * a plain foreign key (also supported) gives you referential integrity but
 * NOT that co-location guarantee, so a join across a plain FK can still
 * cross shards, while an interleaved join never does.
 *
 * <h2>Internal architecture: how Spanner gets global consistency without a single master</h2>
 * <pre>
 * EmployeeCrudDemo -&gt; DatabaseClient.write(mutation) / readWriteTransaction()
 *   -&gt; the row's primary key hashes to a SPLIT (Spanner's unit of
 *      horizontal sharding - the table is range-partitioned by primary key
 *      across many splits, each split replicated - typically 3-5 replicas
 *      per split for regional configs, more for multi-region - across
 *      separate machines/zones)
 *   -&gt; a WRITE goes through Paxos consensus across that split's replicas -
 *      a majority must durably acknowledge before the write commits, the
 *      same fundamental idea as Cloud SQL's synchronous HA replica but
 *      applied per-shard, automatically, at massive scale, not something
 *      you opt into per-instance
 *   -&gt; every committed transaction gets a COMMIT TIMESTAMP assigned via
 *      TrueTime - Google's globally-synchronized clock infrastructure
 *      (GPS + atomic clocks in every datacenter, bounding clock uncertainty
 *      to a known small window) - this is the actual trick behind
 *      Spanner's headline claim of external (linearizable) consistency
 *      across regions with no single global lock: instead of coordinating
 *      a lock across every replica on every read, Spanner just WAITS OUT
 *      the known clock-uncertainty window before acknowledging a commit,
 *      guaranteeing any later transaction (anywhere) sees a strictly later
 *      timestamp - a genuinely novel distributed-systems technique, not
 *      just marketing
 *   -&gt; a READ (client.singleUse().readRow(...) here) can be served by ANY
 *      replica of the relevant split (not just a "primary") because every
 *      replica agrees on the same timestamp-ordered history - this is why
 *      Spanner reads scale near-linearly with replica count the way a
 *      single-primary system's reads never can
 * </pre>
 * The SchemaDemo's DDL (CREATE TABLE) is a SEPARATE, slower, asynchronous
 * long-running operation (same {@code Operation}-polling shape as
 * {@code _09_ai_commerce_search}'s catalog import) - schema changes have to
 * safely propagate and become consistent across every split before they're
 * "done," which is inherently a different (and much rarer) code path than
 * a single-row data write.
 *
 * <h2>System design: when Spanner is (and isn't) the right call</h2>
 * Spanner earns its much higher floor cost specifically for workloads that
 * need BOTH real relational structure (joins, transactions, secondary
 * indexes) AND horizontal write scale beyond what one machine can serve -
 * the intersection Cloud SQL can't reach (vertical-only, see _04_cloudsql's
 * system-design note) and Firestore's document model deliberately gave up
 * (no joins) to achieve. Classic real-world fits: global financial ledgers,
 * multiplayer game state, large-scale inventory/ordering systems spanning
 * regions - Google's own Ads and Google Play backend run on Spanner for
 * exactly this reason. For anything smaller than "this will outgrow one
 * database server" - which is most applications, including everything else
 * in this learning repo - Cloud SQL is the right default: Spanner's
 * interleaving/split-aware schema design and always-on billing are real
 * costs (both in dollars and design complexity) that should be a deliberate
 * trade for a specific scale requirement, never a default reach.
 *
 * <h2>How we set this up (2026-09-01, torn down same session)</h2>
 * <ul>
 *   <li>Enabled {@code spanner.googleapis.com}.</li>
 *   <li>Extended {@code backendDeveloper} (via {@code gcloud iam roles update},
 *       same PATCH-not-overwrite pattern as _01_iam's {@code UpdateRoleDemo})
 *       with Spanner's data-plane permissions:
 *       {@code spanner.databases.select/read/write/get/updateDdl},
 *       {@code spanner.databases.beginOrRollbackReadWriteTransaction},
 *       {@code spanner.databaseOperations.get} (needed to poll the async
 *       DDL operation - hit as a real {@code PERMISSION_DENIED} on the
 *       first run, the table had actually already landed server-side by
 *       then; same "operation succeeded, polling needs its own grant"
 *       shape as _09's import-operation SDK quirk),
 *       {@code spanner.sessions.create/get/delete}, {@code spanner.instances.get}.</li>
 *   <li>Instance created via {@code gcloud spanner instances create
 *       learning-spanner --config=regional-us-central1
 *       --processing-units=100} - the smallest purchasable capacity (1/10th
 *       of a node), deliberately, over the Console wizard's 1000-processing-
 *       unit ("1 node") default.</li>
 *   <li>Database created via {@code gcloud spanner databases create
 *       learning-db --instance=learning-spanner} - empty, no schema yet.</li>
 *   <li>{@link SchemaDemo} ran the {@code employees} table's CREATE TABLE via
 *       DDL, impersonating backend-dev-sa - real async operation, confirmed
 *       via {@code gcloud spanner databases ddl describe}.</li>
 *   <li>{@link EmployeeCrudDemo} ran the full cycle against a fixed UUID row:
 *       create (Mutation insert with {@code Value.COMMIT_TIMESTAMP} for
 *       {@code created_at}) -&gt; read (confirmed the row) -&gt; update (a real
 *       SQL {@code UPDATE ... WHERE} inside an explicit read-write
 *       transaction, confirmed the role field changed) -&gt; delete (Mutation
 *       delete) -&gt; read again (confirmed gone) - every step actually run
 *       and its output checked, not assumed.</li>
 *   <li>Torn down immediately after: {@code gcloud spanner instances delete
 *       learning-spanner --quiet} (deleting the instance deletes every
 *       database inside it too - no separate database-delete step needed).
 *       Confirmed via {@code gcloud spanner instances list} returning zero
 *       items. Total instance lifetime: well under 15 minutes.</li>
 * </ul>
 */
package com.ashfaq.gcplab._11_spanner;
