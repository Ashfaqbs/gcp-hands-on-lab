package com.ashfaq.gcplab._12_bigquery;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.JobStatus;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;

import java.util.List;
import java.util.UUID;

/**
 * Every operation every other class in this package has run -
 * {@link SchemaDemo}'s DDL, {@link EmployeeCrudDemo}'s DML,
 * {@link ProductAnalyticsDemo}'s load, {@link TransformAndPersistDemo}'s
 * CTAS - was ALREADY a {@link Job} underneath the convenience
 * {@code bigquery.query(...)} call, which creates a Job with an
 * auto-generated ID, submits it, and polls it to completion internally.
 * This class does the exact same thing with the auto-generated parts made
 * explicit: a real, developer-assigned {@link JobId}, manual submission,
 * manual polling of {@link JobStatus.State} (PENDING -&gt; RUNNING -&gt;
 * DONE), and inspection of the job's own statistics (bytes processed, a
 * concrete number rather than an abstract "should be small") - the
 * mechanics that were always running, just hidden behind a helper method
 * until now.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._12_bigquery.JobDemo
 */
public final class JobDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";

    private JobDemo() {
    }

    public static void main(String[] args) throws Exception {
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

        // --- 1. CREATE: a developer-assigned JobId, not an auto-generated one -
        // this is what "create a job" means at the BigQuery API level: a Job is a
        // request to run one specific operation (here, a query), identified by
        // this ID for the rest of its lifecycle. -->
        JobId jobId = JobId.newBuilder()
                .setJob("learning-bq-job-demo-" + UUID.randomUUID())
                .build();
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(
                        "SELECT 703 AS product_count, 'learning_bq' AS dataset_name")
                .build();

        // --- 2. TRIGGER FROM CODE: submit the job. This call returns immediately
        // with the job in PENDING/RUNNING state - it does NOT block until done. -->
        Job job = bigquery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());
        System.out.println("Job created: " + job.getJobId().getJob()
                + " | initial state: " + job.getStatus().getState());

        // --- 3. VERIFY (part 1) - poll the job's state manually instead of a
        // one-line waitFor(), to show what waitFor() is actually doing underneath. -->
        while (job.getStatus().getState() != JobStatus.State.DONE) {
            Thread.sleep(500);
            job = job.reload();
            System.out.println("Polled state: " + job.getStatus().getState());
        }

        if (job.getStatus().getError() != null) {
            throw new IllegalStateException("Job failed: " + job.getStatus().getError());
        }

        // --- 4. VERIFY (part 2) - the job carries its own statistics, independent
        // of the query result itself: how many bytes it actually processed, when
        // it started/ended. This is the SAME data Console's "Job history" page
        // (see package-info.java's UI walkthrough) shows for every job ever run. -->
        JobStatistics.QueryStatistics stats = job.getStatistics();
        System.out.println("Job finished. Bytes processed: " + stats.getTotalBytesProcessed()
                + " | Total slot-ms: " + stats.getTotalSlotMs()
                + " | Cache hit: " + stats.getCacheHit());

        // Fetch the actual query result via the now-finished job's own handle -
        // proving getQueryResults() and bigquery.query()'s hidden internals are
        // the same underlying call.
        TableResult result = job.getQueryResults();
        result.iterateAll().forEach(row ->
                System.out.println("Result row: product_count=" + row.get("product_count").getLongValue()
                        + " dataset_name=" + row.get("dataset_name").getStringValue()));

        // --- 5. Fetching the SAME job again by ID, independently - proves a Job
        // is a real, addressable resource (like any GCP resource), not just a
        // local object this process happened to be holding. -->
        Job refetched = bigquery.getJob(jobId);
        System.out.println("Re-fetched job " + refetched.getJobId().getJob()
                + " by ID alone - state: " + refetched.getStatus().getState()
                + " (confirms Jobs are addressable GCP resources, not local-only handles)");
    }
}
