# Local Setup — gcloud CLI & Authentication

One-time machine setup needed before any of the Java SDK code in this repo
can talk to GCP. Do this once per dev machine.

## 1. Install the Google Cloud CLI

Windows installer: https://cloud.google.com/sdk/docs/install

Installed here at:
`C:\Users\ashfa\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin`

Add that `bin` folder to your PATH if `gcloud` isn't recognized in a new
terminal (PowerShell: check with `where.exe gcloud`).

## 2. Initialize gcloud and log in (human/interactive identity)

```
gcloud init
```

This walks through:
- `gcloud auth login` — opens a browser, sign in as the Google account that
  owns the project (here: `YOUR_GOOGLE_ACCOUNT@gmail.com`).
- **Pick cloud project** — select the project this repo targets:
  `project-3d2fd1eb-6dd8-40b6-958` ("My First Project").

This sets your **active gcloud config**, used by `gcloud` CLI commands
(`gcloud services enable ...`, `gcloud iam ...`, etc). It does **not** by
itself let Java/SDK code authenticate — that's step 3.

Verify anytime with:
```
gcloud config list
gcloud auth list
```

## 3. Application Default Credentials (ADC) — what the Java SDK actually uses

```
gcloud auth application-default login
```

This opens a second browser login and writes credentials to:
`C:\Users\ashfa\AppData\Roaming\gcloud\application_default_credentials.json`

Any Google client library (our `IamRoleVerifier`, future Storage/SQL/Vertex
AI code) calls `GoogleCredentials.getApplicationDefault()`, which reads this
file automatically — no key file checked into the repo, no secrets in code,
matches the "never commit credentials" rule. This is the standard local-dev
auth pattern; in production, workloads instead use attached service account
identities (Compute/GKE/Cloud Run metadata server) — same ADC call, different
credential source, zero code changes.

**These credentials are tied to this machine and this Google account only.**
If you set up a second dev machine, or someone else clones this repo, they
must repeat steps 2-3 themselves — nothing here is portable or shared.

## 4. Enable APIs used by this repo's code

Each GCP API must be explicitly enabled per project before SDK calls against
it will succeed (`PERMISSION_DENIED` / `SERVICE_DISABLED` otherwise). Enabled
so far:

```
gcloud services enable iam.googleapis.com --project=project-3d2fd1eb-6dd8-40b6-958
```

As we add modules (Cloud SQL, Storage, Vertex AI, GKE...), the corresponding
`*.googleapis.com` service will need enabling the same way — each module's
own notes will call this out.

## Quick health check

```
gcloud config get-value project        # should print project-3d2fd1eb-6dd8-40b6-958
gcloud auth list                       # should show YOUR_GOOGLE_ACCOUNT@gmail.com as ACTIVE
gcloud services list --enabled --filter="name:iam.googleapis.com"
```
