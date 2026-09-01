package com.ashfaq.gcplab._11_spanner;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Value;

import java.util.List;
import java.util.UUID;

/**
 * CRUD against the "employees" table - IMPERSONATING backend-dev-sa, same
 * identity pattern as every other data-plane demo in this repo (ObjectDemo
 * in _03_storage, EmployeeDocCrudDemo in _06_firestore). Requires
 * backendDeveloper to hold spanner.databases.select/spanner.data.* (a
 * DIFFERENT permission family from cloudsql.instances.connect in
 * _04_cloudsql - Spanner's own IAM permission set, added via UpdateRoleDemo
 * the same way Firestore's datastore.entities.* was added in _06).
 *
 * Two distinct write paths shown deliberately: a MUTATION (create, delete -
 * a key/value-shaped write, Spanner's own primitive, similar in spirit to a
 * Bigtable/NoSQL put) and DML via a read-write TRANSACTION (update - real
 * SQL UPDATE ... WHERE, inside an explicit transaction) - both are valid,
 * mutations are typically cheaper/simpler for single-row point writes, DML
 * is what you reach for when the write depends on a read or touches
 * multiple rows conditionally.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._11_spanner.EmployeeCrudDemo -Dexec.args=create
 *   ... -Dexec.args=read
 *   ... -Dexec.args=update
 *   ... -Dexec.args=delete
 */
public final class EmployeeCrudDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String INSTANCE_ID = "learning-spanner";
    private static final String DATABASE_ID = "learning-db";
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

        SpannerOptions options = SpannerOptions.newBuilder()
                .setProjectId(PROJECT_ID)
                .setCredentials(impersonated)
                .build();

        try (Spanner spanner = options.getService()) {
            DatabaseClient client = spanner.getDatabaseClient(
                    DatabaseId.of(PROJECT_ID, INSTANCE_ID, DATABASE_ID));

            switch (args[0]) {
                case "create" -> create(client);
                case "read" -> read(client);
                case "update" -> update(client);
                case "delete" -> delete(client);
                default -> throw new IllegalStateException("unreachable");
            }
        }
    }

    private static void create(DatabaseClient client) {
        Mutation insert = Mutation.newInsertBuilder("employees")
                .set("employee_id").to(EMPLOYEE_ID)
                .set("name").to("Ashfaq")
                .set("role").to("Backend Developer")
                .set("created_at").to(Value.COMMIT_TIMESTAMP)
                .build();
        client.write(List.of(insert));
        System.out.println("Inserted employee " + EMPLOYEE_ID);
    }

    private static void read(DatabaseClient client) {
        Struct row = client.singleUse().readRow("employees", Key.of(EMPLOYEE_ID),
                List.of("employee_id", "name", "role", "created_at"));
        if (row == null) {
            System.out.println("No employee found with id " + EMPLOYEE_ID);
            return;
        }
        System.out.println("Found: id=" + row.getString("employee_id")
                + " name=" + row.getString("name")
                + " role=" + row.getString("role")
                + " created_at=" + row.getTimestamp("created_at"));
    }

    private static void update(DatabaseClient client) {
        // DML via an explicit read-write transaction - real SQL, transactionally safe.
        client.readWriteTransaction().run(txn -> {
            long rows = txn.executeUpdate(Statement.newBuilder(
                            "UPDATE employees SET role = @role WHERE employee_id = @id")
                    .bind("role").to("Senior Backend Developer")
                    .bind("id").to(EMPLOYEE_ID)
                    .build());
            System.out.println("Rows updated: " + rows);
            return null;
        });
    }

    private static void delete(DatabaseClient client) {
        Mutation delete = Mutation.delete("employees", Key.of(EMPLOYEE_ID));
        client.write(List.of(delete));
        System.out.println("Deleted employee " + EMPLOYEE_ID);
    }
}
