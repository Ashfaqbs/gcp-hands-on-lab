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
 */
package com.ashfaq.gcplab._01_iam;
