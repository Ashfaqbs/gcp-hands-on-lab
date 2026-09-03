/**
 * Reading order: 05 (comes after _01_iam, _02_identities_bindings,
 * _03_storage, _04_cloudsql).
 *
 * <h2>Memorystore for Redis = GCP's managed caching service</h2>
 * "Memorystore" is the product name; Redis is the engine choice underneath
 * it (Memorystore also offers Memcached and, newer, Valkey - a Redis
 * fork). We use Memorystore for Redis, Basic tier (cheapest, no HA/replicas).
 *
 * <h2>Cost</h2>
 * Also a persistent-VM model like Cloud SQL, not usage-metered - billed by
 * the hour for the provisioned capacity regardless of traffic, no free
 * trial or Always Free tier at all for Memorystore (unlike GCS/Firestore).
 * Two components: (1) the Redis capacity itself - $/GB-hour, rate depends
 * on tier (Basic, used here, is cheaper than Standard/HA since it has no
 * replica) and region; (2) the bastion Compute Engine VM used only to reach
 * it from a laptop - a completely separate charge, standard Compute Engine
 * per-vCPU/memory-hour pricing (e2-small here). Formula:
 * <pre>
 * monthly_cost = (redis_capacity_gb x redis_rate_per_gb_hour x hours_running)
 *              + (bastion_vcpu x vm_vcpu_rate + bastion_mem_gb x vm_mem_rate) x hours_running
 * </pre>
 * Both were deleted same-session specifically because there's no free tier
 * to fall back on - every hour either sat running was a real, if small,
 * charge. Pricing reference: {@code cloud.google.com/memorystore/docs/
 * redis/pricing} for Redis capacity, {@code cloud.google.com/compute/
 * vm-instance-pricing} for the bastion VM.
 *
 * <h2>Concept flow: instance -&gt; keyspace -&gt; key -&gt; value</h2>
 * Redis's containment hierarchy is much flatter than an RDBMS - no
 * schema/table layer at all:
 * <pre>
 * Memorystore instance (the Redis server: "learning-redis")
 *   -&gt; logical DB / keyspace   (numbered 0-15 by default, "SELECT 1" etc;
 *                               this module only ever used DB 0)
 *     -&gt; key                   (a unique string name, e.g. "employee:42")
 *       -&gt; value                (string, hash, list, set, or sorted set -
 *                                this module uses simple string values)
 * </pre>
 * There is no enforced structure above the key/value pair itself - "schema"
 * is whatever convention the application imposes on key naming (see
 * {@code databases.md}'s {@code service:entity:id:field} convention) and on
 * what's stored in the value (a raw string here; could equally be JSON, a
 * hash of fields, or a serialized object).
 *
 * <h2>The gotcha: no public IP, ever</h2>
 * Unlike Cloud SQL (reachable from anywhere via the Cloud SQL Java
 * Connector) or Cloud Storage (a public HTTPS API), Memorystore for Redis
 * only ever gets a PRIVATE IP inside one VPC network. There is no
 * equivalent connector library for reaching it from outside GCP. A real
 * deployed app doesn't need a workaround because it's already deployed
 * inside the same VPC (Compute Engine / GKE / Cloud Run+VPC connector) -
 * we, developing from a laptop, are the exception, so we bridge in with:
 *
 * <h2>SSH tunnel through a bastion Compute Engine VM</h2>
 * A minimal VM ({@code redis-bastion}) sits in the same VPC/region as
 * Redis. {@code gcloud compute ssh ... -- -L 6379:REDIS_PRIVATE_IP:6379 -N}
 * opens a local port forward - localhost:6379 on this machine tunnels
 * through SSH to the VM, which can reach Redis directly (same network).
 * The Java client below just connects to localhost:6379 as if Redis were
 * local; it has no idea a tunnel exists. This VM is also this project's
 * first hands-on Compute Engine resource.
 *
 * <h2>CRUD on a cache looks different from CRUD on a DB</h2>
 * No schema, no tables - just key/value pairs (or richer structures: hash,
 * list, set, sorted set - we use simple string keys here) with an optional
 * TTL. {@code CacheCrudDemo} exercises SET/GET/UPDATE(overwrite)/DELETE
 * using Jedis, GCP's recommended Redis client is unopinionated here - any
 * standard Redis Java client works since Memorystore speaks the real Redis
 * protocol, nothing GCP-proprietary about the wire format.
 *
 * <h2>How we created this (2026-08-31)</h2>
 * <ul>
 *   <li>Redis instance via Console UI: Memorystore -&gt; Redis -&gt; Create
 *       Instance - {@code learning-redis}, Basic tier, 1GB, us-central1,
 *       default network. Intended to enable AUTH but it came up with
 *       {@code authEnabled} unset - confirmed via {@code gcloud redis
 *       instances describe ... --format="yaml(authEnabled)"} returning
 *       nothing, so CacheCrudDemo connects without a password (acceptable
 *       for this throwaway instance; a real one should have AUTH on).</li>
 *   <li>Bastion VM via Console UI: Compute Engine -&gt; Create Instance -
 *       {@code redis-bastion}, us-central1-a. Ended up e2-small instead of
 *       the free-tier e2-micro (picked by accident) - left as-is since the
 *       whole exercise runs a few minutes and gets torn down same session,
 *       so the overage is a few cents at most.</li>
 *   <li>SSH tunnel opened via {@code gcloud compute ssh redis-bastion
 *       --zone=us-central1-a -- -L 6379:10.23.23.35:6379 -N}, run in the
 *       background - forwards localhost:6379 through the VM to Redis's
 *       private IP. First connection auto-generated an SSH keypair and
 *       accepted the bastion's host key.</li>
 *   <li>Verified instance state via {@code gcloud redis instances list}
 *       (STATUS: READY) before running any code.</li>
 *   <li>Full CRUD cycle run and confirmed (create -&gt; read -&gt; update
 *       -&gt; read -&gt; delete -&gt; read-returns-empty) against the live
 *       instance through the tunnel.</li>
 *   <li>Torn down same session: {@code gcloud redis instances delete
 *       learning-redis} and {@code gcloud compute instances delete
 *       redis-bastion} (which also kills the SSH tunnel automatically).
 *       Confirmed via {@code gcloud redis instances list} / {@code gcloud
 *       compute instances list} both returning zero items.</li>
 * </ul>
 *
 * <h2>Internal architecture: a real Redis process, VPC-fenced by design</h2>
 * Memorystore is not a proprietary reimplementation - it runs the actual
 * open-source Redis engine on Google-managed infrastructure, which is why
 * any standard Redis client (Jedis here) works with zero GCP-specific code:
 * <pre>
 * CacheCrudDemo -&gt; SSH tunnel (local:6379 -&gt; bastion VM:22 -&gt; Redis
 *   private IP:6379) -&gt; real Redis server process, single-threaded event
 *   loop (Redis's own execution model - one command executed at a time per
 *   shard, which is exactly why individual commands are effectively atomic
 *   with no client-side locking needed) -&gt; in-memory keyspace (DB 0-15)
 * </pre>
 * Because the whole dataset lives in RAM, durability is optional and
 * bolted on rather than fundamental the way it is for Cloud SQL/Firestore:
 * Basic tier (used here) has NO persistence and NO replica at all - an
 * instance restart or the underlying VM failing loses everything, which is
 * exactly the deal a cache is supposed to make (fast, cheap, disposable,
 * backed by a real source of truth elsewhere). Standard/HA tier adds a
 * synchronously-tracked replica in a second zone plus periodic RDB
 * snapshotting to persistent storage, so a failure triggers automatic
 * failover instead of a cold, empty cache. Memorystore is deliberately
 * NEVER reachable outside its VPC (no public IP, no Cloud SQL-Connector-
 * style tunneling library) - Google's own reasoning is that a cache's
 * whole value proposition (sub-millisecond latency) evaporates the moment
 * it's reached over the public internet, so the product doesn't offer that
 * path at all; the bastion-VM SSH tunnel this module used is purely a
 * developer-laptop workaround, never how a real deployed app talks to it.
 *
 * <h2>System design takeaway</h2>
 * A cache-aside pattern (app checks Redis first, falls back to the real DB
 * on a miss, writes the DB then populates/invalidates the cache) only works
 * if the app can tolerate the cache being empty or wrong for a short window
 * - design the TTL and invalidation strategy BEFORE reaching for Redis, not
 * after a stale-data bug shows up in production. Because Redis is
 * single-threaded per shard, a single very large or slow command (an
 * unbounded {@code KEYS *}, a huge {@code SORT}) blocks every other client
 * on that shard for its full duration - the practical system-design rule is
 * "small values, O(1)/O(log n) operations, never a table scan," and reach
 * for Memorystore's cluster mode (sharding across multiple nodes, not used
 * in this module's single Basic-tier instance) only once a single node's
 * memory or single-threaded throughput genuinely becomes the bottleneck.
 *
 * <h2>When to use this service</h2>
 * Reach for Redis/Memorystore when the access pattern is "read this exact
 * key, very often, and it's fine if it's occasionally stale or briefly
 * missing" - session storage, a cache-aside layer in front of a slower
 * source of truth (Cloud SQL/Spanner/an external API), rate-limiting
 * counters, or a leaderboard (sorted sets). Do NOT reach for it as a
 * primary datastore - nothing here has Cloud SQL/Spanner's durability
 * guarantees (Basic tier, used throughout this module, has NO persistence
 * or replica at all - see Internal architecture above), and do not reach
 * for it to pass messages between services (that's Pub/Sub's job, not a
 * key-value store's, even though Redis technically has a pub/sub feature).
 *
 * <h2>Sample usage walkthrough - what {@link CacheCrudDemo} proves</h2>
 * <pre>
 * try (Jedis jedis = new Jedis("localhost", 6379)) {   // through the SSH tunnel, see below
 *     jedis.setex("employee:1:name", 300, "Ashfaq");    // SET with a 300s TTL - always set one
 *
 *     String value = jedis.get("employee:1:name");
 *     long ttlRemaining = jedis.ttl("employee:1:name"); // seconds left, -2 if key doesn't exist
 *
 *     jedis.setex("employee:1:name", 300, "Ashfaq (Senior)"); // "update" = overwrite + reset TTL,
 *                                                              // Redis has no separate UPDATE verb
 *     long removed = jedis.del("employee:1:name");       // returns count actually removed (0 or 1 here)
 * }
 * </pre>
 * The demo deliberately shows only plain string SET/GET - Redis's real
 * power for structured data (a Hash for an object's fields, a Sorted Set
 * for a leaderboard/ranking, a List for a queue, atomic {@code INCR} for
 * counters) is a genuine gap this module doesn't cover; see Production
 * practices below for what those look like and when to reach for each.
 *
 * <h2>Quick reference</h2>
 * <p><b>Auth pattern used here - the one module in this repo with NO GCP
 * IAM auth at all on the data path.</b> Reaching Redis at all requires the
 * SSH tunnel (a Compute Engine / OS Login concern, not an IAM-role-to-
 * Memorystore concern), and once connected, Jedis speaks the raw Redis
 * protocol directly - {@code jedis.auth(password)} would be the call to add
 * if AUTH were enabled (it wasn't for this throwaway instance, see the
 * package-level note on that). This is fundamentally different from every
 * other data-plane demo in this repo (Firestore/Storage/Spanner/BigQuery
 * all authenticate via impersonated backend-dev-sa) - Memorystore's access
 * control is network-boundary + optional Redis-native AUTH, not IAM
 * permissions on the data itself.
 *
 * <p><b>Important classes/methods to know:</b>
 * <ul>
 *   <li>{@code Jedis} - the synchronous client used throughout this module;
 *       {@code JedisPool}/{@code JedisPooled} (NOT used here, same gap as
 *       {@code _04_cloudsql}'s connection-pooling story) is the production-
 *       shaped alternative for a long-running service - a fresh
 *       {@code new Jedis(host, port)} per operation (this module's
 *       per-CLI-invocation pattern) is fine for a one-shot script, wrong
 *       for a service handling many requests.</li>
 *   <li>{@code setex(key, seconds, value)} vs. plain {@code set(key, value)} -
 *       {@code setex} is the TTL-inclusive convenience form used throughout
 *       this module; reaching for plain {@code set} means explicitly
 *       deciding "this key should live forever," which should be a rare,
 *       deliberate choice, not a default.</li>
 *   <li>{@code ttl(key)} - returns seconds remaining, or {@code -1} if the
 *       key exists with NO expiry, or {@code -2} if the key doesn't exist
 *       at all - three genuinely different meanings collapsed into one
 *       return value, worth checking explicitly rather than assuming any
 *       negative number means "missing."</li>
 * </ul>
 *
 * <p><b>Common errors and what they actually mean:</b>
 * <ul>
 *   <li>Connection refused on {@code new Jedis("localhost", 6379)} - the SSH
 *       tunnel isn't open (see "How we created this" below) - Redis itself
 *       being healthy doesn't help if the tunnel process died or was never
 *       started; check the tunnel process first, not Memorystore's own
 *       status.</li>
 *   <li>{@code jedis.get(key)} returns {@code null} sooner than expected -
 *       either the TTL genuinely expired (check with {@code ttl()} on a
 *       fresh write to confirm the expected window), or Basic tier's lack
 *       of persistence meant an instance restart/failover wiped the whole
 *       keyspace (see Internal architecture above - this IS the deal Basic
 *       tier makes, not a bug).</li>
 *   <li>{@code JedisConnectionException} mid-operation - Basic tier has no
 *       replica and no automatic failover (see Internal architecture) - a
 *       node-level hiccup on Google's side genuinely drops the connection
 *       with no fallback; Standard/HA tier's replica exists specifically to
 *       avoid this class of interruption.</li>
 * </ul>
 *
 * <h2>Production practices - what this demo skips that real work needs</h2>
 * <p><b>1. Reach for the right data structure, not just strings.</b> This
 * module's plain SET/GET is the simplest case; real caching workloads
 * usually want: a HASH ({@code jedis.hset("employee:1", Map.of("name",
 * "Ashfaq", "role", "Backend Developer"))}) to cache a whole object as one
 * key without JSON-serializing it yourself; a SORTED SET
 * ({@code jedis.zadd("leaderboard", score, member)}) for rankings/
 * leaderboards with O(log n) insert and O(log n) range queries; atomic
 * {@code INCR}/{@code INCRBY} for counters (rate limiters, view counts) -
 * genuinely atomic even under concurrent access, unlike a GET-then-SET
 * round trip from application code, which has a real race condition.
 * <p><b>2. Connection pooling for a real service, same story as
 * {@code _04_cloudsql}.</b> {@code JedisPool} (or the newer
 * {@code JedisPooled}) should back a long-running service - constructing a
 * new {@code Jedis} instance per operation (this module's per-CLI-
 * invocation pattern, appropriate here) adds real, avoidable per-call
 * connection overhead at request volume.
 * <p><b>3. AUTH and TLS genuinely enabled, not left off.</b> This module's
 * instance came up with AUTH unset by accident (documented honestly above,
 * not smoothed over) - a real deployment should set AUTH explicitly at
 * instance creation and use {@code jedis.auth(password)} (or a connection
 * URI with credentials) before any operation, plus in-transit encryption
 * for anything crossing a network boundary that isn't fully trusted.
 * <p><b>4. Pipelining for bulk operations.</b> Not exercised in this
 * module's single-key demo - {@code Jedis.pipelined()} batches many
 * commands into one round trip instead of one network round trip PER
 * command, the same "batch it" principle {@code _09_ai_commerce_search}'s
 * Bulk API note and {@code _10_elasticsearch}'s bulk-indexing tuning both
 * make for their own services - relevant the moment a cache-warming or
 * bulk-invalidation operation touches more than a handful of keys.
 */
package com.ashfaq.gcplab._05_redis;
