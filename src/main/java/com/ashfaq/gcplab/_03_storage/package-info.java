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
 *
 * <h2>When to use each piece of this module</h2>
 * Use {@link BucketDemo}'s admin operations (create/configure/delete a
 * bucket) rarely and deliberately - bucket creation is an infra/platform
 * action (see the IAM split below), not something application code does at
 * runtime. Use {@link ObjectDemo}'s direct upload/download when the DATA
 * genuinely needs to pass through your backend (validation, transformation,
 * access-control logic beyond "can read this bucket at all"). Use
 * {@link SignedUrlDemo}'s pattern instead whenever a CLIENT (browser,
 * mobile app, another service with no GCP credentials of its own) needs to
 * upload or download a specific object directly - proxying file bytes
 * through your backend just to relay them to/from GCS wastes your backend's
 * bandwidth and compute for zero benefit once access control has already
 * been decided.
 *
 * <h2>Sample usage walkthrough - each demo class, what it proves</h2>
 * <b>{@link BucketDemo} - admin operations, run as YOUR OWN credentials
 * (see Quick reference for why):</b>
 * <pre>
 * Storage storage = StorageOptions.newBuilder().setProjectId(PROJECT_ID).build().getService();
 *
 * BucketInfo bucketInfo = BucketInfo.newBuilder(BUCKET_NAME)
 *     .setLocation("US-CENTRAL1")                         // Always Free tier region
 *     .setStorageClass(StorageClass.STANDARD)
 *     .setIamConfiguration(BucketInfo.IamConfiguration.newBuilder()
 *         .setIsUniformBucketLevelAccessEnabled(true)      // IAM-only access, no per-object ACLs
 *         .build())
 *     .build();
 * Bucket bucket = storage.create(bucketInfo);
 * </pre>
 * <b>{@link ObjectDemo} - object CRUD, IMPERSONATING backend-dev-sa (proving
 * the storage.objects.* grant works for real data operations):</b>
 * <pre>
 * Blob blob = storage.create(
 *     BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, "hello.txt")).setContentType("text/plain").build(),
 *     content.getBytes(StandardCharsets.UTF_8));
 *
 * Blob downloaded = storage.get(BlobId.of(BUCKET_NAME, "hello.txt"));
 * String content = new String(downloaded.getContent(), StandardCharsets.UTF_8);
 * </pre>
 * <b>{@link SignedUrlDemo} - the pattern real client apps actually use,
 * proven with a real round trip, no GCP credentials on the "client" side:</b>
 * <pre>
 * URI signedUploadUrl = storage.signUrl(blobInfo, 10, TimeUnit.MINUTES,
 *     Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
 *     Storage.SignUrlOption.withV4Signature(),
 *     Storage.SignUrlOption.withContentType()).toURI();
 *
 * // handed to a client with ZERO GCP credentials - a plain PUT is enough
 * HttpClient.newHttpClient().send(
 *     HttpRequest.newBuilder(signedUploadUrl)
 *         .header("Content-Type", "text/plain")
 *         .PUT(HttpRequest.BodyPublishers.ofString(content))
 *         .build(),
 *     HttpResponse.BodyHandlers.ofString());
 * </pre>
 * Verified live: a signed PUT URL generated via impersonated credentials
 * (no downloaded JSON key anywhere) accepted a plain, credential-free HTTP
 * PUT with HTTP 200, and a signed GET URL for the same object returned the
 * exact uploaded content back byte-for-byte on a separate request. Signing
 * itself never touches a local private key - {@code Storage.signUrl(...)}
 * detects the credential is {@code ImpersonatedCredentials} and transparently
 * calls the IAM Credentials API's {@code signBlob} RPC instead, the same
 * "no exportable secret" property every impersonation-based demo in this
 * repo relies on.
 *
 * <h2>Quick reference</h2>
 * <p><b>Auth pattern used here, and why it splits across two identities.</b>
 * {@code BucketDemo} runs as your own ADC user (bucket admin is
 * intentionally NOT in backendDeveloper's permission set - see "Ties back to
 * _01_iam / _02_identities_bindings" above). {@code ObjectDemo} and
 * {@code SignedUrlDemo} both impersonate backend-dev-sa, because both are
 * proving the APPLICATION identity's object-level access, not your own.
 *
 * <p><b>Important classes/methods to know:</b>
 * <ul>
 *   <li>{@code StorageOptions.getDefaultInstance()}/{@code newBuilder()} -
 *       the client builder; {@code Storage} itself is the single entry
 *       point for every bucket AND object operation - unlike
 *       {@code _09_ai_commerce_search}'s split of
 *       {@code ProductServiceClient}/{@code SearchServiceClient}, GCS has
 *       one client class for everything.</li>
 *   <li>{@code BlobId} vs. {@code BlobInfo} - {@code BlobId} is just the
 *       (bucket, key, generation) address; {@code BlobInfo} is the address
 *       PLUS metadata (content type, custom metadata, storage class
 *       override). Methods that only need to locate an object take
 *       {@code BlobId}; methods that create/update one take
 *       {@code BlobInfo}.</li>
 *   <li>{@code Storage.signUrl(BlobInfo, duration, TimeUnit, SignUrlOption...)} -
 *       the signed-URL entry point; {@code SignUrlOption.withV4Signature()}
 *       should always be passed explicitly (V4 is current; the default
 *       signing version without it is the older, discouraged V2 scheme).</li>
 *   <li>{@code Bucket.IamConfiguration.setIsUniformBucketLevelAccessEnabled(true)} -
 *       the setting that makes every access decision go through IAM alone
 *       (see Internal architecture above) - the default this module always
 *       sets explicitly rather than relying on GCS's own default.</li>
 * </ul>
 *
 * <p><b>Common errors and what they actually mean:</b>
 * <ul>
 *   <li>{@code 403 Forbidden} on a signed URL that LOOKS correctly formed -
 *       almost always signed with the wrong HTTP method, wrong
 *       content-type (a PUT signed for {@code text/plain} rejects a client
 *       sending {@code application/json}), or already expired - signed URL
 *       validation is strict about matching exactly what was signed, not
 *       just "is this request generally authorized."</li>
 *   <li>{@code PERMISSION_DENIED} calling {@code signUrl} itself (not the
 *       resulting URL - the SIGNING call) - the impersonation chain needs
 *       {@code roles/iam.serviceAccountTokenCreator} on the target SA
 *       (same grant every impersonation demo in this repo depends on,
 *       already set up in {@code _02_identities_bindings}) since signing
 *       via {@code ImpersonatedCredentials} routes through the IAM
 *       Credentials API's {@code signBlob}, bundled in that same
 *       predefined role.</li>
 *   <li>{@code 409 Conflict} on bucket create - bucket names are GLOBALLY
 *       unique across every GCP project on Earth, not just yours; a
 *       collision with someone else's bucket name is a real, if rare,
 *       possibility, which is why this module prefixes the bucket name with
 *       the full project ID.</li>
 * </ul>
 *
 * <h2>Production practices - what this demo skips that real work needs</h2>
 * <p><b>1. Signed URLs need a short expiry and a narrow scope, always.</b>
 * This module's 10-minute expiry is reasonable for a one-off client
 * upload/download flow - never generate a signed URL with a long expiry
 * "to be safe," since anyone who obtains the URL (a leaked log line, a
 * browser history entry, a referrer header) can use it for its full
 * validity window with no further authentication at all. Scope each URL to
 * exactly the object and HTTP method needed - never a bucket-wide signed
 * URL when a client only needs one file.
 * <p><b>2. Lifecycle rules for anything that isn't meant to live forever.</b>
 * Not exercised in this module's short-lived demo objects, but a real
 * bucket holding uploads (user avatars, temporary exports, staged imports)
 * should have an Object Lifecycle Management rule (auto-delete after N
 * days, or auto-transition to a colder/cheaper storage class) configured on
 * the bucket - the durable, no-code alternative to a cron job that lists
 * and deletes old objects by hand.
 * <p><b>3. CORS configuration for browser-originated signed-URL uploads.</b>
 * A signed URL called from a browser's JavaScript (rather than this
 * module's server-side {@code HttpClient} proof) needs the bucket's CORS
 * configuration to explicitly allow the calling origin and the PUT/GET
 * method - otherwise the browser blocks the response even though the
 * signed URL itself is valid and the server-side request would have
 * succeeded. A common first-time confusion: the request appears to work in
 * a raw {@code curl} test (this module's proof) but fails silently in an
 * actual browser until CORS is configured.
 * <p><b>4. Object versioning for anything where accidental overwrite/delete
 * is a real risk.</b> Enabling Object Versioning on a bucket keeps prior
 * versions of an object recoverable after an overwrite or delete - not
 * needed for this module's disposable demo objects, but the right default
 * for any bucket holding data that would be genuinely costly to lose to a
 * bug or a mistaken signed-URL grant.
 */
package com.ashfaq.gcplab._03_storage;
