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
 *
 * <h2>When to use each piece of this module</h2>
 * Reach for Cloud SQL, generally, when the workload is transactional
 * (orders, accounts, inventory - anything needing real ACID guarantees and
 * joins) and fits comfortably on one machine's write throughput - see
 * {@code _11_spanner}'s RDBMS-flow comparison table for exactly where that
 * ceiling is and what to reach for past it. Within this module:
 * {@link CloudSqlConnection}/{@link EmployeeCrudDemo}/{@link TableDemo}'s
 * per-call-connection pattern is fine for a one-shot script or a batch job
 * that runs, does its work, and exits - it is the WRONG pattern for a
 * long-running service; {@link PooledConnectionDemo} is the pattern an
 * actual Spring Boot service should use (and is exactly what
 * {@code spring-boot-starter-data-jpa}'s auto-configured
 * {@code HikariDataSource} does automatically if that starter's JPA/
 * DataSource support were wired up in this project - see
 * {@code pom.xml}'s note that starter is currently unused scaffold).
 *
 * <h2>Sample usage walkthrough - each demo class, what it proves</h2>
 * <b>{@link CloudSqlConnection} - the shared connector setup every other
 * class in this package builds on:</b>
 * <pre>
 * Properties props = new Properties();
 * props.setProperty("user", "postgres");
 * props.setProperty("password", System.getenv("DB_PASSWORD"));   // never hardcoded
 * props.setProperty("socketFactory", "com.google.cloud.sql.postgres.SocketFactory");
 * props.setProperty("cloudSqlInstance", "PROJECT:REGION:INSTANCE");
 *
 * Connection conn = DriverManager.getConnection("jdbc:postgresql:///postgres", props);
 * // the socketFactory property is what makes this an encrypted, IAM-resolved
 * // connection instead of a raw host:port - no firewall rule, no TLS cert
 * // management, no public IP needed on the instance at all
 * </pre>
 * <b>{@link EmployeeCrudDemo} - full CRUD via {@code PreparedStatement}
 * everywhere, never string concatenation:</b>
 * <pre>
 * try (PreparedStatement ps = conn.prepareStatement(
 *         "INSERT INTO employees (name, role) VALUES (?, ?) RETURNING id")) {
 *     ps.setString(1, "Ashfaq");
 *     ps.setString(2, "Backend Developer");
 *     try (ResultSet rs = ps.executeQuery()) {
 *         rs.next();
 *         int newId = rs.getInt("id");
 *     }
 * }
 * </pre>
 * <b>{@link TableDemo} - DDL from code, alongside a UI-created table, to
 * prove both paths produce the same result:</b>
 * <pre>
 * stmt.execute("""
 *     CREATE TABLE IF NOT EXISTS projects (
 *       id SERIAL PRIMARY KEY,
 *       name VARCHAR(100) NOT NULL,
 *       employee_id INTEGER REFERENCES employees(id),
 *       created_at TIMESTAMPTZ NOT NULL DEFAULT now()
 *     )""");
 * </pre>
 * <b>{@link PooledConnectionDemo} - the production-shaped alternative,
 * proven with real numbers, not just configured and assumed to work:</b>
 * <pre>
 * HikariConfig config = new HikariConfig();
 * config.setJdbcUrl("jdbc:postgresql:///postgres");
 * config.setDataSourceProperties(cloudSqlConnectorProps);   // same socketFactory setup
 * config.setMaximumPoolSize(3);
 * HikariDataSource dataSource = new HikariDataSource(config);   // built ONCE
 *
 * for (int i = 0; i &lt; 5; i++) {
 *     try (Connection conn = dataSource.getConnection()) {      // BORROWED, not opened fresh
 *         // ... run a query ...
 *     }                                                          // returned to pool, socket stays open
 * }
 * </pre>
 * Verified live against a real {@code db-g1-small} instance: 5 sequential
 * queries through the pool logged {@code "Added connection"} exactly ONCE
 * (HikariCP's own log line for a genuinely new physical connection) -
 * every subsequent query reused that same connection, confirmed further by
 * {@code HikariPoolMXBean} reporting 1 total connection opened against a
 * configured max of 3. Contrast this with {@link CloudSqlConnection}'s
 * pattern, which would open (and tear down) a brand-new TCP+TLS+Postgres-
 * auth handshake for every one of those 5 calls if used in a loop instead
 * of once per process.
 *
 * <h2>Quick reference</h2>
 * <p><b>Auth pattern used here.</b> Every class in this package connects
 * using YOUR OWN ADC identity to resolve the instance via the Cloud SQL
 * Admin API (the socket factory's own internal call), PLUS a native
 * Postgres username/password for the actual database login - genuinely two
 * separate auth steps layered together, not impersonation the way
 * {@code _03_storage}/{@code _06_firestore} use it. See "CRUD ties back to
 * IAM - with a caveat" above for why this isn't backend-dev-sa's
 * {@code cloudsql.instances.connect} grant actually being exercised (IAM
 * database authentication, a different feature, would be needed for that).
 *
 * <p><b>Important classes/methods to know:</b>
 * <ul>
 *   <li>{@code DriverManager.getConnection(url, Properties)} - the plain
 *       JDBC entry point; the {@code socketFactory}/{@code cloudSqlInstance}
 *       properties are what route it through the Cloud SQL Connector
 *       instead of a raw network address - remove those two properties and
 *       this becomes an ordinary (and non-functional, since there's no
 *       public IP/firewall rule) JDBC connection string.</li>
 *   <li>{@code HikariConfig}/{@code HikariDataSource} - the pool
 *       configuration object and the pool itself; {@code
 *       setMaximumPoolSize}/{@code setMinimumIdle} are the two settings
 *       that matter most and should be sized against real measured
 *       concurrency, never left at a guessed default (see Production
 *       practices below).</li>
 *   <li>{@code HikariPoolMXBean} - live pool introspection
 *       ({@code getActiveConnections()}/{@code getIdleConnections()}/
 *       {@code getTotalConnections()}) - the programmatic way to answer
 *       "is my pool actually sized correctly for real traffic," the same
 *       numbers Spring Boot Actuator's {@code /actuator/metrics} would
 *       expose automatically if that starter (present in {@code pom.xml}
 *       but currently unused scaffold) were wired up.</li>
 * </ul>
 *
 * <p><b>Common errors and what they actually mean:</b>
 * <ul>
 *   <li>{@code IllegalStateException: DB_PASSWORD environment variable is
 *       not set} - deliberate fail-fast (see {@code security.md}'s "validate
 *       all required secrets at startup" rule) rather than a confusing JDBC
 *       auth failure several layers down.</li>
 *   <li>Connection hangs for a long time, then times out - almost always
 *       the Cloud SQL Admin API call inside the socket factory failing
 *       silently-ish (instance doesn't exist, wrong instance-connection
 *       name format, or the caller's ADC identity lacks
 *       {@code cloudsql.instances.connect}) rather than the actual database
 *       login - check the instance-connection-name string
 *       ({@code PROJECT:REGION:INSTANCE}, colon-separated, easy to typo)
 *       before assuming a password problem.</li>
 *   <li>{@code HikariPool-1 - Connection is not available, request timed
 *       out} - every pooled connection is checked out and none returned
 *       within {@code connectionTimeout} (default 30s) - either genuine
 *       overload (pool sized too small for real concurrency) or a leak
 *       (code that calls {@code getConnection()} without a
 *       try-with-resources / never closes it) - HikariCP's leak-detection
 *       threshold ({@code setLeakDetectionThreshold(...)}, not set in this
 *       module) logs a stack trace for exactly this case in production.</li>
 * </ul>
 *
 * <h2>Production practices - what this demo skips that real work needs</h2>
 * <p><b>1. Size the pool against measured concurrency, not a guess.</b>
 * This module's {@code maximumPoolSize(3)} was picked arbitrarily for a
 * five-query demo. HikariCP's own guidance formula for a real service:
 * {@code connections = ((core_count * 2) + effective_spindle_count)} as a
 * STARTING point, then tune against actual observed wait times - and
 * critically, Cloud SQL itself has a hard {@code max_connections} ceiling
 * per instance tier, shared across every service connecting to it, so a
 * pool sized too large on one service can starve others.
 * <p><b>2. Secret Manager instead of an environment variable for the DB
 * password, in a real deployment.</b> {@code DB_PASSWORD} as a plain env var
 * is acceptable for local dev (this module's context) but not for a
 * deployed service - see {@code docs/production-readiness.md}'s Secret
 * Manager entry for the versioned, IAM-controlled, audit-logged
 * alternative, and consider IAM database authentication (mentioned above)
 * to remove the password entirely for the identity that matters most.
 * <p><b>3. One shared {@code HikariDataSource} per application, not per
 * request or per class.</b> {@link PooledConnectionDemo} builds the pool
 * ONCE at the top of {@code main()} and reuses it for every query - the
 * pattern that matters is exactly this: a Spring Boot service should have
 * exactly one {@code DataSource} bean for its whole lifetime, injected
 * everywhere, never constructed per-request (the same "build once, reuse
 * everywhere" rule stated for every other GCP/DB client throughout this
 * repo, here doubly true since a pool is specifically expensive to
 * duplicate).
 * <p><b>4. Monitor pool exhaustion as a real alerting signal.</b>
 * {@code HikariPoolMXBean}'s live stats (used in this module purely to
 * prove reuse) are exactly what should feed a production dashboard -
 * sustained {@code activeConnections} near {@code maximumPoolSize} is an
 * early warning of exactly the kind of overload that later shows up as
 * "connection is not available, request timed out" in production logs.
 */
package com.ashfaq.gcplab._04_cloudsql;
