/**
 * Reading order: 04 (comes after _01_iam, _02_identities_bindings, _03_storage).
 *
 * <h2>Cloud SQL = managed RDBMS (here: PostgreSQL)</h2>
 * Google handles patching, backups, storage scaling, and HA (if enabled).
 * Unlike every prior module, this is the first PAID resource in the repo -
 * even the smallest tier is a persistent VM under the hood, not serverless.
 * Instance: {@code free-trial-first-project} (PostgreSQL 18, us-central1),
 * created under Google's 30-day Cloud SQL free trial - $0 unless upgraded.
 * Delete the instance when this module is done regardless of the trial
 * window; don't rely on a promotional offer as a cost-control plan.
 *
 * <h2>Cost</h2>
 * Unlike everything before this module, Cloud SQL is NOT usage-metered -
 * it's a persistent VM billed by the hour whether or not you run a single
 * query, made of three separately-priced components: (1) vCPU + memory
 * ($/vCPU-hour, $/GB-hour - varies by machine tier), (2) storage ($/GB-month
 * for the provisioned disk, charged for the full size regardless of how
 * much data is actually stored), (3) optional HA/read replicas (each
 * roughly doubles/adds the base cost, not used here). This instance rode
 * the 30-day Cloud SQL free trial ($0 during the trial, real cost after),
 * which is why it was deleted same-session rather than treated as a safe
 * long-term $0 resource. Formula:
 * <pre>
 * monthly_cost = (vcpu_count x vcpu_rate + memory_gb x memory_rate) x hours_running
 *              + provisioned_disk_gb x disk_rate_per_gb
 *              + (backup_storage_gb x backup_rate)   [if automated backups on]
 * </pre>
 * Pricing reference: {@code cloud.google.com/sql/docs/postgres/pricing}
 * (per-region rate tables by machine tier); the Console's instance creation
 * wizard also shows a live estimated monthly cost as you pick machine size.
 *
 * <h2>Concept flow: instance -&gt; database -&gt; schema -&gt; table -&gt; row</h2>
 * The PostgreSQL containment hierarchy, top to bottom:
 * <pre>
 * Cloud SQL instance (the VM: "free-trial-first-project")
 *   -&gt; database        (a named DB inside the instance, e.g. "postgres")
 *     -&gt; schema         (a namespace inside the database, default "public")
 *       -&gt; table        (e.g. "employees" - defines columns/types)
 *         -&gt; row         (one record; a column value is one field of a row)
 * </pre>
 * An instance can host multiple databases; a database can have multiple
 * schemas; a schema can have multiple tables. This module only ever used
 * the default database and {@code public} schema - one instance, one
 * schema, two tables ({@code employees}, {@code projects}).
 *
 * <h2>Connecting: Cloud SQL Java Connector, not a raw JDBC URL</h2>
 * Instead of connecting to a public IP (which would require managing
 * firewall rules and TLS certs by hand), we use the Cloud SQL Socket
 * Factory - it wraps the JDBC driver, resolves the instance via the Cloud
 * SQL Admin API, and encrypts the connection automatically using IAM
 * credentials (ADC). This is the standard pattern; the old "Cloud SQL Auth
 * Proxy" standalone binary does the same thing as an external process
 * instead of an in-JVM library.
 *
 * <h2>One table via Console UI, one via code</h2>
 * {@code employees} - created by hand via Cloud SQL Studio, to see the
 * Console's built-in SQL client (no separate psql install needed).
 * {@code TableDemo} - creates a second table programmatically via JDBC DDL,
 * proving the same operation works from code, same pattern as every prior
 * module (role, service account, bucket all had a UI + code path).
 *
 * <h2>CRUD ties back to IAM - with a caveat</h2>
 * {@code EmployeeCrudDemo} connects using your own ADC identity (to resolve
 * the instance via the Cloud SQL Admin API) plus a native Postgres
 * username/password for the actual database login (read from the
 * DB_PASSWORD env var, never hardcoded). This is NOT the same as using
 * backend-dev-sa's {@code cloudsql.instances.connect} grant from _01_iam -
 * that permission gates IAM database authentication specifically (a
 * separate, not-yet-covered Cloud SQL feature where the DB login itself is
 * an IAM identity, no password at all). Worth revisiting as a follow-up:
 * swap password auth for IAM DB auth to actually exercise that grant.
 *
 * <h2>How we created this (2026-08-31, torn down same session)</h2>
 * <ul>
 *   <li>Instance created via Console UI's Cloud SQL "free trial" flow
 *       (SQL -&gt; Create Instance -&gt; PostgreSQL) - 30 days at $0 unless
 *       upgraded. Landed on {@code db-perf-optimized-N-8} / 100GB (the
 *       trial's preset size, larger than we needed, but free for the
 *       trial window) in us-central1. Enabled the prompted {@code
 *       sqladmin.googleapis.com} (Cloud SQL Admin API) along the way.</li>
 *   <li>{@code employees} table created by hand via Cloud SQL Studio (built
 *       into the Console, reached from the instance's page) running a
 *       CREATE TABLE statement directly.</li>
 *   <li>{@code projects} table created via {@code TableDemo create}
 *       (JDBC DDL through the Cloud SQL Java Connector).</li>
 *   <li>Full CRUD run via {@code EmployeeCrudDemo} (create/list/update
 *       verified; delete left available but not run in this session).</li>
 *   <li>Instance deleted via {@code gcloud sql instances delete} once the
 *       exercise was done - confirmed with a follow-up {@code gcloud sql
 *       instances list} showing zero instances.</li>
 * </ul>
 *
 * <h2>Internal architecture: Cloud SQL is a managed VM, not serverless magic</h2>
 * Unlike GCS or Firestore, a Cloud SQL instance really is one (or, with HA,
 * two) dedicated Compute Engine VM(s) running a genuine PostgreSQL server
 * process - Google's management layer just automates everything around it:
 * <pre>
 * your code (EmployeeCrudDemo)
 *   -&gt; Cloud SQL Java Connector (in-process library, wraps the JDBC driver)
 *      -&gt; Cloud SQL Admin API: "resolve instance X, give me its current
 *         IP + a client cert" (this call is authenticated via YOUR ADC/IAM
 *         identity - this is the part that needs cloudsql.instances.connect)
 *      -&gt; Connector opens a mutually-authenticated TLS tunnel directly to
 *         the instance's private control-plane endpoint using that
 *         short-lived cert (rotated automatically ~hourly) - no manual
 *         firewall rule or public IP needed, and no separate proxy binary
 *         to run (the old "Cloud SQL Auth Proxy" does the identical thing
 *         as an external OS process instead of an in-JVM library)
 *   -&gt; actual SQL traffic (the JDBC protocol, e.g. a native Postgres
 *      username/password login as used here) flows inside that tunnel to
 *      the real postgres process on the underlying VM
 * </pre>
 * The management layer's real job is everything OUTSIDE that data path:
 * automated point-in-time-recoverable backups, minor-version patching
 * during a maintenance window, storage auto-grow on the underlying
 * persistent disk, and - if HA/regional availability is enabled (not used
 * here) - a synchronously-replicated standby VM in a second zone with an
 * internal health-checker that triggers automatic failover (DNS/internal-IP
 * cutover to the standby) if the primary VM or zone goes down.
 *
 * <h2>System design takeaway</h2>
 * Because it's a real always-on VM under the hood (see the Cost section
 * above), Cloud SQL's scaling story is fundamentally vertical-first: more
 * vCPU/memory on the instance, or read replicas fanned out for read-heavy
 * workloads (each replica is a full separate VM, billed separately, with
 * asynchronous replication lag - never assume replica reads are
 * up-to-the-millisecond fresh). This is the right tool when you need real
 * transactions, joins, and strong relational consistency (see _06_firestore
 * for the deliberately different, denormalized alternative) - but it does
 * NOT scale horizontally the way Firestore or GCS do, so a system design
 * that expects unbounded write throughput on one Cloud SQL instance will
 * eventually hit a real ceiling that only sharding (an application-level
 * concern Cloud SQL doesn't automate) can push past.
 */
package com.ashfaq.gcplab._04_cloudsql;
