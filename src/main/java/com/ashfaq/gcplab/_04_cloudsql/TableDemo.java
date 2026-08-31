package com.ashfaq.gcplab._04_cloudsql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies the UI-created "employees" table, and creates a second table
 * ("projects") entirely from code, to show the same DDL operation done
 * both ways - same pattern as role/service account/bucket in earlier
 * modules.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._04_cloudsql.TableDemo -Dexec.args=list
 *   ... -Dexec.args=create
 */
public final class TableDemo {

    private TableDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("list") || args[0].equals("create"))) {
            System.out.println("Usage: TableDemo <list|create>");
            return;
        }

        try (Connection conn = CloudSqlConnection.get()) {
            if (args[0].equals("create")) {
                createProjectsTable(conn);
            }
            listTables(conn);
        }
    }

    private static void createProjectsTable(Connection conn) throws Exception {
        String ddl = """
                CREATE TABLE IF NOT EXISTS projects (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    employee_id INTEGER REFERENCES employees(id),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
        System.out.println("Created table: projects (created programmatically)");
    }

    private static void listTables(Connection conn) throws Exception {
        System.out.println("Tables in public schema:");
        String sql = "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                System.out.println("  - " + rs.getString("tablename"));
            }
        }
    }
}
