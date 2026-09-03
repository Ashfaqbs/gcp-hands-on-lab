/**
 * Reading order: 02 of the IAM learning track (comes after _01_iam).
 *
 * <h2>Two distinct steps that turn a role definition into real access</h2>
 * <ol>
 *   <li>Service accounts (IAM Admin API, {@link ServiceAccountDemo}) - a
 *       non-human identity, the way applications/CI/VMs authenticate
 *       instead of a personal Google login. Created/deleted the same way
 *       custom roles are in _01_iam, but a completely different resource
 *       type - creating a role does not create any identity.</li>
 *   <li>IAM policy bindings (Resource Manager API, NOT IAM Admin API,
 *       {@link IamBindingDemo}) - the actual "grant access" step. A binding
 *       lives on the RESOURCE being protected (here: the project), not on
 *       the role or the identity, which is why a different API/client is
 *       used. Bindings are edited with a read-modify-write pattern
 *       (getIamPolicy -&gt; mutate -&gt; setIamPolicy) guarded by an etag
 *       for optimistic concurrency.</li>
 * </ol>
 *
 * <h2>Human identity vs. service account</h2>
 * <table border="1">
 *   <tr><th></th><th>Human (e.g. YOUR_GOOGLE_ACCOUNT@gmail.com)</th><th>Service account (e.g. backend-dev-sa)</th></tr>
 *   <tr><td>Represents</td><td>An actual person</td><td>A non-human "robot" identity</td></tr>
 *   <tr><td>Used for</td><td>Console clicks, gcloud CLI, personal debugging</td><td>Apps, CI/CD, servers - no human present</td></tr>
 *   <tr><td>Auth</td><td>Interactive OAuth browser login</td><td>Key file, impersonation, or attached identity - see below</td></tr>
 * </table>
 * A role by itself (see _01_iam) grants nothing - it's an unassigned job
 * description. The pattern seen in this project: broad role (roles/owner)
 * on the human for debugging, narrow custom role (backendDeveloper) on the
 * service account for the actual deployed app - so a compromised app can't
 * do more than its narrow role allows, even though the engineer who wrote
 * it has broader personal access.
 *
 * <h2>A bound role is inert until code authenticates AS that identity</h2>
 * Binding a role to a service account does not let code "become" that
 * identity automatically. Something must explicitly tell GCP "run this
 * process as this service account":
 * <ol>
 *   <li><b>JSON key file</b> - {@code GOOGLE_APPLICATION_CREDENTIALS=path.json}.
 *       Works anywhere, but is a long-lived static secret - a standing
 *       compromise if leaked. Deliberately explored last in this repo.</li>
 *   <li><b>Impersonation</b> - see {@link IamPermissionTestDemo}. A caller
 *       holding {@code roles/iam.serviceAccountTokenCreator} on the target
 *       SA requests short-lived tokens "as" it. Good for local dev/testing.</li>
 *   <li><b>Attached identity (production)</b> - code running ON GCP infra
 *       (Compute Engine, GKE, Cloud Run) has the SA attached at deploy
 *       time; the metadata server hands out tokens automatically, same ADC
 *       call as local dev, zero code changes.</li>
 * </ol>
 * Full chain, all four links required: role (permissions) -&gt; bound to
 * -&gt; service account (identity) -&gt; code explicitly authenticates as
 * (one of the 3 methods above) -&gt; permissions actually exercised. Any
 * link missing and nothing happens - which is why dataMlEngineer,
 * siteReliabilityEngineer and securityAdmin currently do nothing: no
 * identity is bound to them yet.
 *
 * <h2>Proving the boundary, not just trusting it</h2>
 * {@link IamPermissionTestDemo} impersonates backend-dev-sa and calls
 * testIamPermissions to check, as that identity: which permissions it
 * genuinely has (e.g. storage.objects.get - granted) vs. does not (e.g.
 * compute.instances.delete - never granted to backendDeveloper). This is
 * the difference between "I configured access" and "I proved access is
 * what I think it is."
 *
 * <h2>Cost</h2>
 * $0. Service accounts, IAM policy bindings, and token generation
 * (impersonation, key files, ADC) are all free - same as custom roles in
 * _01_iam, GCP does not charge for identity/access-control bookkeeping,
 * only for the underlying resources those identities are then used to
 * touch. Reference: {@code cloud.google.com/iam/pricing}.
 *
 * <h2>How we created this (2026-08-29/31)</h2>
 * <ul>
 *   <li>First created backend-dev-sa entirely via code
 *       ({@code ServiceAccountDemo create}), bound it to backendDeveloper
 *       via code ({@code IamBindingDemo bind}), granted the caller
 *       {@code roles/iam.serviceAccountTokenCreator} on it via
 *       {@code gcloud iam service-accounts add-iam-policy-binding} (needed
 *       for {@link IamPermissionTestDemo} to impersonate it), then verified
 *       the permission boundary, then deleted it end-to-end (unbind ->
 *       delete) to confirm the teardown path too.</li>
 *   <li>Recreated backend-dev-sa a second time via Console UI (Service
 *       Accounts -&gt; Create Service Account), granting it backendDeveloper
 *       directly in step 2 of the wizard ("Grant this service account
 *       access to project") instead of a separate bind call - the UI
 *       collapses create+bind into one flow where the API needs two calls.
 *       Verified identical via {@code ServiceAccountDemo get} / {@code
 *       IamBindingDemo list}.</li>
 *   <li>Re-granted {@code roles/iam.serviceAccountTokenCreator} on the new
 *       SA (the old grant died with the old SA - it's a different resource
 *       even with the same email).</li>
 *   <li>Enabled APIs required along the way: {@code
 *       cloudresourcemanager.googleapis.com}, {@code
 *       iamcredentials.googleapis.com} (the latter specifically for
 *       impersonation's {@code generateAccessToken} call).</li>
 * </ul>
 *
 * <h2>Internal architecture: what impersonation actually does on the wire</h2>
 * {@link IamPermissionTestDemo} never holds backend-dev-sa's key material -
 * nothing like that exists for impersonation. Instead:
 * <pre>
 * caller's own ADC credential (human login or another SA)
 *   -&gt; IAM Credentials API: generateAccessToken(target=backend-dev-sa)
 *      [requires roles/iam.serviceAccountTokenCreator ON the target SA]
 *   -&gt; STS-style token exchange happens server-side inside Google's
 *      identity infrastructure - the caller's identity is verified, then
 *      checked against the target SA's IAM policy for TokenCreator, then a
 *      brand-new SHORT-LIVED (default 1hr) OAuth access token is minted
 *      that is indistinguishable, to any downstream API, from a token the
 *      SA generated for itself
 *   -&gt; that token is attached to every subsequent call (e.g. testIamPermissions)
 *      -&gt; the downstream API's IAM check (see _01_iam's evaluation flow)
 *         runs against backend-dev-sa's identity and bindings, NOT the
 *         original caller's
 * </pre>
 * This is exactly the same mechanism GCP's own services use internally for
 * attached identity (Compute Engine's metadata server is, under the hood,
 * a local proxy that calls this same token-minting machinery on the VM's
 * behalf) - impersonation, key files, and attached identity all converge on
 * "produce a valid OAuth access token for the target SA," they just differ
 * in HOW that token gets minted and how long-lived the underlying secret is.
 *
 * <h2>System design takeaway</h2>
 * The three auth methods form a spectrum of blast radius, not just
 * convenience: a leaked JSON key is a standing credential valid until
 * manually revoked (worst blast radius, best portability); impersonation
 * tokens expire in ~1 hour and require the impersonator to already hold a
 * TokenCreator grant, so a leak is bounded by both time and a pre-existing
 * trust relationship; attached identity has no exportable secret at all -
 * the token only ever exists in the metadata server's response and a
 * process's memory, which is why it's the only one of the three considered
 * safe for production without extra controls. Designing a system's identity
 * story is really designing where on this spectrum each component sits:
 * production workloads on GCP compute should always land on attached
 * identity; local dev/CI that isn't itself GCP infra should land on
 * impersonation; JSON keys should be the last resort, for the rare
 * external/non-GCP caller that has no other option, with rotation and
 * expiry policy planned in from day one.
 *
 * <h2>When to use each piece of this module</h2>
 * Create a NEW service account per workload/service, not one shared
 * "backend" identity for everything - the whole point of narrow custom
 * roles ({@code _01_iam}) is defeated if every app shares one identity with
 * the union of every app's permissions. Reach for a project-level IAM
 * BINDING ({@code IamBindingDemo}'s job) when the access genuinely applies
 * project-wide; reach for a resource-level binding instead (not
 * demonstrated in this module, but the same {@code getIamPolicy}/
 * {@code setIamPolicy} shape on a bucket/dataset/instance directly) when
 * access should be scoped to one specific resource, not everything of that
 * type in the project. Reach for {@code IamPermissionTestDemo}'s
 * impersonate-and-verify pattern any time "I configured this correctly" is
 * about to be trusted without being checked - a five-line verification
 * beats a production incident discovered by a user.
 *
 * <h2>Sample usage walkthrough - each demo class, what it proves</h2>
 * <b>{@link ServiceAccountDemo} - create/get/delete, and a reusable
 * package-visible constant other classes in this package build on:</b>
 * <pre>
 * static final String ACCOUNT_EMAIL = ACCOUNT_ID + "@" + PROJECT_ID + ".iam.gserviceaccount.com";
 *
 * ServiceAccount created = iam.projects().serviceAccounts()
 *     .create("projects/" + PROJECT_ID, new CreateServiceAccountRequest()
 *         .setAccountId("backend-dev-sa")
 *         .setServiceAccount(new ServiceAccount().setDisplayName("Backend Developer SA")))
 *     .execute();
 * </pre>
 * Note {@code ACCOUNT_EMAIL} is package-private ({@code static final}, no
 * modifier) rather than duplicated as a string literal in every other class
 * in this package - {@link IamBindingDemo} and {@link IamPermissionTestDemo}
 * both reference {@code ServiceAccountDemo.ACCOUNT_EMAIL} directly. A small,
 * deliberate example of controlled coupling WITHIN a package (fine) versus
 * across packages (each module in this repo instead re-declares its own
 * {@code PROJECT_ID}/{@code SERVICE_ACCOUNT_EMAIL} constants rather than
 * importing another package's - see the Quick reference in later modules'
 * "reuse the client" notes for why cross-package coupling is deliberately
 * avoided beyond a few explicit exceptions like
 * {@code ProductCatalogGenerator}).
 * <p>
 * <b>{@link IamBindingDemo} - the read-modify-write pattern for editing a
 * policy without clobbering concurrent changes:</b>
 * <pre>
 * Policy current = client.getIamPolicy(GetIamPolicyRequest.newBuilder()
 *     .setResource("projects/" + PROJECT_ID).build());
 *
 * // find-or-create the binding for this role, add the member
 * Policy.Builder updated = current.toBuilder();
 * // ... mutate updated's bindings list in memory ...
 *
 * client.setIamPolicy(SetIamPolicyRequest.newBuilder()
 *     .setResource("projects/" + PROJECT_ID)
 *     .setPolicy(updated.build())     // carries the SAME etag as `current`
 *     .build());
 * // if the policy changed underneath us between get and set, the etag
 * // mismatch causes setIamPolicy to fail rather than silently overwrite
 * </pre>
 * The {@code unbind} path additionally shows the "drop the binding entirely
 * once its last member is removed" detail - a binding with an empty member
 * list is not the same as no binding, and leaving empty bindings around is
 * exactly the kind of policy debris that makes a real project's IAM policy
 * hard to read months later.
 * <p>
 * <b>{@link IamPermissionTestDemo} - impersonate, then ask "what can this
 * identity ACTUALLY do" instead of assuming:</b>
 * <pre>
 * ImpersonatedCredentials impersonated = ImpersonatedCredentials.create(
 *     GoogleCredentials.getApplicationDefault(),
 *     ServiceAccountDemo.ACCOUNT_EMAIL,
 *     null,
 *     List.of("https://www.googleapis.com/auth/cloud-platform"),
 *     300);   // token lifetime in seconds
 *
 * ProjectsClient client = ProjectsClient.create(ProjectsSettings.newBuilder()
 *     .setCredentialsProvider(FixedCredentialsProvider.create(impersonated))
 *     .build());
 *
 * TestIamPermissionsResponse resp = client.testIamPermissions(
 *     TestIamPermissionsRequest.newBuilder()
 *         .setResource("projects/" + PROJECT_ID)
 *         .addAllPermissions(List.of("storage.objects.get", "compute.instances.delete", ...))
 *         .build());
 * // resp.getPermissionsList() contains ONLY the ones actually granted -
 * // silently omits the rest, so a permission's ABSENCE from the response
 * // is how you know it's denied, not an explicit "DENIED" entry
 * </pre>
 *
 * <h2>Quick reference</h2>
 * <p><b>Auth pattern used here, and why it differs per class.</b>
 * {@code ServiceAccountDemo} and {@code IamBindingDemo} run as YOUR OWN ADC
 * identity (creating/binding identities is an admin action, same reasoning
 * as {@code _01_iam}'s role management staying on human credentials).
 * {@code IamPermissionTestDemo} is the odd one out, deliberately: it
 * IMPERSONATES backend-dev-sa specifically because the whole point is
 * testing what THAT identity can do, not what you can do.
 *
 * <p><b>Important classes/methods to know:</b>
 * <ul>
 *   <li>{@code ImpersonatedCredentials.create(source, targetPrincipal,
 *       delegates, scopes, lifetimeSeconds)} - the core impersonation call;
 *       {@code delegates} (null here) is for CHAINED impersonation (A
 *       impersonates B who impersonates C) - not needed for a single hop,
 *       real for multi-team delegation setups.</li>
 *   <li>{@code ProjectsClient.getIamPolicy}/{@code setIamPolicy}/
 *       {@code testIamPermissions} - the three-verb surface for
 *       project-level policy work; the same three methods exist on other
 *       resource-specific clients (e.g. a bucket's IAM policy via
 *       {@code Storage}) for resource-scoped bindings instead of
 *       project-wide.</li>
 *   <li>{@code Policy.getEtag()} - opaque optimistic-concurrency token;
 *       always round-trip it (get it, then set it back unchanged on the
 *       policy you write) rather than omitting it - omitting it is what
 *       turns this into a silent last-write-wins race instead of a safe
 *       read-modify-write.</li>
 * </ul>
 *
 * <p><b>Common errors and what they actually mean:</b>
 * <ul>
 *   <li>{@code PERMISSION_DENIED} on {@code ImpersonatedCredentials.create}
 *       succeeding but the FIRST call using the token failing - the caller
 *       needs {@code roles/iam.serviceAccountTokenCreator} ON the target SA
 *       specifically (a grant ON the service-account resource, not a
 *       project-level role) - this is the single most common setup miss
 *       when wiring up impersonation for the first time.</li>
 *   <li>{@code ABORTED} on {@code setIamPolicy} - the etag didn't match
 *       (someone/something changed the policy between your get and set) -
 *       the correct response is retry the whole read-modify-write cycle
 *       from a fresh {@code getIamPolicy}, never retry with the stale
 *       policy object.</li>
 *   <li>A permission missing from {@code testIamPermissions}'s response
 *       that you're certain was granted - check binding SCOPE (a resource-
 *       level binding doesn't show up when testing at the project level,
 *       and vice versa) before assuming the role/binding itself is wrong.</li>
 * </ul>
 *
 * <h2>Production practices - what this demo skips that real work needs</h2>
 * <p><b>1. One service account per workload, enforced, not just
 * recommended.</b> This repo's own {@code backend-dev-sa} is intentionally
 * the ONLY app identity across many modules ({@code _03} through
 * {@code _12}) - a deliberate simplification for a single-developer
 * learning project. A real team should treat that as an anti-pattern: each
 * deployable service gets its own service account, so a compromise or bug
 * in one service can't reach what another service's identity can touch -
 * exactly the reasoning {@code _09_ai_commerce_search}'s Production
 * practices section calls out for search-serving vs. catalog-admin access
 * specifically.
 * <p><b>2. Workload Identity Federation for anything NOT running on GCP.</b>
 * This module's impersonation pattern (a GCP-authenticated human/service
 * "acting as" another SA) assumes the caller already has a GCP identity.
 * For workloads running OUTSIDE GCP entirely (GitHub Actions, another
 * cloud, an on-prem CI runner) that need to call GCP APIs, Workload
 * Identity Federation lets them exchange an EXTERNAL identity token (a
 * GitHub Actions OIDC token, an AWS IAM role) directly for GCP credentials
 * - no JSON key ever created or downloaded, the true zero-key equivalent of
 * this module's impersonation pattern for non-GCP callers.
 * <p><b>3. Audit who can impersonate whom, on a schedule.</b>
 * {@code roles/iam.serviceAccountTokenCreator} grants are exactly the kind
 * of permission that should be periodically reviewed
 * ({@code gcloud iam service-accounts get-iam-policy <sa-email>} across
 * every SA in a project) - an unused or forgotten TokenCreator grant is a
 * standing "who else can act as this identity" risk that {@code
 * IamPermissionTestDemo}'s verification pattern doesn't catch (it proves
 * what the SA CAN do, not who else can BECOME the SA).
 */
package com.ashfaq.gcplab._02_identities_bindings;
