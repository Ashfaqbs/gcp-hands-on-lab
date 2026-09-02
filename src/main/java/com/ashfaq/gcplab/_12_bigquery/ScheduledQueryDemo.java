package com.ashfaq.gcplab._12_bigquery;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.bigquery.datatransfer.v1.CreateTransferConfigRequest;
import com.google.cloud.bigquery.datatransfer.v1.DataTransferServiceClient;
import com.google.cloud.bigquery.datatransfer.v1.DataTransferServiceSettings;
import com.google.cloud.bigquery.datatransfer.v1.DeleteTransferConfigRequest;
import com.google.cloud.bigquery.datatransfer.v1.ProjectName;
import com.google.cloud.bigquery.datatransfer.v1.ScheduleOptions;
import com.google.cloud.bigquery.datatransfer.v1.StartManualTransferRunsRequest;
import com.google.cloud.bigquery.datatransfer.v1.StartManualTransferRunsResponse;
import com.google.cloud.bigquery.datatransfer.v1.TransferConfig;
import com.google.cloud.bigquery.datatransfer.v1.TransferRun;
import com.google.cloud.bigquery.datatransfer.v1.TransferState;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The OTHER thing BigQuery calls a "job" - not the per-operation
 * {@link Job} object {@link JobDemo} demonstrates (created implicitly by
 * every query/load/DDL statement, one-shot, torn down the moment it's
 * done), but a SCHEDULED QUERY: a named, persistent, reusable job
 * DEFINITION (technically a BigQuery Data Transfer Service
 * {@code TransferConfig}) that can be triggered on a cron-like schedule OR
 * on demand from code - much closer to what "create a job, trigger it,
 * verify it ran" usually means outside BigQuery's own per-query Job
 * abstraction, and the API-driven equivalent of Console's "Scheduled
 * queries" page.
 *
 * This demo creates a scheduled query wrapping the SAME Apparel-with-tax
 * CTAS transform {@link TransformAndPersistDemo} runs directly, but with
 * automatic scheduling explicitly DISABLED (on-demand only) - deliberately,
 * so nothing keeps re-running or costing anything after this process exits
 * even if cleanup were somehow skipped - then triggers ONE manual run of it
 * immediately via code, polls the run to completion, and verifies the
 * output table the run produced.
 *
 * Requires {@link ProductAnalyticsDemo} to have been run first (this demo's
 * scheduled query reads from the {@code products} table it loads).
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._12_bigquery.ScheduledQueryDemo
 */
public final class ScheduledQueryDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String DATASET_ID = "learning_bq";
    private static final String TARGET_TABLE = "apparel_scheduled_demo";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";

    private ScheduledQueryDemo() {
    }

    public static void main(String[] args) throws Exception {
        GoogleCredentials sourceCredentials = GoogleCredentials.getApplicationDefault();
        ImpersonatedCredentials impersonated = ImpersonatedCredentials.create(
                sourceCredentials,
                SERVICE_ACCOUNT_EMAIL,
                null,
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                300);

        DataTransferServiceSettings dtsSettings = DataTransferServiceSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(impersonated))
                .build();

        String query = """
                CREATE OR REPLACE TABLE `%s.%s.%s` AS
                SELECT id, title, ROUND(price * 1.18, 2) AS price_with_tax
                FROM `%s.%s.products`
                WHERE category = 'Apparel'
                """.formatted(PROJECT_ID, DATASET_ID, TARGET_TABLE, PROJECT_ID, DATASET_ID);

        try (DataTransferServiceClient client = DataTransferServiceClient.create(dtsSettings)) {
            // --- 1. CREATE the job DEFINITION - a persistent, named, reusable
            // resource, NOT a one-shot Job. disableAutoScheduling(true) means it
            // only ever runs when explicitly triggered, never on a cron - the
            // safe choice for something a learning session creates and tears down
            // in the same run. ---
            TransferConfig config = TransferConfig.newBuilder()
                    .setDisplayName("learning-bq-scheduled-query-demo")
                    .setDataSourceId("scheduled_query")
                    .setDestinationDatasetId(DATASET_ID)
                    .setSchedule("every 24 hours")
                    .setScheduleOptions(ScheduleOptions.newBuilder().setDisableAutoScheduling(true).build())
                    .setParams(Struct.newBuilder()
                            .putFields("query", Value.newBuilder().setStringValue(query).build())
                            .build())
                    .build();

            TransferConfig created = client.createTransferConfig(CreateTransferConfigRequest.newBuilder()
                    .setParent(ProjectName.of(PROJECT_ID).toString())
                    .setTransferConfig(config)
                    .build());
            System.out.println("Scheduled query DEFINITION created: " + created.getName()
                    + " (auto-scheduling disabled - on-demand only)");

            try {
                // --- 2. TRIGGER FROM CODE - a manual, immediate run of that
                // definition, right now, without waiting for any schedule. ---
                StartManualTransferRunsResponse startResponse = client.startManualTransferRuns(
                        StartManualTransferRunsRequest.newBuilder()
                                .setParent(created.getName())
                                .setRequestedRunTime(Timestamp.newBuilder()
                                        .setSeconds(Instant.now().getEpochSecond())
                                        .build())
                                .build());
                TransferRun run = startResponse.getRuns(0);
                System.out.println("Manual run triggered: " + run.getName()
                        + " | initial state: " + run.getState());

                // --- 3. VERIFY (part 1) - poll the run until it leaves the
                // PENDING/RUNNING states, same polling shape as JobDemo's Job
                // polling loop, just for a TransferRun instead of a Job. ---
                while (run.getState() == TransferState.PENDING || run.getState() == TransferState.RUNNING) {
                    Thread.sleep(2000);
                    run = client.getTransferRun(run.getName());
                    System.out.println("Polled run state: " + run.getState());
                }

                if (run.getState() != TransferState.SUCCEEDED) {
                    throw new IllegalStateException("Scheduled query run did not succeed: "
                            + run.getState() + " - " + run.getErrorStatus());
                }
                System.out.println("Run SUCCEEDED.");

                // --- 4. VERIFY (part 2) - the run claims success; independently
                // confirm the table it was supposed to produce actually has the
                // expected data, the same "don't trust the job status alone"
                // discipline as every other verification in this package. ---
                BigQuery bigquery = BigQueryOptions.newBuilder()
                        .setProjectId(PROJECT_ID)
                        .setCredentials(impersonated)
                        .build()
                        .getService();
                TableResult check = bigquery.query(QueryJobConfiguration.newBuilder(
                        "SELECT COUNT(*) AS n FROM `%s.%s.%s`"
                                .formatted(PROJECT_ID, DATASET_ID, TARGET_TABLE)).build());
                long rowCount = check.iterateAll().iterator().next().get("n").getLongValue();
                System.out.println((rowCount == 200 ? "VERIFIED" : "MISMATCH")
                        + ": scheduled query's output table has " + rowCount + " rows (expected 200 Apparel products)");

                if (rowCount != 200) {
                    throw new IllegalStateException("Output table row count did not match expectation.");
                }
            } finally {
                // --- 5. Clean up the job DEFINITION itself, independent of the
                // data it produced (that's dropped separately with the dataset). ---
                client.deleteTransferConfig(DeleteTransferConfigRequest.newBuilder()
                        .setName(created.getName())
                        .build());
                System.out.println("Scheduled query definition deleted: " + created.getName());
            }
        }
    }
}
