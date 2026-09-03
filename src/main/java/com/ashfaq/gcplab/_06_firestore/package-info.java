/**
 * Reading order: 06 (comes after _01_iam, _02_identities_bindings,
 * _03_storage, _04_cloudsql, _05_redis).
 *
 * <h2>Concept flow: database -&gt; collection -&gt; document -&gt; fields</h2>
 * <pre>
 * Firestore database         (e.g. "learning-native" - a project can have
 *                              multiple, each fully isolated)
 *   -&gt; collection             (e.g. "employees" - no fixed schema, just a
 *                              named bucket of documents)
 *     -&gt; document             (e.g. doc ID "emp-1" - a JSON-like object)
 *       -&gt; fields             (string/number/bool/map/array/reference -
 *                              can differ document to document)
 * </pre>
 * A document can also hold a nested <b>subcollection</b> (a collection
 * scoped under one specific document) - not used in this module's CRUD
 * demo, but the mechanism Firestore uses instead of foreign keys/joins for
 * "child" data that belongs to one parent document.
 *
 * <h2>Firestore = GCP's native document DB, closest thing to MongoDB</h2>
 * <ul>
 *   <li><b>Collection</b> - like a Mongo collection or a SQL table, but no
 *       fixed schema. Holds <b>documents</b>.</li>
 *   <li><b>Document</b> - a JSON-like object (fields can be strings,
 *       numbers, booleans, maps, arrays, references to other documents),
 *       identified by an ID, unique within its collection.</li>
 *   <li>No joins. Firestore is deliberately non-relational - denormalize
 *       data into each document, or issue multiple queries and combine in
 *       code, unlike the single JOIN we'd write in Cloud SQL.</li>
 * </ul>
 *
 * <h2>We picked "Firestore with MongoDB compatibility", not Native mode</h2>
 * At database creation, Console offers Native mode (Firestore's own SDKs)
 * vs. <b>MongoDB compatibility mode</b> - the one we chose. This mode makes
 * Firestore speak the REAL MongoDB wire protocol, so the actual MongoDB
 * Java driver ({@code mongodb-driver-sync}, already on the classpath
 * transitively via spring-boot-starter-data-mongodb) can talk to it,
 * authenticating via MONGODB-OIDC with ENVIRONMENT=gcp.
 *
 * <h2>Why the code here uses the Firestore Native SDK instead</h2>
 * We tried the MongoDB-driver path first and hit a real wall: the driver's
 * built-in "gcp" OIDC environment fetches its token from the GCE metadata
 * server ({@code http://metadata.google.internal/...}), which only exists
 * when code is running ON actual GCP compute (VM/GKE/Cloud Run) - not from
 * an external laptop. It hung for ~2 minutes then failed ("Prematurely
 * reached end of stream"). The real fix would be a custom OIDC callback
 * (impersonate backend-dev-sa, mint an ID token via IAM Credentials API,
 * hand it to the driver manually) PLUS granting that service account a
 * Firestore-specific IAM role it doesn't currently have - doable, but
 * meaningfully more setup than every other module here, so left as a
 * follow-up exercise rather than blocking this module.
 * <p>
 * We first assumed "MongoDB compatibility" was just an ADDITIONAL access
 * path (since {@code type: FIRESTORE_NATIVE} still showed in describe
 * output) and tried the Native SDK against {@code learn-firestore}
 * directly - it failed outright: {@code FAILED_PRECONDITION: Access to
 * this database via the Firestore in Native mode API is disabled}. The
 * describe output actually already said this
 * ({@code firestoreDataAccessMode: DATA_ACCESS_MODE_DISABLED}), we just
 * missed it. Choosing MongoDB compatibility mode is a ONE-WAY, PERMANENT
 * choice per database that disables the Native API entirely - it's not
 * additive.
 * <p>
 * Rather than build the full OIDC-callback + service-account-impersonation
 * fix (a legitimate but heavier path - grant backend-dev-sa a Firestore
 * IAM role, mint an ID token via IAM Credentials API, feed it to the
 * MongoDB driver's OidcCallback), we created a SECOND database,
 * {@code learning-native} (Native mode, us-central1), ran the full CRUD
 * demo against it with plain ADC (no OIDC needed), confirmed it worked,
 * then deleted it - a project's free tier only covers its FIRST database,
 * so this second one was NOT free (small real cost for the handful of
 * test operations) and not worth leaving up. {@code learn-firestore}
 * remained, empty, at $0, as a documented dead end for the Mongo-driver
 * path - a good candidate to revisit later specifically to practice the
 * OIDC-callback + impersonation pattern.
 *
 * <h2>Final cleanup (2026-08-31)</h2>
 * Both databases were deleted: {@code learning-native} (right after CRUD
 * was proven, since it wasn't free-tier), and {@code learn-firestore}
 * (afterward, on request, for a fully clean project - it was $0 anyway but
 * there was no ongoing use for it). Confirmed via
 * {@code gcloud firestore databases list} returning zero items. Unlike
 * _04_cloudsql / _05_redis, neither deletion was cost-driven for
 * learn-firestore specifically - just general hygiene, since an unused
 * resource lying around invites confusion later even at $0.
 *
 * <h2>Correction: the CRUD demo now runs AS backend-dev-sa, not as you</h2>
 * The first pass of {@code EmployeeDocCrudDemo} authenticated with your own
 * ADC identity (plain {@code FirestoreOptions.newBuilder()...}) - it
 * "worked" but wasn't actually simulating an application; it was you,
 * personally, reading/writing. Caught during review: only
 * {@code ObjectDemo} in _03_storage had been impersonating backend-dev-sa;
 * Cloud SQL and Firestore had both quietly taken the shortcut of using the
 * human login instead. Fixed for Firestore since it's fully IAM-based (no
 * excuse not to):
 * <ol>
 *   <li>{@code UpdateRoleDemo} (new, in _01_iam) PATCHed backendDeveloper
 *       to add {@code datastore.entities.get/create/update/delete/list} -
 *       the first time we exercised UPDATE on a role, having previously
 *       only done create/get/delete.</li>
 *   <li>{@code EmployeeDocCrudDemo} now builds its Firestore client with
 *       {@code ImpersonatedCredentials} targeting backend-dev-sa, same
 *       pattern as ObjectDemo - verified by recreating learning-native
 *       (free tier, since it was again the project's only database),
 *       running the full CRUD cycle as the SA (all four operations
 *       succeeded on the first try), then deleting it again.</li>
 * </ol>
 * Cloud SQL's password-based auth was NOT changed - that's a legitimate,
 * separate real-world pattern (DB credentials via Secret Manager), not a
 * shortcut, and its identity-based equivalent (Cloud SQL IAM database
 * authentication) is a deliberate future exercise, not a bug to fix here.
 * Redis has no identity-based data-plane option at all - nothing to fix
 * there either.
 *
 * <h2>Genuinely free-tier, unlike _04_cloudsql / _05_redis</h2>
 * 1 GiB stored data, 50K document reads/day, 20K writes/day, 20K
 * deletes/day, all free forever (not a 30-day trial) - no teardown
 * pressure for this module the way Cloud SQL/Redis needed.
 *
 * <h2>Cost</h2>
 * Genuinely usage-metered, unlike Cloud SQL/Redis's always-on VM billing -
 * you pay only for what you actually read/write/store, per operation, with
 * a real permanent free daily allowance (see above) that a learning
 * workload like this one never came close to exceeding. Beyond the free
 * tier: (1) document reads - $/100K reads, (2) writes - $/100K writes
 * (pricier than reads), (3) deletes - $/100K deletes, (4) stored data -
 * $/GB-month, (5) network egress - same shape as GCS's egress pricing.
 * {@code learning-native}, the second (non-free-tier) database created to
 * unblock the impersonation fix, was NOT actually free - a project's free
 * daily quota only applies to its first database - but the handful of CRUD
 * operations against it cost a fraction of a cent; deleted immediately
 * after regardless, on principle, not because the bill mattered. Formula:
 * <pre>
 * monthly_cost = max(0, reads - free_reads_per_day x 30) / 100000 x read_rate
 *              + max(0, writes - free_writes_per_day x 30) / 100000 x write_rate
 *              + max(0, deletes - free_deletes_per_day x 30) / 100000 x delete_rate
 *              + max(0, avg_gb_stored - 1) x storage_rate_per_gb
 * </pre>
 * Pricing reference: {@code cloud.google.com/firestore/pricing} (has a
 * built-in cost calculator; also states the free-tier daily quotas
 * explicitly, which is where the numbers above come from).
 *
 * <h2>Internal architecture: built on Spanner, globally strongly consistent</h2>
 * Firestore is not a single-machine document store - it's Google's document
 * database layered on the same globally-distributed, synchronously-
 * replicated infrastructure lineage as Cloud Spanner, which is what lets it
 * make an unusual promise for a NoSQL document DB: every read (not just
 * writes) is strongly consistent by default, even across regions, with no
 * "eventual consistency" caveat to design around the way most distributed
 * document stores require:
 * <pre>
 * EmployeeDocCrudDemo -&gt; Firestore client library -&gt; nearest regional
 *   Firestore frontend -&gt; the write is committed via a Paxos/TrueTime-style
 *   consensus protocol across replicas BEFORE the client gets an
 *   acknowledgement - a subsequent read from ANY replica, anywhere, is
 *   guaranteed to see that write, not "usually sees it within X ms"
 * </pre>
 * Two consequences fall directly out of this architecture: (1) Firestore
 * AUTOMATICALLY maintains indexes for every field of every document (single-
 * field indexes are free and automatic; multi-field/composite indexes for
 * compound queries must be explicitly declared, and Firestore will refuse a
 * query that needs an index that doesn't exist yet rather than silently
 * table-scan) - this is the opposite of a SQL DB where indexes are opt-in
 * and a missing one just means a slow query, not a rejected one; (2)
 * real-time listeners (not exercised in this module's CRUD demo, but core
 * to Firestore's design) are implemented as a persistent watch stream from
 * the same consensus layer - a client subscribes to a query and gets pushed
 * every subsequent change, which is why Firestore is the default choice for
 * GCP-backed apps needing live UI updates (chat, collaborative editing,
 * live dashboards) without hand-rolling polling or a separate pub/sub layer.
 *
 * <h2>System design takeaway</h2>
 * Firestore's "no joins" rule (see above) is a direct consequence of this
 * architecture too: cross-document consistency across a join would require
 * the consensus protocol to coordinate across arbitrarily many documents on
 * every read, which doesn't scale - so the system pushes that cost onto the
 * DATA MODEL instead (denormalize what you read together into one document,
 * per the databases.md guidance) rather than the query engine. When
 * designing a Firestore schema, the right question is never "what's the
 * normalized shape of this data" (the SQL instinct) but "what does one
 * screen/API response need to render, and can that live in one document or
 * one flat query" - get that wrong and you end up doing N sequential reads
 * in application code to reassemble what a single SQL JOIN would have done
 * in one round trip.
 *
 * <h2>Production practices - what this demo skips that real work needs</h2>
 * Everything below is a real gap between {@link EmployeeDocCrudDemo} (a
 * correctness demo) and a production Firestore integration - the intent is
 * that none of it should require a search/Slack/StackOverflow detour later.
 *
 * <p><b>1. Hotspotting - the single most common real Firestore production
 * incident.</b> Firestore shards by document key range, similar in spirit
 * to Spanner's splits (see {@code _11_spanner}'s Internal architecture).
 * Writing documents with a MONOTONICALLY INCREASING key - an
 * auto-incrementing counter, or worse, a raw timestamp as the document ID
 * (e.g. logging events keyed by {@code eventTimestamp}) - concentrates every
 * new write on the SAME shard at the SAME moment, hard-capping write
 * throughput on that one hot range regardless of how much the database
 * could theoretically handle overall. This module's fixed {@code "emp-1"}
 * ID never exercises this because it's a single document, but any
 * production collection doing high-volume writes with client-assigned
 * sequential IDs will hit this. Fixes: let Firestore auto-generate the
 * document ID (random, well-distributed by design -
 * {@code collection.document()} with no ID argument), or if a
 * business-meaningful key is required, hash or reverse it before use (e.g.
 * a reversed/hashed timestamp prefix) to scatter writes across the
 * keyspace.
 *
 * <p><b>2. Composite indexes are NOT automatic, and a missing one fails
 * loudly, in production, not at dev time.</b> Firestore auto-indexes every
 * single field, but a query filtering/sorting on MULTIPLE fields together
 * needs an explicit composite index - the SDK throws a
 * {@code FAILED_PRECONDITION} with a direct Console link to create it the
 * first time an un-indexed compound query actually runs. In production this
 * means: define {@code firestore.indexes.json} and deploy it as part of
 * CI/CD ({@code gcloud firestore indexes composite create} or
 * {@code firebase deploy --only firestore:indexes}) BEFORE the query ships,
 * never rely on hitting the error in prod traffic and clicking the Console
 * link reactively - that's a production incident, not a debugging step, the
 * first time it happens under real load. This module's queries are all
 * single-field reads by document ID, so this was never exercised - a real
 * search-team collection (e.g. product-affinity or session data queried by
 * multiple filter fields) will need this from day one.
 *
 * <p><b>3. Transactions have real limits, and contention causes retries, not
 * failures.</b> A single transaction is capped at 500 document writes and
 * has a request-size ceiling; exceeding either fails outright, not
 * partially - batch large writes across multiple transactions/batched
 * writes deliberately. Under write contention (two transactions touching
 * the same document concurrently), Firestore aborts the loser with
 * {@code ABORTED} rather than blocking - the client library retries
 * automatically with backoff by default, but code that catches and swallows
 * exceptions around a transaction can silently turn a transient contention
 * retry into a dropped write; let the SDK's retry do its job and only
 * catch/handle the FINAL failure after retries are exhausted.
 *
 * <p><b>4. TTL policies for auto-expiring data</b> (sessions, carts, OTPs,
 * search-result caches stored in Firestore) - a field marked as the
 * collection's TTL policy (via Console or {@code gcloud firestore fields
 * ttls update}) gets background-deleted by Firestore automatically once
 * past its timestamp, at no per-delete cost beyond the storage already
 * being billed - the correct alternative to a cron job manually scanning
 * and deleting expired documents (which burns read+delete operation
 * quota AND lags behind the real expiry time).
 *
 * <p><b>5. Backups - export/import, not a "snapshot" button.</b>
 * {@code gcloud firestore export gs://bucket/path} triggers a managed,
 * consistent export of the whole database (or specific collections) to
 * GCS, restorable via {@code gcloud firestore import} - schedule this via
 * Cloud Scheduler + a Cloud Function/Cloud Run job for a real backup
 * cadence, since nothing does this automatically by default. Point-in-time
 * recovery (continuous, restore to any second in the last 7 days, not just
 * scheduled export snapshots) is a separate, additionally-billed feature to
 * enable explicitly per database if the RPO of daily/hourly exports isn't
 * tight enough.
 *
 * <p><b>6. Real-time listeners at scale - a resource a client MUST close.</b>
 * Not exercised by this module's plain CRUD demo, but core to why teams
 * reach for Firestore: {@code collection.addSnapshotListener(...)} opens a
 * persistent watch stream (see Internal architecture above) that keeps
 * pushing updates until explicitly removed - a service that opens listeners
 * per-request without closing them (e.g. one per HTTP request in a web
 * backend) leaks open streams and will eventually hit the per-project
 * concurrent-listener ceiling; listeners belong on long-lived
 * connections/sessions (a WebSocket to a browser client, a long-running
 * worker), not spun up and abandoned per short-lived request.
 *
 * <p><b>7. Testing - use the emulator, never real Firestore in CI/unit
 * tests.</b> {@code gcloud emulators firestore start} runs a fully local,
 * zero-cost, zero-network Firestore-compatible server -
 * {@code FirestoreOptions.newBuilder().setEmulatorHost("localhost:8080")}
 * points the same client code at it with no other code changes. Running
 * automated tests against a real GCP project (as every demo in this module
 * does, deliberately, for a LEARNING exercise proving real API behavior) is
 * the wrong pattern for CI - it's slow, costs real (if tiny) money per test
 * run, and pollutes a real project with test data; the emulator exists
 * specifically to make Firestore unit/integration tests fast, free, and
 * hermetic.
 *
 * <p><b>8. Code-level habit this module's demo doesn't need to show:</b> a
 * production service should hold ONE {@code Firestore} client instance for
 * the whole app's lifetime (it's a heavyweight object managing its own
 * gRPC channel pool) - never construct a new
 * {@code FirestoreOptions.newBuilder()...getService()} per request, which
 * this module's {@code main()}-per-CLI-invocation demo does simply because
 * each run IS the whole process lifetime; a real Spring service should
 * build this once as a {@code @Bean} and inject it everywhere, closing it
 * only on application shutdown.
 *
 * <h2>When to use this service</h2>
 * Reach for Firestore when the data is naturally document-shaped (nested
 * objects/arrays, no fixed schema across records) and the access pattern is
 * "read/write one document, or a flat query, at low millisecond latency" -
 * user profiles, product catalogs, session/app state, anything a mobile/web
 * client might sync in real time via a listener. Reach for Cloud SQL/
 * Spanner instead (see {@code _11_spanner}'s RDBMS-flow comparison table)
 * when the data is genuinely relational (needs real joins/multi-table
 * transactions) - Firestore's "no joins" trade (see "Firestore = GCP's
 * native document DB" above) is a deliberate scalability choice, not a
 * missing feature to work around with denormalization tricks past a
 * certain complexity.
 *
 * <h2>Sample usage walkthrough - what {@link EmployeeDocCrudDemo} proves</h2>
 * <pre>
 * Firestore firestore = FirestoreOptions.newBuilder()
 *     .setProjectId(PROJECT_ID)
 *     .setDatabaseId("learning-native")                 // a project can host MULTIPLE databases
 *     .setCredentialsProvider(FixedCredentialsProvider.create(impersonatedCredentials))
 *     .build()
 *     .getService();
 *
 * DocumentReference doc = firestore.collection("employees").document("emp-1");
 *
 * doc.set(Map.of("name", "Ashfaq", "role", "Backend Developer",
 *                 "skills", List.of("Java", "Spring Boot", "GCP"))).get();  // .get() blocks for the write
 *
 * DocumentSnapshot snapshot = doc.get().get();
 * boolean exists = snapshot.exists();
 * Map&lt;String, Object&gt; data = snapshot.getData();
 *
 * doc.update("role", "Senior Backend Developer").get();   // partial update - only this field changes
 * doc.delete().get();
 * </pre>
 * Notice there's no {@code CREATE TABLE}/{@code CREATE SCHEMA} anywhere -
 * the {@code employees} collection is created IMPLICITLY by the first
 * document write, the defining trait of a schemaless document DB (contrast
 * every SQL-shaped module in this repo, where the table/schema must exist
 * before the first row can be written).
 *
 * <h2>Quick reference</h2>
 * <p><b>Auth pattern used here.</b> Impersonated backend-dev-sa - see
 * "Correction: the CRUD demo now runs AS backend-dev-sa, not as you" above
 * for the real mistake this fixed (the first pass used plain ADC, quietly
 * running as a human instead of simulating a real application identity).
 *
 * <p><b>Important classes/methods to know:</b>
 * <ul>
 *   <li>{@code Firestore} - the single client for everything (documents,
 *       collections, transactions, listeners); build once, reuse for the
 *       app's lifetime (see Production practices above).</li>
 *   <li>{@code DocumentReference} vs. {@code DocumentSnapshot} - a
 *       reference is just an ADDRESS (doesn't mean the document exists);
 *       a snapshot is the actual DATA at a point in time, returned by
 *       {@code .get()} - always check {@code snapshot.exists()} before
 *       reading fields, since a reference to a non-existent document is a
 *       completely valid, non-throwing object to hold.</li>
 *   <li>{@code doc.set(...)} (overwrite the whole document) vs.
 *       {@code doc.update(...)} (patch specific fields, fails if the
 *       document doesn't exist) vs. {@code doc.set(..., SetOptions.merge())}
 *       (merge specific fields, CREATES the document if missing) - three
 *       genuinely different semantics easy to reach for the wrong one of;
 *       this module only exercises the first two.</li>
 *   <li>Every write method returns an {@code ApiFuture} - the API is async
 *       by default; {@code .get()} (used throughout this module) blocks
 *       until complete, appropriate for a CLI demo, wrong for a real
 *       service's request-handling thread (see Production practices for
 *       the async-listener alternative).</li>
 * </ul>
 *
 * <p><b>Common errors and what they actually mean:</b>
 * <ul>
 *   <li>{@code PERMISSION_DENIED} on any operation - backendDeveloper needs
 *       {@code datastore.entities.*} (the Firestore IAM permission
 *       namespace is still called "datastore" - see "We picked Firestore
 *       with MongoDB compatibility" above for the historical reason) -
 *       added via {@code _01_iam}'s {@code UpdateRoleDemo}, the first PATCH
 *       this repo's roles ever needed.</li>
 *   <li>{@code FAILED_PRECONDITION: Access to this database via the
 *       Firestore in Native mode API is disabled} - a database created in
 *       MongoDB-compatibility mode rejecting a Native-SDK call - see "We
 *       picked Firestore with MongoDB compatibility" above; this is a
 *       ONE-WAY, PERMANENT choice per database, not a config flag to flip
 *       back.</li>
 *   <li>A composite query throwing {@code FAILED_PRECONDITION} with a
 *       Console link to create an index - not exercised by this module's
 *       single-field lookups, but the single most common real Firestore
 *       error in a growing application - see Production practices' index
 *       deployment note for the CI-safe fix.</li>
 * </ul>
 */
package com.ashfaq.gcplab._06_firestore;
