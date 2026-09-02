package com.ashfaq.gcplab._12_bigquery;

import com.ashfaq.gcplab._09_ai_commerce_search.ProductCatalogGenerator;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.retail.v2.Product;

import java.util.List;

/**
 * Answers a direct question with running proof instead of an assertion: a
 * plain SELECT's result is NOT automatically saved anywhere durable - it's
 * returned to the caller (or briefly cached, ~24h, keyed to the exact query
 * text) and then gone. To actually PERSIST a filter+map transformation as a
 * new, permanent, independently-queryable table, the transformation has to
 * be wrapped in {@code CREATE TABLE ... AS SELECT} (CTAS) - this class does
 * exactly that, then proves the result really is a durable table (not just
 * query output) by running a SEPARATE, LATER query against it.
 *
 * The FILTER + MAP being persisted: only Apparel products (WHERE - a
 * filter), with an 18% tax-inclusive price and an uppercased brand name
 * added as new computed columns (SELECT expressions - a map) - the same
 * two SQL operations Flink would call a filter operator and a map operator
 * on a stream, expressed here as one CTAS statement over data already at
 * rest.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._12_bigquery.TransformAndPersistDemo
 */
public final class TransformAndPersistDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String DATASET_ID = "learning_bq";
    private static final String SOURCE_TABLE = "products";
    private static final String TARGET_TABLE = "apparel_with_tax";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";
    private static final double TAX_RATE = 1.18;

    private TransformAndPersistDemo() {
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

        // Expected answer, computed independently in Java from the same source
        // ProductAnalyticsDemo already loaded into the "products" table - the
        // same "verify against an independent calculation" pattern as before.
        List<Product> products = ProductCatalogGenerator.generate();
        long expectedApparelCount = products.stream()
                .filter(p -> !p.getCategoriesList().isEmpty() && p.getCategoriesList().get(0).equals("Apparel"))
                .count();
        System.out.println("Java-computed expected Apparel row count: " + expectedApparelCount);

        // --- The actual filter + map, persisted via CTAS ---
        String ctas = """
                CREATE OR REPLACE TABLE `%s.%s.%s` AS
                SELECT
                  id,
                  title,
                  price AS price_before_tax,
                  ROUND(price * %s, 2) AS price_with_tax,
                  UPPER(brand) AS brand_upper
                FROM `%s.%s.%s`
                WHERE category = 'Apparel'
                """.formatted(PROJECT_ID, DATASET_ID, TARGET_TABLE, TAX_RATE,
                PROJECT_ID, DATASET_ID, SOURCE_TABLE);
        bigquery.query(QueryJobConfiguration.newBuilder(ctas).build()).iterateAll();
        System.out.println("CTAS finished: " + DATASET_ID + "." + TARGET_TABLE + " created.");

        // --- Prove it's really a durable TABLE, not just query output: a
        // completely separate query, run afterward, against the new table. ---
        TableResult check = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT COUNT(*) AS n FROM `%s.%s.%s`"
                        .formatted(PROJECT_ID, DATASET_ID, TARGET_TABLE)).build());
        long actualCount = check.iterateAll().iterator().next().get("n").getLongValue();
        boolean countMatches = actualCount == expectedApparelCount;
        System.out.println((countMatches ? "VERIFIED" : "MISMATCH")
                + ": persisted table row count - expected " + expectedApparelCount
                + ", actual " + actualCount);

        System.out.println("\nFirst 3 rows of the persisted, transformed table:");
        TableResult sample = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT id, title, price_before_tax, price_with_tax, brand_upper FROM `%s.%s.%s` LIMIT 3"
                        .formatted(PROJECT_ID, DATASET_ID, TARGET_TABLE)).build());
        for (FieldValueList row : sample.iterateAll()) {
            System.out.println("  " + row.get("id").getStringValue()
                    + " | " + row.get("title").getStringValue()
                    + " | before_tax=" + row.get("price_before_tax").getDoubleValue()
                    + " | with_tax=" + row.get("price_with_tax").getDoubleValue()
                    + " | brand=" + row.get("brand_upper").getStringValue());
        }

        if (!countMatches) {
            throw new IllegalStateException("Verification failed - see MISMATCH line above.");
        }
        System.out.println("\nConfirmed: the filtered+mapped result is a real, separately-queryable "
                + "table (" + DATASET_ID + "." + TARGET_TABLE + "), not ephemeral query output.");
    }
}
