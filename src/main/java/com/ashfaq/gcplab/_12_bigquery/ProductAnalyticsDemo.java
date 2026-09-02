package com.ashfaq.gcplab._12_bigquery;

import com.ashfaq.gcplab._09_ai_commerce_search.ProductCatalogGenerator;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.FormatOptions;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.TableDataWriteChannel;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.bigquery.WriteChannelConfiguration;
import com.google.cloud.retail.v2.CustomAttribute;
import com.google.cloud.retail.v2.Product;

import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The real end-to-end BigQuery pattern this package's package-info.java
 * describes but {@link SchemaDemo}/{@link EmployeeCrudDemo}'s single-row
 * CRUD demo doesn't show: take real data (reusing the exact same 703-
 * product synthetic catalog from {@code _09_ai_commerce_search}, the same
 * apples-to-apples reuse pattern {@code _10_elasticsearch} already
 * established), MASSAGE it (flatten each retail-API {@code Product} proto
 * into a plain row shape - id/title/category/brand/price/attribute),
 * PERSIST it via a real bulk LOAD JOB (not 703 individual DML INSERTs,
 * which would be both slow and the exact anti-pattern called out in this
 * package's Production practices section), then VERIFY the whole
 * simulation IN CODE - not by eyeballing printed output, but by computing
 * expected aggregates independently in Java BEFORE the load, then asserting
 * BigQuery's query results actually match after the load, printing an
 * explicit PASS/FAIL per check.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._12_bigquery.ProductAnalyticsDemo
 */
public final class ProductAnalyticsDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String DATASET_ID = "learning_bq";
    private static final String TABLE_NAME = "products";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";

    private ProductAnalyticsDemo() {
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

        // --- 1. TAKE DATA: reuse the exact same generator _09/_10 already use ---
        List<Product> products = ProductCatalogGenerator.generate();
        System.out.println("Generated " + products.size() + " products (source of truth for this run).");

        // --- Compute expected results independently in Java, BEFORE touching BigQuery ---
        // This is the actual verification anchor - if BigQuery's answer doesn't match
        // what plain Java already knows is true about this exact data, something in the
        // load/query pipeline is broken, not the data itself.
        double expectedMaxPrice = 0.0;
        String expectedMaxPriceProductId = "";
        for (Product p : products) {
            double price = p.getPriceInfo().getPrice();
            if (price > expectedMaxPrice) {
                expectedMaxPrice = price;
                expectedMaxPriceProductId = p.getId();
            }
        }
        int expectedRowCount = products.size();

        // --- 2. PERSIST: create the dataset/table, then a real bulk LOAD JOB ---
        ensureDataset(bigquery);
        TableId tableId = TableId.of(PROJECT_ID, DATASET_ID, TABLE_NAME);
        Schema schema = Schema.of(
                Field.of("id", StandardSQLTypeName.STRING),
                Field.of("title", StandardSQLTypeName.STRING),
                Field.of("category", StandardSQLTypeName.STRING),
                Field.of("brand", StandardSQLTypeName.STRING),
                Field.of("price", StandardSQLTypeName.FLOAT64),
                Field.of("attribute_key", StandardSQLTypeName.STRING),
                Field.of("attribute_value", StandardSQLTypeName.STRING));

        WriteChannelConfiguration writeConfig = WriteChannelConfiguration.newBuilder(tableId)
                .setFormatOptions(FormatOptions.json())
                .setSchema(schema)
                .setWriteDisposition(JobInfo.WriteDisposition.WRITE_TRUNCATE)
                .build();

        TableDataWriteChannel writer = bigquery.writer(writeConfig);
        try (OutputStream out = Channels.newOutputStream(writer)) {
            for (Product p : products) {
                out.write(toNdjsonLine(p).getBytes(StandardCharsets.UTF_8));
            }
        }
        Job loadJob = writer.getJob().waitFor();
        if (loadJob.getStatus().getError() != null) {
            throw new IllegalStateException("Load job failed: " + loadJob.getStatus().getError());
        }
        System.out.println("Load job finished: " + products.size() + " products persisted to "
                + DATASET_ID + "." + TABLE_NAME);

        // --- 3. MASSAGE + VERIFY: run real analytical SQL, check it against Java's own answer ---
        long actualRowCount = queryScalarLong(bigquery,
                "SELECT COUNT(*) AS n FROM `%s.%s.%s`".formatted(PROJECT_ID, DATASET_ID, TABLE_NAME), "n");
        boolean countMatches = actualRowCount == expectedRowCount;
        System.out.println((countMatches ? "VERIFIED" : "MISMATCH")
                + ": row count - expected " + expectedRowCount + ", BigQuery reports " + actualRowCount);

        TableResult maxPriceResult = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT id, price FROM `%s.%s.%s` ORDER BY price DESC LIMIT 1"
                        .formatted(PROJECT_ID, DATASET_ID, TABLE_NAME)).build());
        FieldValueList topRow = maxPriceResult.iterateAll().iterator().next();
        String actualMaxPriceProductId = topRow.get("id").getStringValue();
        double actualMaxPrice = topRow.get("price").getDoubleValue();
        boolean maxPriceMatches = actualMaxPriceProductId.equals(expectedMaxPriceProductId)
                && Math.abs(actualMaxPrice - expectedMaxPrice) < 0.001;
        System.out.println((maxPriceMatches ? "VERIFIED" : "MISMATCH")
                + ": highest-priced product - expected " + expectedMaxPriceProductId + " at " + expectedMaxPrice
                + ", BigQuery reports " + actualMaxPriceProductId + " at " + actualMaxPrice);

        // The actual "massage" step - an aggregation no single row could answer,
        // the reason this data belongs in a warehouse at all.
        System.out.println("\nAverage price and product count by category:");
        TableResult byCategory = bigquery.query(QueryJobConfiguration.newBuilder("""
                SELECT category, COUNT(*) AS product_count, ROUND(AVG(price), 2) AS avg_price
                FROM `%s.%s.%s`
                GROUP BY category
                ORDER BY product_count DESC
                """.formatted(PROJECT_ID, DATASET_ID, TABLE_NAME)).build());
        for (FieldValueList row : byCategory.iterateAll()) {
            System.out.println("  " + row.get("category").getStringValue()
                    + " - " + row.get("product_count").getLongValue() + " products, avg price "
                    + row.get("avg_price").getDoubleValue());
        }

        if (!countMatches || !maxPriceMatches) {
            throw new IllegalStateException("Verification failed - see MISMATCH lines above.");
        }
        System.out.println("\nAll verification checks passed.");
    }

    private static void ensureDataset(BigQuery bigquery) throws Exception {
        bigquery.query(QueryJobConfiguration.newBuilder(
                "CREATE SCHEMA IF NOT EXISTS `%s.%s` OPTIONS (location = 'US')"
                        .formatted(PROJECT_ID, DATASET_ID)).build()).iterateAll();
    }

    private static long queryScalarLong(BigQuery bigquery, String sql, String column) throws Exception {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(sql).build());
        return result.iterateAll().iterator().next().get(column).getLongValue();
    }

    private static String toNdjsonLine(Product p) {
        String attributeKey = "";
        String attributeValue = "";
        for (Map.Entry<String, CustomAttribute> e : p.getAttributesMap().entrySet()) {
            if (!e.getValue().getTextList().isEmpty()) {
                attributeKey = e.getKey();
                attributeValue = e.getValue().getText(0);
            }
        }
        String category = p.getCategoriesList().isEmpty() ? "" : p.getCategoriesList().get(0);
        String brand = p.getBrandsList().isEmpty() ? "" : p.getBrandsList().get(0);

        return "{"
                + "\"id\":" + json(p.getId()) + ","
                + "\"title\":" + json(p.getTitle()) + ","
                + "\"category\":" + json(category) + ","
                + "\"brand\":" + json(brand) + ","
                + "\"price\":" + p.getPriceInfo().getPrice() + ","
                + "\"attribute_key\":" + json(attributeKey) + ","
                + "\"attribute_value\":" + json(attributeValue)
                + "}\n";
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
