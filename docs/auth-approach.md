# Auth approach per service: use whatever the industry actually uses

## The rule

For each GCP service in this repo, authenticate the way real companies
predominantly do it for that specific service - not a single dogmatic
method (e.g. "always IAM impersonation") applied everywhere regardless of
fit. Some services are IAM-native end to end; others still commonly use a
native username/password even in serious production setups. Both are
legitimate - match the service, don't force a pattern onto it.

## Per-service, what we actually use and why

| Service | Auth used here | Why this is the realistic choice |
|---|---|---|
| IAM Admin API, Resource Manager (_01_iam, _02_identities_bindings) | ADC (your own login) for admin ops; impersonated backend-dev-sa to prove/test grants | Role/policy administration is normally done by a human or a CI identity with elevated rights - not the app's own runtime identity. |
| Cloud Storage (_03_storage) | ADC (you) for bucket admin; impersonated backend-dev-sa for object read/write | Matches [[iam-human-vs-service-account]] split: infra owns buckets, the app only touches objects - IAM-native both ways, no reason not to use identity-based access throughout. |
| Cloud SQL (_04_cloudsql) | Native PostgreSQL username/password (via Cloud SQL Java Connector for the network path) | Legitimate, still extremely common in real companies - DB credentials managed via a secret manager, not necessarily IAM DB auth. IAM database authentication exists as an alternative and is a valid future exercise, not a requirement. |
| Memorystore for Redis (_05_redis) | Redis AUTH string (or none - ours ended up without it) | Redis's data plane has NO IAM integration at all, in GCP or anywhere else - AUTH string is the entire realistic menu. Only the management API (create/delete the instance) is IAM-gated. |
| Firestore (_06_firestore) | Impersonated backend-dev-sa | Firestore is fully IAM-native with no password-based alternative at all - so this is the only realistic option, and also the correct one. |

## The one thing worth being deliberate about

Whichever method fits a service, use the one that avoids a long-lived
static secret sitting in a file when a short-lived alternative exists for
that service:
- IAM services: prefer ADC / impersonation / attached identity over a
  downloaded service account JSON key (see [[_02_identities_bindings]] for
  the three options and when each applies).
- Password-based services (Cloud SQL native auth, Redis AUTH): the
  password is unavoidable, so keep it out of code and git - read from an
  environment variable at runtime, never hardcoded (see
  {@code CloudSqlConnection} / {@code CacheCrudDemo} for the pattern used
  throughout this repo).

Within that constraint, whichever auth method a service's real-world users
predominantly reach for is the right one to use here too.
