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
 *
 * <h2>Internal architecture: what "managed Kubernetes" actually manages</h2>
 * <pre>
 * kubectl apply -f hello-app.yaml
 *   -&gt; kube-apiserver (the CONTROL PLANE - entirely Google-hosted, you never
 *      see or pay directly for its VMs; it's the single source of truth,
 *      backed by etcd, also fully Google-managed and never directly
 *      reachable)
 *   -&gt; scheduler watches for unscheduled Pods -&gt; picks a node (Standard) or
 *      TRIGGERS NEW NODE PROVISIONING (Autopilot - this is why "kubectl get
 *      nodes" showed nothing until the Deployment was applied: Autopilot
 *      doesn't pre-provision capacity, it reacts to actual pending pods)
 *   -&gt; kubelet (an agent on every node, Google-managed on Autopilot) pulls
 *      the container image and starts it, reports status back to the API
 *      server
 *   -&gt; Service type=LoadBalancer -&gt; GKE's cloud-controller-manager
 *      component watches for these and calls the Compute Engine API on your
 *      behalf to provision a REAL external L4 load balancer + forwarding
 *      rule - this is a separate GCP resource, separately billed, which is
 *      exactly why deleting the Deployment/Service first (before the
 *      cluster) matters: it lets that controller clean up the LB properly
 *      instead of leaving an orphaned forwarding rule behind
 * </pre>
 * Networking underneath is VPC-native (IP alias ranges) by default: every
 * Pod gets a real routable IP straight from the VPC's secondary range
 * rather than an overlay network requiring extra encapsulation - this is
 * why Pods can be reached directly by IP from elsewhere in the VPC and why
 * Pod IP exhaustion is a real capacity-planning concern (the secondary
 * range size caps how many Pods a cluster can ever run, decided at cluster
 * creation, not resizable after the fact).
 *
 * <h2>System design takeaway</h2>
 * The whole point of the control-plane/data-plane split is that a
 * Deployment's declared state (1 replica, this image, these resource
 * requests) is a DESIRED state the control plane continuously reconciles
 * toward - not a one-time command. Kill the pod and the Deployment
 * controller notices the actual state has drifted from desired and
 * recreates it, with zero code of ours involved; this reconciliation loop
 * (desired vs. actual, continuously re-converged) is the core Kubernetes
 * design pattern and the reason it's the default choice for anything that
 * needs to self-heal without a human or a separate orchestration script.
 * Autopilot's real trade is giving up node-level control (no SSH to nodes,
 * no DaemonSets needing host access, mandatory resource requests on every
 * container) in exchange for never capacity-planning nodes yourself - the
 * right call for most application workloads; Standard mode earns its extra
 * complexity only when something genuinely needs node-level access
 * (custom kernel modules, specific machine types/GPUs Autopilot doesn't
 * offer, DaemonSet-based infrastructure agents).
 *
 * <h2>When to use this service</h2>
 * Reach for GKE when a workload is a long-running, always-on process that
 * genuinely benefits from container orchestration - multiple replicas,
 * rolling updates, self-healing, service discovery between multiple
 * services. Do NOT reach for it for something that only needs to run in
 * response to an event or a schedule (Cloud Run or Cloud Functions - both
 * genuinely missing from this repo, see {@code docs/roadmap.md} - are a
 * much smaller operational surface for that shape of workload, with no
 * cluster to manage at all). Within GKE itself: default to Autopilot (this
 * module's choice) unless a specific, named requirement needs Standard's
 * node-level access - see the System design section above for exactly
 * which requirements those are.
 *
 * <h2>Sample usage walkthrough - the YAML and kubectl commands, what each proves</h2>
 * This module has no Java code - GKE's day-to-day surface is genuinely
 * YAML + {@code kubectl}, not a client library, which is itself worth
 * internalizing: unlike every other module in this repo, there is no
 * {@code GoogleCredentials}/{@code ImpersonatedCredentials} call anywhere
 * in this workflow (see Quick reference below for how auth actually works
 * here instead).
 * <pre>
 * # 1. CONNECT kubectl to the cluster's control plane - writes connection
 * #    info + a cert into ~/.kube/config, a ONE-TIME step per cluster
 * gcloud container clusters get-credentials learning-gke --region us-central1
 *
 * # 2. APPLY the manifest - a Deployment (desired state: 1 replica of this
 * #    image) and a Service (expose it, provision a real Load Balancer)
 * kubectl apply -f hello-app.yaml
 *
 * # 3. VERIFY - never assume apply succeeding means the workload is healthy
 * kubectl get pods                 # watch for STATUS: Running
 * kubectl get service hello-app-service   # watch for an EXTERNAL-IP to be assigned
 * curl http://EXTERNAL_IP/          # the actual end-to-end proof - real traffic through
 *                                    # the LoadBalancer to the pod, not just "kubectl says it's up"
 *
 * # 4. TEAR DOWN - delete the Service FIRST, so its LoadBalancer/forwarding
 * #    rule is cleaned up properly before the cluster disappears out from under it
 * kubectl delete -f hello-app.yaml
 * gcloud container clusters delete learning-gke --region us-central1
 * </pre>
 * {@code hello-app.yaml} itself is two documents in one file
 * (YAML {@code ---} separator): a {@code Deployment} (desired state: how
 * many replicas of which container image) and a {@code Service} of
 * {@code type: LoadBalancer} (how to reach them from outside the cluster) -
 * the two-resource pattern almost every real workload starts from, with a
 * third (Ingress) added only once multiple services need to share one
 * entry point (see "Ingress deliberately NOT used" in "How we created
 * this" above for why this module didn't need it).
 *
 * <h2>Quick reference</h2>
 * <p><b>Auth pattern used here - genuinely different from every other
 * module.</b> There's no impersonation or ADC call in this workflow because
 * {@code kubectl}'s auth is a SEPARATE layer from GCP IAM: step 1 above
 * (`get-credentials`) uses your gcloud/ADC identity ONCE, to fetch a
 * short-lived credential plugin config, but every subsequent
 * {@code kubectl} command authenticates to the Kubernetes API SERVER
 * itself (which then applies its OWN RBAC rules, layered on top of, not
 * instead of, whatever GCP IAM role got you connected in the first place) -
 * GKE effectively has two authorization systems stacked (IAM: can you
 * reach the cluster at all; Kubernetes RBAC: what can you do once
 * connected), neither of which this module's simple deploy needed to
 * configure beyond the defaults.
 *
 * <p><b>Important kubectl commands to know, beyond what this module used:</b>
 * <ul>
 *   <li>{@code kubectl logs &lt;pod&gt;} / {@code kubectl logs -f &lt;pod&gt;} -
 *       the first thing to reach for when a pod is unhealthy - stdout/
 *       stderr from the container, streamed live with {@code -f}.</li>
 *   <li>{@code kubectl describe pod &lt;pod&gt;} - the EVENTS section at the
 *       bottom is where scheduling failures, image-pull errors, and probe
 *       failures actually show up - more useful for "why won't this start"
 *       than {@code get pods}' one-line status alone.</li>
 *   <li>{@code kubectl exec -it &lt;pod&gt; -- /bin/sh} - a shell inside a
 *       running container, for live debugging - not available at all on
 *       some minimal/distroless images by design, worth knowing before
 *       relying on it as a debugging plan.</li>
 *   <li>{@code kubectl rollout status deployment/&lt;name&gt;} /
 *       {@code kubectl rollout undo deployment/&lt;name&gt;} - watch a rolling
 *       update's progress, or roll back to the previous revision - the
 *       command-line face of the "desired state reconciliation" concept in
 *       the System design section above.</li>
 * </ul>
 *
 * <p><b>Common errors and what they actually mean:</b>
 * <ul>
 *   <li>{@code ImagePullBackOff} in {@code kubectl get pods} - the node
 *       can't pull the container image - a typo'd image name/tag, a
 *       private registry the cluster's identity can't authenticate to
 *       (Artifact Registry needs the node/Workload Identity to hold
 *       {@code roles/artifactregistry.reader}), or (rare, but real) a
 *       public registry rate-limiting anonymous pulls.</li>
 *   <li>Pod stuck {@code Pending} - almost always a scheduling constraint
 *       that can't be satisfied: on Autopilot specifically, a MISSING
 *       resource request/limit is rejected outright rather than defaulted
 *       silently the way Standard mode would (see "How we created this"
 *       above); {@code kubectl describe pod} shows the exact reason.</li>
 *   <li>Service's {@code EXTERNAL-IP} stays {@code &lt;pending&gt;}
 *       indefinitely - a real, if usually transient (1-2 minutes), part of
 *       Load Balancer provisioning; if it never resolves, check
 *       {@code kubectl describe service} for quota errors (a project-level
 *       cap on the number of forwarding rules/external IPs exists and is
 *       occasionally hit).</li>
 * </ul>
 *
 * <h2>Production practices - what this demo skips that real work needs</h2>
 * <p><b>1. Never {@code kubectl apply} by hand against a real cluster.</b>
 * This module's manual {@code kubectl apply -f hello-app.yaml} is
 * appropriate for a learning exercise and a genuine anti-pattern for
 * anything real - a production GKE workflow deploys through CI/CD (Cloud
 * Build, GitHub Actions, Argo CD) applying manifests from a reviewed git
 * commit, with the cluster's actual state as a downstream EFFECT of a
 * merged PR, not a developer's local terminal.
 * <p><b>2. Resource requests AND limits on every container, deliberately
 * sized.</b> Autopilot forced this module to specify a resource request at
 * all (see "How we created this" above); a real deployment should also set
 * LIMITS (a ceiling, not just a floor) and size both against actual
 * measured usage (via {@code kubectl top pod} or Cloud Monitoring), not
 * copy-pasted defaults - an under-sized request causes the scheduler to
 * pack too many pods per node (real contention); an over-sized one wastes
 * money paying for capacity that's requested but never used.
 * <p><b>3. Liveness, readiness, AND startup probes - not just "the pod
 * started."</b> This module's minimal manifest has none of the three -
 * Kubernetes' default behavior (a process that hasn't crashed is
 * considered healthy) is a real production gap: a READINESS probe stops
 * traffic reaching a pod that's up but not yet able to serve (e.g. still
 * warming a cache); a LIVENESS probe restarts a pod that's up but
 * genuinely stuck (deadlocked, wedged); a STARTUP probe gives a slow-
 * booting app (a big JVM app is the classic case) room to initialize
 * before liveness checks start counting against it.
 * <p><b>4. Namespaces to separate environments/teams sharing one
 * cluster.</b> This module's single {@code hello-app} deployment lives in
 * the {@code default} namespace, fine for a one-workload learning cluster -
 * a real cluster serving multiple teams/environments should use namespaces
 * (plus {@code ResourceQuota}/{@code NetworkPolicy} per namespace) so one
 * team's misconfigured workload can't starve or reach another's.
 * <p><b>5. Secrets via Kubernetes Secrets backed by Secret Manager, never
 * baked into an image or a plain env var in the YAML.</b> This module's
 * stateless "hello world" container needed no secrets at all - a real
 * workload's database password/API key should be a Kubernetes
 * {@code Secret} (ideally synced from Secret Manager via the Secret
 * Manager CSI driver, not hand-created and left unrotated) mounted as a
 * file or env var at runtime, never committed into the image or the plain
 * YAML manifest itself.
 */
package com.ashfaq.gcplab._07_gke;
