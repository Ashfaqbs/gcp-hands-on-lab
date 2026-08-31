package com.ashfaq.gcplab._04_cloudsql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Shared connection helper - uses the Cloud SQL Java Connector (socket
 * factory) instead of a raw host:port JDBC URL, so the connection is
 * encrypted and instance discovery goes through the Cloud SQL Admin API
 * under your ADC credentials. No public IP allowlisting, no manual TLS
 * certs.
 *
 * Password is read from the DB_PASSWORD environment variable - never
 * hardcoded, never committed. Set it before running any class in this
 * package:
 *   export DB_PASSWORD=...          (bash)
 *   $env:DB_PASSWORD = "..."        (PowerShell)
 */
final class CloudSqlConnection {

    private static final String INSTANCE_CONNECTION_NAME =
            "project-3d2fd1eb-6dd8-40b6-958:us-central1:free-trial-first-project";
    private static final String DATABASE = "postgres";
    private static final String DB_USER = "postgres";

    private CloudSqlConnection() {
    }

    static Connection get() throws SQLException {
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "DB_PASSWORD environment variable is not set - required to connect.");
        }

        String jdbcUrl = "jdbc:postgresql:///" + DATABASE;

        Properties props = new Properties();
        props.setProperty("user", DB_USER);
        props.setProperty("password", password);
        props.setProperty("socketFactory", "com.google.cloud.sql.postgres.SocketFactory");
        props.setProperty("cloudSqlInstance", INSTANCE_CONNECTION_NAME);

        return DriverManager.getConnection(jdbcUrl, props);
    }
}
