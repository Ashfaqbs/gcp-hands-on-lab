/**
 * Reading order: 07 (comes after _01_iam ... _06_firestore).
 *
 * <h2>GKE = GCP's managed Kubernetes</h2>
 * Unlike every module before this, there's little Java SDK code here - a
 * cluster is infrastructure you deploy CONTAINERS onto via kubectl/YAML,
 * not something you script line-by-line the way we created buckets/tables/
 * roles. The learning goal is deploy -&gt; verify -&gt; tear down, using a
 * public sample image rather than building our own (Docker build +
 * Artifact Registry push is its own separate exercise, deliberately out of
 * scope here to keep this module simple, per explicit request).
 *
 * <h2>Autopilot vs. Standard mode</h2>
 * Console defaulted us into Autopilot (GKE manages nodes/scaling/security
 * automatically - "give it a name and region, we handle the rest").
 * Standard mode would have qualified for GKE's free zonal cluster
 * management-fee waiver; Autopilot always incurs a small management fee
 * (~$0.10/hr) on top of the node costs. Accepted deliberately - the fee
 * difference for a short exercise is a few cents, not worth the extra
 * Standard-mode node-pool configuration decisions for a "keep it simple"
 * pass.
 *
 * <h2>How we created this (2026-08-31)</h2>
 * <ul>
 *   <li>Console UI: Kubernetes Engine -&gt; Clusters -&gt; Create -&gt;
 *       Autopilot flow (Console's default landing page for cluster
 *       creation). Name {@code learning-gke}, region {@code us-central1}.</li>
 *   <li>Fleet registration: skipped (not needed for a single standalone
 *       learning cluster - fleets are for managing/normalizing policy
 *       across MULTIPLE clusters).</li>
 *   <li>Networking: left every default as offered - notably control plane
 *       access ended up as "Access using IPv4 addresses" (external IP)
 *       rather than the DNS-based option, since that's simpler for
 *       kubectl from a laptop with no extra network setup. Network/subnet
 *       left as {@code default}, Pod/Service IP ranges auto-managed
 *       (not custom), no private nodes, no authorized-network IP
 *       allowlisting.</li>
 *   <li>Advanced settings: Release channel Regular (recommended). Left
 *       DISABLED (all cost- or complexity-adding, none needed for
 *       deploy-verify-delete): Service Mesh, Backup for GKE, Binary
 *       Authorization, Secret Manager integration, Google Groups for
 *       RBAC, application-layer secret encryption, workload vulnerability
 *       scanning, Ray Operator, Pod Snapshots, Automatic Application
 *       Metric Collection. Left ENABLED (default, low/no extra cost):
 *       Cloud Logging, Cloud Monitoring, boot disk encryption
 *       (Google-managed), default service account/access scopes.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * Three independent charges stack on GKE: (1) the cluster management fee -
 * $0 for one zonal Standard cluster per billing account (waived), but
 * Autopilot always charges ~$0.10/hour regardless of that waiver, which we
 * accepted here; (2) compute for the nodes themselves - standard Compute
 * Engine per-vCPU/memory pricing, EXCEPT on Autopilot you're billed for
 * what your PODS request (CPU/memory/ephemeral storage), not raw node
 * capacity - the platform packs pods onto nodes behind the scenes and you
 * never see or pay for the difference; (3) the LoadBalancer Service - each
 * one provisions a real Google Cloud Load Balancer, billed hourly plus
 * per-GB processed, separate from GKE itself. Formula (Autopilot):
 * <pre>
 * monthly_cost = (management_fee_per_hour x hours_running)
 *              + sum_over_pods(cpu_requested x cpu_rate + mem_gb_requested x mem_rate) x hours_running
 *              + (lb_hourly_rate x hours_running) + (gb_processed x lb_data_rate)
 * </pre>
 * This module's real cost was a few cents at most - one pod, default
 * resource request, ~10 minutes total from apply to delete. Pricing
 * reference: {@code cloud.google.com/kubernetes-engine/pricing} (breaks out
 * Autopilot vs. Standard, and the separate Cloud Load Balancing rates are
 * at {@code cloud.google.com/vpc/network-pricing#lb}).
 *
 * <h2>Connecting kubectl - not YAML, a kubeconfig</h2>
 * Two independent layers, easy to conflate: (1) CONNECTING to the cluster's
 * API uses {@code gcloud container clusters get-credentials learning-gke
 * --region us-central1}, which writes connection info + a cert into
 * {@code ~/.kube/config} - no YAML involved. (2) YAML (hello-app.yaml)
 * describes WHAT to run once connected, applied via {@code kubectl apply
 * -f}. Also needed a one-time plugin install:
 * {@code gcloud components install gke-gcloud-auth-plugin} - kubectl 1.26+
 * requires it for GKE's auth flow, not bundled with gcloud by default.
 *
 * <h2>Deploy/verify/teardown (2026-08-31)</h2>
 * <ul>
 *   <li>{@code hello-app.yaml} - a Deployment (1 replica, public image
 *       {@code gcr.io/google-samples/hello-app:1.0}, no Docker build of our
 *       own needed) + a {@code type: LoadBalancer} Service exposing port 80
 *       -&gt; container port 8080.</li>
 *   <li>Autopilot auto-injected a default CPU resource request on the
 *       container since none was specified in the YAML - Autopilot
 *       REQUIRES resource requests/limits on every container, unlike
 *       Standard mode where they're optional.</li>
 *   <li>Right after cluster creation, {@code kubectl get nodes} showed one
 *       node stuck {@code NotReady,SchedulingDisabled}, then briefly
 *       "No resources found" - Autopilot doesn't keep idle nodes running at
 *       all; a node only gets provisioned once a workload is actually
 *       scheduled. Applying the Deployment triggered real node
 *       provisioning, not the earlier bootstrap node.</li>
 *   <li>Verified live: pod reached {@code Running} (~72s after apply),
 *       Service got an external IP (~2min after apply), and
 *       {@code curl http://EXTERNAL_IP/} returned "Hello, world!
 *       Version: 1.0.0 Hostname: hello-app-&lt;pod-id&gt;" - real external
 *       traffic through the LoadBalancer to the pod.</li>
 *   <li>Ingress deliberately NOT used - it exists for HTTP-layer routing
 *       across MULTIPLE services sharing one entry point (path-based
 *       rules, shared TLS). With exactly one service already reachable
 *       directly via its own LoadBalancer IP, Ingress would be pure
 *       overhead, not a missing step.</li>
 * </ul>
 *
 * <h2>Torn down same session</h2>
 * {@code kubectl delete -f hello-app.yaml} first (cleans up the
 * LoadBalancer's forwarding rule before the cluster disappears out from
 * under it), then {@code gcloud container clusters delete learning-gke
 * --region us-central1}. Confirmed clean via {@code gcloud container
 * clusters list} (zero), {@code gcloud compute forwarding-rules list}
 * (zero - no orphaned LB), and {@code gcloud compute instances list}
 * (zero - no leftover Autopilot-provisioned nodes).
 */
package com.ashfaq.gcplab._07_gke;
