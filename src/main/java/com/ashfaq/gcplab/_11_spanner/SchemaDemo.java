package com.ashfaq.gcplab._11_spanner;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Creates the "employees" table via DDL against an already-existing Spanner
 * instance/database (instance/database creation itself is a Console/gcloud
 * step, same "infra via UI, code for what runs on top" split as
 * _04_cloudsql's Cloud SQL instance / _05_redis's Memorystore instance).
 *
 * DDL in Spanner is its own async long-running operation, same pattern as
 * _09_ai_commerce_search's catalog import - submit, then poll until done,
 * never assume synchronous completion.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._11_spanner.SchemaDemo -Dexec.args=create
 *   ... -Dexec.args=drop
 */
public final class SchemaDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String INSTANCE_ID = "learning-spanner";
    private static final String DATABASE_ID = "learning-db";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";

    private SchemaDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("create", "drop").contains(args[0])) {
            System.out.println("Usage: SchemaDemo <create|drop>");
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
            DatabaseAdminClient adminClient = spanner.getDatabaseAdminClient();

            if (args[0].equals("create")) {
                adminClient.updateDatabaseDdl(
                        INSTANCE_ID, DATABASE_ID,
                        List.of("""
                                CREATE TABLE employees (
                                  employee_id STRING(36) NOT NULL,
                                  name STRING(200) NOT NULL,
                                  role STRING(200),
                                  created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp = true)
                                ) PRIMARY KEY (employee_id)
                                """),
                        null).get(5, TimeUnit.MINUTES);
                System.out.println("Table created.");
            } else {
                adminClient.updateDatabaseDdl(
                        INSTANCE_ID, DATABASE_ID,
                        List.of("DROP TABLE employees"),
                        null).get(5, TimeUnit.MINUTES);
                System.out.println("Table dropped.");
            }
        }
    }
}
