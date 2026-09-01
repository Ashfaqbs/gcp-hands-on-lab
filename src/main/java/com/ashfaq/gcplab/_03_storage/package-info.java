/**
 * Reading order: 03 (comes after _01_iam, _02_identities_bindings).
 *
 * <h2>Cloud Storage = GCP's S3 equivalent</h2>
 * <ul>
 *   <li><b>Bucket</b> - a container for objects. Bucket names are globally
 *       unique across ALL of GCP (not just your project) - like S3, unlike
 *       almost every other GCP resource name. Buckets live in one region
 *       (or multi-region) chosen at creation and cannot be moved later.</li>
 *   <li><b>Object</b> - a file, identified by a key (path-like string)
 *       inside a bucket. No real folders - "folders" in the Console are
 *       just a UI convenience over keys containing "/".</li>
 *   <li><b>Storage class</b> - Standard/Nearline/Coldline/Archive, trading
 *       retrieval cost against storage cost for colder data. We use
 *       Standard throughout - it's the only class inside the Always Free
 *       tier (5 GB-months, US regions only: us-east1, us-west1,
 *       us-central1).</li>
 *   <li><b>Uniform bucket-level access</b> (what we use) vs. fine-grained
 *       per-object ACLs (legacy) - uniform means IAM roles/bindings are the
 *       ONLY access control, applied at the bucket level - simpler, and the
 *       Google-recommended default for anything new.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * Three independent dimensions, all metered separately: (1) storage - $/GB
 * per month, varies by storage class and region (Standard in a US region is
 * inside the Always Free tier's first 5 GB-months); (2) network egress -
 * $/GB for data leaving GCP (free within the same region, free for the
 * first ~1 GB/month total egress to internet across most of GCP); (3)
 * operations - per-10,000-operations pricing, split into Class A (writes/
 * lists - pricier) and Class B (reads - cheaper), with a small free monthly
 * allowance of each. This module's actual spend was $0 - two tiny buckets,
 * a handful of object operations, deleted same-day, well inside the Always
 * Free tier on every dimension. Formula:
 * <pre>
 * monthly_cost = (avg_GB_stored x storage_rate_per_gb)
 *              + (egress_GB x egress_rate_per_gb)
 *              + (classA_ops / 10000 x classA_rate) + (classB_ops / 10000 x classB_rate)
 * </pre>
 * Pricing reference: {@code cloud.google.com/storage/pricing} (has a
 * region/class picker; also linked from the bucket creation wizard in
 * Console).
 *
 * <h2>Ties back to _01_iam / _02_identities_bindings</h2>
 * The backendDeveloper role already includes storage.objects.create/get/
 * list/delete, but deliberately NOT storage.buckets.create/get/list/delete.
 * This mirrors real orgs: infra/platform teams provision buckets, app-level
 * roles only touch objects inside buckets that already exist. So in this
 * module:
 * <ul>
 *   <li>{@code BucketDemo} runs as YOUR OWN credentials (ADC) - bucket
 *       admin is not something backendDeveloper is allowed to do.</li>
 *   <li>{@code ObjectDemo} runs impersonated as backend-dev-sa - proving the
 *       storage.objects.* grant from backendDeveloper actually works for
 *       object operations, same impersonation pattern as
 *       IamPermissionTestDemo in _02_identities_bindings.</li>
 * </ul>
 *
 * <h2>How we created this (2026-08-30, torn down same day)</h2>
 * <ul>
 *   <li>Bucket 1 ({@code ...-learning-bucket}) created entirely via code
 *       ({@code BucketDemo create}) - US-CENTRAL1, Standard class, uniform
 *       bucket-level access enabled.</li>
 *   <li>Bucket 2 ({@code ...-learning-bucket-ui}) created via Console UI
 *       (Cloud Storage -&gt; Buckets -&gt; Create) with the identical settings,
 *       to compare both paths - verified matching via {@code gcloud storage
 *       buckets describe} (region/class/uniform-access all matched).</li>
 *   <li>{@code hello.txt} uploaded/listed/downloaded via {@code ObjectDemo},
 *       impersonating backend-dev-sa - proving backendDeveloper's
 *       storage.objects.* grant (from _01_iam) actually works for real
 *       object operations.</li>
 *   <li>Both buckets were later deleted directly via Console UI (browser,
 *       not code) - confirmed via Cloud Audit Logs
 *       ({@code storage.buckets.delete}, caller user agent = Chrome) when
 *       a routine resource audit found them missing.</li>
 *   <li>Enabled APIs required along the way: {@code
 *       storage.googleapis.com}.</li>
 * </ul>
 *
 * <h2>Internal architecture: what GCS is actually built on</h2>
 * Cloud Storage is not "a filesystem in the cloud" internally - it's a flat
 * key-value object store layered over Google's internal distributed storage
 * stack (historically Colossus, Google's successor to GFS), fronted by a
 * globally-anycast HTTP(S) API:
 * <pre>
 * client (BucketDemo/ObjectDemo) -&gt; storage.googleapis.com (single global
 *   anycast endpoint - your request is routed to the nearest Google edge,
 *   not to a region-specific hostname)
 *   -&gt; metadata layer resolves bucket -&gt; object -&gt; which storage cells
 *      actually hold the bytes (metadata itself lives in a strongly
 *      consistent, globally replicated index - this is WHY GCS offers
 *      strong read-after-write consistency for both metadata AND data,
 *      unlike S3's historical eventual consistency)
 *   -&gt; object bytes are erasure-coded and striped across many physical
 *      disks/machines within the bucket's chosen region(s) - a single disk
 *      or even a whole machine failing loses nothing; multi-region buckets
 *      replicate the erasure-coded shards across geographically separate
 *      regions for disaster tolerance
 *   -&gt; an upload (PutObject/resumable upload) is only acknowledged back to
 *      the client once enough shards are durably written to survive the
 *      bucket's declared durability SLA (11 nines annual durability) - this
 *      is why writes have real latency even though reads can be very fast
 * </pre>
 * "Uniform bucket-level access" (used in this module) means every request's
 * authorization decision is answered purely by IAM policy evaluation (see
 * _01_iam) at the bucket resource - no per-object ACL lookup ever happens,
 * which is both simpler and removes an entire class of "public object
 * despite a private bucket" misconfiguration that legacy ACL mode allows.
 *
 * <h2>System design takeaway</h2>
 * Because GCS is metadata-indexed rather than filesystem-hierarchical, "list
 * objects with prefix X" is a metadata-index range scan, not a directory
 * walk - cheap and fast at any bucket size, but this also means there's no
 * real atomic "rename a folder" operation (renaming N objects sharing a
 * prefix is N separate copy+delete calls under the hood, whatever tool you
 * use). Design object KEYS deliberately: a well-chosen prefix scheme (e.g.
 * date-partitioned keys for a data lake, or a hash prefix to spread very
 * high write-throughput across storage cells) is the single biggest lever
 * over GCS performance/cost at scale, since there is no schema or index to
 * tune the way there is in a database - the key namespace IS the index.
 */
package com.ashfaq.gcplab._03_storage;
