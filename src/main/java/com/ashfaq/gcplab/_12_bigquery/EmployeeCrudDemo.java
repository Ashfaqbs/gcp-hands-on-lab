package com.ashfaq.gcplab._12_bigquery;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;

import java.util.List;

/**
 * CRUD against the "employees" table - IMPERSONATING backend-dev-sa, same
 * identity pattern as every other data-plane demo in this repo.
 *
 * The deliberate teaching point of this class: every operation below -
 * INSERT, SELECT, UPDATE, DELETE - goes through the EXACT SAME
 * {@code bigquery.query(...)} call. There is no separate "write path" vs.
 * "read path" client, no Mutation type (contrast Spanner's
 * {@code EmployeeCrudDemo} in _11_spanner, which has a distinct Mutation
 * API for point writes plus a separate DML-in-a-transaction path), and no
 * ORM-style row object - everything is a SQL string submitted as a JOB.
 * DML in BigQuery works, but is NOT meant for high-frequency single-row
 * OLTP-style updates (see this package's package-info.java "Internal
 * architecture" and "Production practices" sections for why) - it's shown
 * here specifically to prove it works and to make the contrast with a real
 * OLTP database (Cloud SQL, Spanner) concrete, not as a recommended pattern
 * for production row-level mutation traffic.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._12_bigquery.EmployeeCrudDemo -Dexec.args=create
 *   ... -Dexec.args=read
 *   ... -Dexec.args=update
 *   ... -Dexec.args=delete
 */
public final class EmployeeCrudDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String DATASET_ID = "learning_bq";
    private static final String TABLE = "`" + PROJECT_ID + "." + DATASET_ID + ".employees`";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";
    // Fixed ID (not random) so read/update/delete in separate JVM runs target the same row.
    private static final String EMPLOYEE_ID = "11111111-1111-1111-1111-111111111111";

    private EmployeeCrudDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("create", "read", "update", "delete").contains(args[0])) {
            System.out.println("Usage: EmployeeCrudDemo <create|read|update|delete>");
            return;
        }

        GoogleCredentials sourceCredentials = GoogleCredentials.getApplicationDefault();
        ImpersonatedCredentials impersonated = ImpersonatedCredentials.create(
                sourceCredentials,
                SERVICE_ACCOUNT_EMAIL,
                null,
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                300);

        BigQuery bigquery = BigQueryOptions.newBuilder()
                .setProjectId(PROJECT_ID)
                .setCredentials(impersonated)
                .build()
                .getService();

        switch (args[0]) {
            case "create" -> create(bigquery);
            case "read" -> read(bigquery);
            case "update" -> update(bigquery);
            case "delete" -> delete(bigquery);
            default -> throw new IllegalStateException("unreachable");
        }
    }

    private static void create(BigQuery bigquery) throws Exception {
        String sql = """
                INSERT INTO %s (employee_id, name, role, created_at)
                VALUES (@id, @name, @role, CURRENT_TIMESTAMP())
                """.formatted(TABLE);
        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("id", QueryParameterValue.string(EMPLOYEE_ID))
                .addNamedParameter("name", QueryParameterValue.string("Ashfaq"))
                .addNamedParameter("role", QueryParameterValue.string("Backend Developer"))
                .build();
        bigquery.query(config).iterateAll();
        System.out.println("Inserted employee " + EMPLOYEE_ID);
    }

    private static void read(BigQuery bigquery) throws Exception {
        String sql = "SELECT employee_id, name, role, created_at FROM %s WHERE employee_id = @id".formatted(TABLE);
        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("id", QueryParameterValue.string(EMPLOYEE_ID))
                .build();
        TableResult result = bigquery.query(config);
        if (result.getTotalRows() == 0) {
            System.out.println("No employee found with id " + EMPLOYEE_ID);
            return;
        }
        for (FieldValueList row : result.iterateAll()) {
            System.out.println("Found: id=" + row.get("employee_id").getStringValue()
                    + " name=" + row.get("name").getStringValue()
                    + " role=" + row.get("role").getStringValue()
                    + " created_at=" + row.get("created_at").getTimestampInstant());
        }
    }

    private static void update(BigQuery bigquery) throws Exception {
        String sql = "UPDATE %s SET role = @role WHERE employee_id = @id".formatted(TABLE);
        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("role", QueryParameterValue.string("Senior Backend Developer"))
                .addNamedParameter("id", QueryParameterValue.string(EMPLOYEE_ID))
                .build();
        TableResult result = bigquery.query(config);
        System.out.println("Update job finished, affected rows reported via job statistics.");
        result.iterateAll();
    }

    private static void delete(BigQuery bigquery) throws Exception {
        String sql = "DELETE FROM %s WHERE employee_id = @id".formatted(TABLE);
        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("id", QueryParameterValue.string(EMPLOYEE_ID))
                .build();
        bigquery.query(config).iterateAll();
        System.out.println("Deleted employee " + EMPLOYEE_ID);
    }
}
