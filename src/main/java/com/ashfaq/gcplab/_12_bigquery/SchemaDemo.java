package com.ashfaq.gcplab._12_bigquery;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;

import java.util.List;

/**
 * Creates the dataset and table via DDL - run as ordinary SQL through the
 * SAME {@code bigquery.query(...)} call every other operation in this
 * module uses (see {@link EmployeeCrudDemo}). Unlike Cloud SQL (a JDBC DDL
 * statement) or Spanner ({@code DatabaseAdminClient.updateDatabaseDdl}, its
 * own separate admin API/client), BigQuery has no separate "admin client"
 * for schema changes at all - CREATE SCHEMA and CREATE TABLE are just SQL
 * statements submitted as a query job, identical in shape to a SELECT.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._12_bigquery.SchemaDemo -Dexec.args=create
 *   ... -Dexec.args=drop
 */
public final class SchemaDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String DATASET_ID = "learning_bq";
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

        BigQuery bigquery = BigQueryOptions.newBuilder()
                .setProjectId(PROJECT_ID)
                .setCredentials(impersonated)
                .build()
                .getService();

        if (args[0].equals("create")) {
            run(bigquery, """
                    CREATE SCHEMA IF NOT EXISTS `%s.%s`
                    OPTIONS (location = 'US')
                    """.formatted(PROJECT_ID, DATASET_ID));
            System.out.println("Dataset created: " + DATASET_ID);

            run(bigquery, """
                    CREATE TABLE IF NOT EXISTS `%s.%s.employees` (
                      employee_id STRING NOT NULL,
                      name STRING NOT NULL,
                      role STRING,
                      created_at TIMESTAMP NOT NULL
                    )
                    """.formatted(PROJECT_ID, DATASET_ID));
            System.out.println("Table created: employees");
        } else {
            run(bigquery, "DROP TABLE IF EXISTS `%s.%s.employees`".formatted(PROJECT_ID, DATASET_ID));
            run(bigquery, "DROP SCHEMA IF EXISTS `%s.%s`".formatted(PROJECT_ID, DATASET_ID));
            System.out.println("Dataset and table dropped.");
        }
    }

    private static void run(BigQuery bigquery, String sql) throws Exception {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(sql).build());
        // DDL statements return an empty result set - iterating just confirms the job finished.
        result.iterateAll();
    }
}
