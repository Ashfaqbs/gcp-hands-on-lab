package com.ashfaq.gcplab._04_cloudsql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * Full CRUD against the "employees" table created via Cloud SQL Studio.
 * Uses PreparedStatement everywhere (parameterized queries) - never string
 * concatenation, per this project's security rules, even in a throwaway
 * learning demo.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._04_cloudsql.EmployeeCrudDemo -Dexec.args=create
 *   ... -Dexec.args=list
 *   ... -Dexec.args=update
 *   ... -Dexec.args=delete
 */
public final class EmployeeCrudDemo {

    private static final List<String> VALID_ACTIONS = List.of("create", "list", "update", "delete");

    private EmployeeCrudDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !VALID_ACTIONS.contains(args[0])) {
            System.out.println("Usage: EmployeeCrudDemo <create|list|update|delete>");
            return;
        }

        try (Connection conn = CloudSqlConnection.get()) {
            switch (args[0]) {
                case "create" -> create(conn);
                case "list" -> list(conn);
                case "update" -> update(conn);
                case "delete" -> delete(conn);
                default -> throw new IllegalStateException("unreachable");
            }
        }
    }

    private static void create(Connection conn) throws Exception {
        String sql = "INSERT INTO employees (name, role) VALUES (?, ?) RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "Ashfaq");
            ps.setString(2, "Backend Developer");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                System.out.println("Inserted employee with id: " + rs.getInt("id"));
            }
        }
    }

    private static void list(Connection conn) throws Exception {
        String sql = "SELECT id, name, role, created_at FROM employees ORDER BY id";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            System.out.println("Employees:");
            while (rs.next()) {
                System.out.printf("  [%d] %s - %s (created %s)%n",
                        rs.getInt("id"), rs.getString("name"), rs.getString("role"),
                        rs.getTimestamp("created_at"));
            }
        }
    }

    private static void update(Connection conn) throws Exception {
        String sql = "UPDATE employees SET role = ? WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "Senior Backend Developer");
            ps.setString(2, "Ashfaq");
            int updated = ps.executeUpdate();
            System.out.println("Updated " + updated + " row(s)");
        }
    }

    private static void delete(Connection conn) throws Exception {
        String sql = "DELETE FROM employees WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "Ashfaq");
            int deleted = ps.executeUpdate();
            System.out.println("Deleted " + deleted + " row(s)");
        }
    }
}
