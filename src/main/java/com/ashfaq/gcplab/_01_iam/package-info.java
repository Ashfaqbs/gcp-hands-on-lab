/**
 * Reading order: 01 of the IAM learning track (see _02_identities_bindings next).
 *
 * Custom IAM roles: defining a permission bundle (a role) and verifying it
 * via the IAM Admin API. A role by itself grants nothing - it's just a
 * named list of permissions sitting unattached. See _02_identities_bindings
 * for the step that actually connects a role to an identity on a resource.
 *
 * <h2>How we created this (2026-08-28/29)</h2>
 * <ul>
 *   <li>Console UI - IAM &amp; Admin -&gt; Roles -&gt; Create Role, for each of:
 *       {@code backendDeveloper} (cloudsql.instances.get/connect,
 *       storage.objects.create/get/list/delete, logging.logEntries.list),
 *       {@code dataMlEngineer} (aiplatform.endpoints.predict,
 *       storage.objects.get/list, bigquery.tables.getData),
 *       {@code siteReliabilityEngineer} (container.pods.get/list,
 *       compute.instances.get/reset, monitoring.timeSeries.list,
 *       logging.logEntries.list), {@code securityAdmin}
 *       (resourcemanager.projects.getIamPolicy/setIamPolicy,
 *       iam.roles.get/list).</li>
 *   <li>{@code billingViewer} was attempted the same way but
 *       {@code billing.accounts.get} showed "Non-applicable" - billing
 *       accounts are a separate resource tree from projects (see
 *       docs/gcp-hierarchy.md), so that permission can't attach to a
 *       project-scoped custom role. Skipped; real billing-viewer access
 *       would use the predefined {@code roles/billing.viewer} bound
 *       directly on the billing account instead.</li>
 *   <li>{@code IamRoleLifecycleDemo} then created/verified/deleted a
 *       throwaway {@code dummyTestRole} entirely via code (IAM Admin API),
 *       to prove every Console action here has a code equivalent.</li>
 *   <li>Enabled APIs required along the way:
 *       {@code gcloud services enable iam.googleapis.com}.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * $0, always. IAM (roles, service accounts, policy bindings, the IAM Admin
 * API) is a free control-plane feature of every GCP project - you're only
 * ever charged for the resources a role's permissions let you touch, never
 * for the role/permission bookkeeping itself. Pricing reference for
 * completeness: {@code cloud.google.com/iam/pricing} (a one-line page
 * confirming "no charge").
 *
 * <h2>Role UPDATE (2026-08-31, added when wiring up _06_firestore)</h2>
 * {@code IamRoleLifecycleDemo} only ever exercised create/get/delete (on a
 * throwaway role). The fourth CRUD verb - update - was missing until
 * {@code UpdateRoleDemo} PATCHed the REAL backendDeveloper role, adding
 * {@code datastore.entities.get/create/update/delete/list} so
 * backend-dev-sa could be used for real (impersonated) Firestore access
 * instead of quietly falling back to a human login - see
 * _06_firestore's package-info for the full story. Pattern: GET current
 * role -&gt; merge new permissions into the existing set (not overwrite) -&gt;
 * PATCH with {@code updateMask=includedPermissions} so only that field
 * changes.
 *
 * <h2>Internal architecture: how IAM actually evaluates "can this call happen?"</h2>
 * IAM is not a per-service feature bolted onto each API - it's a single
 * shared control-plane service ({@code iam.googleapis.com} for role/identity
 * bookkeeping, {@code cloudresourcemanager.googleapis.com} for policy
 * bindings) that every other GCP API calls into on the hot path of every
 * single request:
 * <pre>
 * caller -&gt; API frontend (e.g. storage.googleapis.com)
 *        -&gt; authenticate the caller (verify token: OAuth access token,
 *           short-lived impersonated token, or attached-identity metadata
 *           token - see _02)
 *        -&gt; ask the shared IAM policy-evaluation layer: "does this
 *           principal have permission X on this resource?"
 *        -&gt; IAM walks the RESOURCE HIERARCHY bottom-up (resource -&gt;
 *           project -&gt; folder(s) -&gt; organization), unioning every policy
 *           binding at every level (bindings are additive-only - there is
 *           no "revoke" binding, only removing a grant or, at the org
 *           level, an explicit IAM Deny policy which is evaluated first
 *           and always wins over any Allow)
 *        -&gt; permission found in the union of (role -&gt; permissions) across
 *           any binding that matches this principal? -&gt; ALLOW, else DENY
 *        -&gt; only if ALLOW does the API frontend actually run the requested
 *           operation
 * </pre>
 * A role (what _01_iam builds) is purely a named permission SET stored in
 * IAM's own metadata store - it is never consulted directly by another
 * service; only the policy-evaluation step above, at call time, dereferences
 * a binding's role into its permission list. This is why a role change
 * (like the {@code UpdateRoleDemo} PATCH above) affects every existing
 * binding to that role immediately - the permission list is looked up live,
 * not copied into the binding.
 *
 * <h2>System design takeaway</h2>
 * Treat IAM as a distributed, globally-consistent-ish but NOT
 * instant-propagation authorization cache in front of every API: changes
 * typically apply within seconds but Google documents up to ~7 minutes for
 * full global propagation, which matters for anything that grants-then-
 * immediately-calls (a retry-with-backoff on the first post-grant call is a
 * legitimate defensive pattern, not a bug workaround). Because policy
 * evaluation unions bindings up the whole resource hierarchy, the cheapest
 * mental model for "why can/can't this identity do X" is: list every
 * binding at the resource, its project, every folder above it, and the org
 * - the effective permission set is all of those roles' permissions
 * combined, not just what's bound directly on the resource in front of you.
 *
 * <h2>When to use custom roles (vs. predefined roles)</h2>
 * Default to a PREDEFINED role ({@code roles/storage.objectViewer},
 * {@code roles/cloudsql.client}, etc.) unless one genuinely doesn't exist at
 * the right granularity - Google maintains hundreds of these, they're kept
 * up to date as APIs evolve, and they don't need YOU to remember to add a
 * permission when a service gains a new capability. Reach for a CUSTOM role
 * (what this whole module builds) specifically when: (1) the least-
 * privilege set you need doesn't match any predefined role (this repo's
 * {@code backendDeveloper} - a narrow slice of Cloud SQL + Storage + Logging,
 * with none of the broader admin permissions {@code roles/cloudsql.client}
 * alone would still leave too broad for), or (2) you're deliberately
 * modeling job-function boundaries across a team ({@code dataMlEngineer},
 * {@code siteReliabilityEngineer}, {@code securityAdmin} here) rather than
 * per-service roles. Custom roles are real maintenance burden - they don't
 * auto-update when a service adds permissions - so the trade is deliberate
 * narrowness in exchange for owning the upkeep.
 *
 * <h2>Sample usage walkthrough - each demo class, what it proves</h2>
 * <b>{@link IamRoleLifecycleDemo} - full CRUD on a throwaway role, via the
 * OLDER Discovery-based client style (not the newer Cloud Client Library
 * pattern every other module in this repo uses - see Quick reference
 * below):</b>
 * <pre>
 * Iam iam = new Iam.Builder(
 *         GoogleNetHttpTransport.newTrustedTransport(),
 *         GsonFactory.getDefaultInstance(),
 *         new HttpCredentialsAdapter(GoogleCredentials.getApplicationDefault()
 *                 .createScoped("https://www.googleapis.com/auth/cloud-platform")))
 *     .setApplicationName("gcp-learning-lab")
 *     .build();
 *
 * Role roleDefinition = new Role()
 *     .setTitle("Dummy Test Role")
 *     .setIncludedPermissions(List.of("logging.logEntries.list"))
 *     .setStage("GA");
 * Role created = iam.projects().roles()
 *     .create("projects/" + PROJECT_ID, new CreateRoleRequest()
 *         .setRoleId("dummyTestRole").setRole(roleDefinition))
 *     .execute();
 *
 * // ... later ...
 * iam.projects().roles().delete(ROLE_NAME).execute();
 * // Custom roles are SOFT-DELETED - the name stays reserved and undeletable-
 * // recoverable for ~7 days before permanent purge (see Quick reference's
 * // common-errors entry on this - it's a real gotcha, not a footnote).
 * </pre>
 * <b>{@link IamRoleVerifier} - the "did my roles drift from what I expect"
 * check, the pattern real teams run in CI:</b>
 * <pre>
 * Map&lt;String, Set&lt;String&gt;&gt; EXPECTED_ROLES = Map.of(
 *     "backendDeveloper", Set.of("cloudsql.instances.get", "storage.objects.create", ...));
 *
 * for (var entry : EXPECTED_ROLES.entrySet()) {
 *     Role role = iam.projects().roles().get(roleName(entry.getKey())).execute();
 *     Set&lt;String&gt; actual = new TreeSet&lt;&gt;(role.getIncludedPermissions());
 *     Set&lt;String&gt; missing = new TreeSet&lt;&gt;(entry.getValue());
 *     missing.removeAll(actual);           // expected but not present -> under-permissioned
 *     Set&lt;String&gt; unexpected = new TreeSet&lt;&gt;(actual);
 *     unexpected.removeAll(entry.getValue()); // present but not expected -> permission creep
 * }
 * </pre>
 * This diff-based pattern - not just "does the role exist" - is what
 * catches both accidental under-permissioning (a deploy that forgot to grant
 * a needed permission) and permission creep (someone manually added a
 * broad permission "just to unblock" and never removed it) - exactly the
 * two failure modes a real least-privilege posture erodes from over time.
 * <p>
 * <b>{@link UpdateRoleDemo} - the PATCH pattern, not overwrite:</b>
 * <pre>
 * Role current = iam.projects().roles().get(ROLE_NAME).execute();
 * Set&lt;String&gt; updated = new LinkedHashSet&lt;&gt;(current.getIncludedPermissions());
 * updated.addAll(FIRESTORE_PERMISSIONS);           // merge, don't replace
 *
 * iam.projects().roles()
 *     .patch(ROLE_NAME, new Role().setIncludedPermissions(new ArrayList&lt;&gt;(updated)))
 *     .setUpdateMask("includedPermissions")         // ONLY this field changes
 *     .execute();
 * </pre>
 * Skipping the GET-then-merge step and just PATCHing a hand-typed permission
 * list is the real-world mistake this demo avoids - it would silently DROP
 * every permission not in that hand-typed list, a genuine production
 * incident (an app that was working now gets PERMISSION_DENIED) rather than
 * an obviously-broken deploy.
 *
 * <h2>Quick reference</h2>
 * <p><b>Auth pattern used here:</b> plain ADC
 * ({@code GoogleCredentials.getApplicationDefault()}), scoped to
 * {@code cloud-platform} - never impersonated backend-dev-sa, deliberately,
 * because managing IAM itself (creating roles, granting permissions) is a
 * platform/security-admin action, not something an application's own
 * service-account identity should ever be able to do to itself (a
 * compromised app identity that could also rewrite IAM policy is a much
 * worse blast radius than one that can only touch its granted resources).
 *
 * <p><b>Client construction - the OLD pattern, worth recognizing.</b> This
 * package builds its {@code Iam} client via
 * {@code new Iam.Builder(HttpTransport, JsonFactory, HttpCredentialsAdapter)}
 * - the older, Discovery-document-based Google API client style. Every
 * other module in this repo ({@code _03_storage}'s {@code StorageOptions},
 * {@code _06_firestore}'s {@code FirestoreOptions}, etc.) uses the newer
 * Cloud Client Library pattern (a {@code *Options}/{@code *Settings}
 * builder, no manual transport/JSON-factory wiring). Both are genuinely
 * Google-maintained and both work - the IAM Admin API and the Resource
 * Manager API (used by {@code _02_identities_bindings}'s
 * {@code ProjectsClient} for policy bindings) simply ship different-
 * generation client libraries; recognizing the Discovery-style
 * boilerplate ({@code GoogleNetHttpTransport}, {@code GsonFactory},
 * {@code HttpCredentialsAdapter}) is useful precisely because it still
 * shows up in older or less-central GCP APIs.
 *
 * <p><b>Important classes/methods to know:</b>
 * <ul>
 *   <li>{@code Iam.Builder} - constructs the client; needs transport + JSON
 *       factory + credentials adapter, unlike newer clients' one-line
 *       {@code Options.getDefaultInstance().getService()}.</li>
 *   <li>{@code iam.projects().roles().create/get/patch/delete/list/undelete()} -
 *       the full role CRUD surface; {@code undelete()} exists specifically
 *       for the ~7-day soft-delete grace window mentioned above, and this
 *       module never calls it - worth knowing it's there before assuming a
 *       soft-deleted role is unrecoverable.</li>
 *   <li>{@code Role.setStage(...)} - {@code GA}/{@code BETA}/{@code ALPHA}/
 *       {@code DISABLED}/{@code DEPRECATED} - controls whether a role can
 *       even be bound; a role stuck at {@code DISABLED} silently grants
 *       nothing to anything already bound to it, a real "why doesn't this
 *       work" trap if stage is set without meaning to.</li>
 * </ul>
 *
 * <p><b>Common errors and what they actually mean:</b>
 * <ul>
 *   <li>{@code ALREADY_EXISTS} on {@code CreateRole} for a role ID you just
 *       deleted - the soft-delete grace window (~7 days) reserves the name;
 *       either wait it out, {@code undelete()} it, or pick a different role
 *       ID - deleting and immediately recreating with the same ID does NOT
 *       work the way a normal resource delete-then-recreate would.</li>
 *   <li>{@code PERMISSION_DENIED} on any {@code iam.projects().roles()}
 *       call - the caller needs {@code iam.roles.create}/{@code get}/
 *       {@code update}/{@code delete} (bundled in {@code roles/iam.roleAdmin}
 *       or broader) - note this is a DIFFERENT permission family from
 *       {@code resourcemanager.projects.setIamPolicy} (needed for policy
 *       BINDINGS in {@code _02_identities_bindings}) - having one does not
 *       imply the other.</li>
 *   <li>A role's permission list silently missing something you PATCHed in -
 *       almost always a forgotten {@code setUpdateMask(...)} call, or a
 *       mask that doesn't include the field you changed - the API applies
 *       ONLY the masked fields from the patch body, silently ignoring
 *       everything else even if it's present in the request object.</li>
 * </ul>
 *
 * <h2>Production practices - what this demo skips that real work needs</h2>
 * <p><b>1. Manage roles as code (Terraform), not imperative API calls, at
 * team scale.</b> This module's classes prove the API works and are useful
 * for one-off verification/drift-checking ({@code IamRoleVerifier}'s
 * pattern specifically) - but a real team's SOURCE OF TRUTH for role
 * definitions should be a {@code google_project_iam_custom_role} Terraform
 * resource, reviewed via PR like any other infrastructure change, not a
 * Java class someone runs by hand. {@code IamRoleVerifier}'s drift-check
 * pattern still has real value even with Terraform-managed roles - it
 * catches manual out-of-band changes Terraform's own state doesn't know
 * about.
 * <p><b>2. Avoid role sprawl.</b> Four roles for four job functions (this
 * module's shape) is a reasonable, legible size - a real org's IAM tends to
 * decay toward dozens of overlapping custom roles nobody remembers the
 * purpose of. {@code IamRoleVerifier}'s expected-permissions map is also,
 * incidentally, living documentation of intent - worth keeping even purely
 * for that.
 * <p><b>3. Understand the soft-delete window before it surprises a CI
 * pipeline.</b> A pipeline that deletes and recreates a role by the same ID
 * on every run (common in naive test setups) will fail on the second run
 * with {@code ALREADY_EXISTS} - the fix is either {@code undelete()} inside
 * the ~7-day window, or designing tests to use a fresh, uniquely-suffixed
 * role ID per run instead of reusing one fixed name.
 */
package com.ashfaq.gcplab._01_iam;
