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
 */
package com.ashfaq.gcplab._02_identities_bindings;
