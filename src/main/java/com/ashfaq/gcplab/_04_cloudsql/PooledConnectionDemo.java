package com.ashfaq.gcplab._04_cloudsql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * The production-shaped alternative to {@link CloudSqlConnection}'s
 * {@code DriverManager.getConnection()}-per-call pattern - this repo's own
 * {@code ~/.claude/rules/coding-style.md}/{@code java-springboot.md} rules
 * mandate HikariCP for exactly this reason, and every other demo in this
 * package deliberately does NOT follow that rule, so the gap is real, not
 * an oversight to paper over.
 *
 * {@link EmployeeCrudDemo}/{@link TableDemo} open a BRAND NEW physical
 * connection (a real TCP handshake through the Cloud SQL Connector, TLS
 * negotiation, Postgres auth) on every single CLI invocation, then close it
 * - correct for a one-shot script where the process exits immediately
 * after, actively wrong for a long-running service handling many requests,
 * where opening a fresh connection per request adds real, avoidable latency
 * to every single one. A connection POOL (what HikariCP is) opens a small
 * number of connections ONCE, keeps them alive, and hands them out/takes
 * them back per request - this class proves that reuse is actually
 * happening, not just configured.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._04_cloudsql.PooledConnectionDemo
 */
public final class PooledConnectionDemo {

    private static final String INSTANCE_CONNECTION_NAME =
            "project-3d2fd1eb-6dd8-40b6-958:us-central1:learning-pool-demo";
    private static final String DATABASE = "postgres";
    private static final String DB_USER = "postgres";

    private PooledConnectionDemo() {
    }

    public static void main(String[] args) throws Exception {
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("DB_PASSWORD environment variable is not set.");
        }

        HikariDataSource dataSource = buildPooledDataSource(password);

        try {
            // Run several queries through the SAME pool, one connection borrowed
            // and returned per query - proving reuse, not just configuration.
            for (int i = 1; i <= 5; i++) {
                runOneQuery(dataSource, i);
            }

            // The pool exposes real, live statistics - the concrete evidence
            // that connections were reused rather than opened fresh each time.
            HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
            System.out.println("\nPool stats after 5 queries:");
            System.out.println("  Total connections created: " + dataSource.getMaximumPoolSize()
                    + " max configured, " + pool.getTotalConnections() + " actually opened");
            System.out.println("  Active right now: " + pool.getActiveConnections());
            System.out.println("  Idle (alive, ready for reuse): " + pool.getIdleConnections());
        } finally {
            dataSource.close(); // closes every pooled physical connection cleanly
        }
    }

    private static HikariDataSource buildPooledDataSource(String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql:///" + DATABASE);
        config.setUsername(DB_USER);
        config.setPassword(password);

        Properties dataSourceProperties = new Properties();
        dataSourceProperties.setProperty("socketFactory", "com.google.cloud.sql.postgres.SocketFactory");
        dataSourceProperties.setProperty("cloudSqlInstance", INSTANCE_CONNECTION_NAME);
        config.setDataSourceProperties(dataSourceProperties);

        // Deliberately small for a learning instance - a real service sizes this
        // against actual concurrent-request load, never left at a library default
        // guessed without measuring.
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setPoolName("learning-cloudsql-pool");

        return new HikariDataSource(config);
    }

    private static void runOneQuery(HikariDataSource dataSource, int callNumber) throws Exception {
        // getConnection() BORROWS an existing pooled connection (or opens one,
        // only up to maximumPoolSize) - it does NOT open a fresh physical
        // connection every time, unlike CloudSqlConnection.get().
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT " + callNumber + " AS call_number, now() AS server_time")) {
            rs.next();
            System.out.println("Call " + callNumber + ": server_time=" + rs.getTimestamp("server_time")
                    + " (connection borrowed from pool, not freshly opened)");
        }
        // try-with-resources here returns the connection to the POOL, it does
        // NOT close the underlying physical socket - that's the whole point.
    }
}
