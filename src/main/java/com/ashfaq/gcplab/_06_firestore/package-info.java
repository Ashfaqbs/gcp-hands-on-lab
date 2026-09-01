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
 */
package com.ashfaq.gcplab._06_firestore;
