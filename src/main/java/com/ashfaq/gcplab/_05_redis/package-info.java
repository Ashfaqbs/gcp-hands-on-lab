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
 */
package com.ashfaq.gcplab._05_redis;
