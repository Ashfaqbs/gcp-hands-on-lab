package com.ashfaq.gcplab._12_bigquery;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;

import java.util.List;

/**
 * The whole BigQuery lifecycle in one run, using the native client API for
 * the structure (dataset and table) and SQL for the rows:
 *
 *   create dataset -> create table -> insert -> read -> update -> read
 *   -> delete row -> drop table -> drop dataset
 *
 * It uses a throwaway dataset ("lifecycle_demo"), never "learning_bq", so
 * it cannot touch data from the other demos. A finally block drops
 * everything even if a step fails, so nothing is left behind to bill.
 *
 * Why rows use DML (INSERT/UPDATE/DELETE) and not the streaming insertAll
 * API: rows written by streaming sit in a "streaming buffer" and BigQuery
 * refuses UPDATE and DELETE on them for a while (up to about 90 minutes).
 * A demo that inserts and then updates straight away would fail.
 *
 * Run with:
 *   mvn -q exec:java -Dexec.mainClass=com.ashfaq.gcplab._12_bigquery.LifecycleDemo
 */
public final class LifecycleDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String DATASET_ID = "lifecycle_demo";
    private static final String TABLE_ID = "toys";
    private static final String LOCATION = "US";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";
    private static final String TABLE_SQL = "`" + PROJECT_ID + "." + DATASET_ID + "." + TABLE_ID + "`";

    private LifecycleDemo() {
    }

    public static void main(String[] args) throws Exception {
        BigQuery bigquery = newClient();
        try {
            createDataset(bigquery);
            createTable(bigquery);
            insertRow(bigquery);
            readRows(bigquery, "after insert");
            updateRow(bigquery);
            readRows(bigquery, "after update");
            deleteRow(bigquery);
            readRows(bigquery, "after delete");
        } finally {
            cleanup(bigquery);
        }
    }

    private static BigQuery newClient() throws Exception {
        ImpersonatedCredentials impersonated = ImpersonatedCredentials.create(
                GoogleCredentials.getApplicationDefault(),
                SERVICE_ACCOUNT_EMAIL,
                null,
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                300);
        return BigQueryOptions.newBuilder()
                .setProjectId(PROJECT_ID)
                .setCredentials(impersonated)
                .build()
                .getService();
    }

    private static void createDataset(BigQuery bigquery) {
        DatasetInfo info = DatasetInfo.newBuilder(DATASET_ID).setLocation(LOCATION).build();
        Dataset dataset = bigquery.create(info);
        System.out.println("1. Dataset created: " + dataset.getDatasetId().getDataset()
                + " (location " + dataset.getLocation() + ")");
    }

    private static void createTable(BigQuery bigquery) {
        Schema schema = Schema.of(
                Field.newBuilder("toy_id", StandardSQLTypeName.STRING).setMode(Field.Mode.REQUIRED).build(),
                Field.newBuilder("name", StandardSQLTypeName.STRING).setMode(Field.Mode.REQUIRED).build(),
                Field.newBuilder("price", StandardSQLTypeName.NUMERIC).setMode(Field.Mode.NULLABLE).build(),
                Field.newBuilder("tags", StandardSQLTypeName.STRING).setMode(Field.Mode.REPEATED).build());
        TableInfo info = TableInfo.of(TableId.of(DATASET_ID, TABLE_ID), StandardTableDefinition.of(schema));
        Table table = bigquery.create(info);
        System.out.println("2. Table created: " + table.getTableId().getTable()
                + " with " + schema.getFields().size() + " columns (one REPEATED)");
    }

    private static void insertRow(BigQuery bigquery) throws Exception {
        runDml(bigquery, "3. Row inserted", """
                INSERT INTO %s (toy_id, name, price, tags)
                VALUES ('t1', 'Wooden Train', 19.99, ['wood', 'train'])
                """.formatted(TABLE_SQL));
    }

    private static void updateRow(BigQuery bigquery) throws Exception {
        QueryJobConfiguration config = QueryJobConfiguration.newBuilder(
                        "UPDATE %s SET price = @price WHERE toy_id = @id".formatted(TABLE_SQL))
                .addNamedParameter("price", QueryParameterValue.numeric(new java.math.BigDecimal("24.99")))
                .addNamedParameter("id", QueryParameterValue.string("t1"))
                .build();
        bigquery.query(config).iterateAll();
        System.out.println("4. Row updated: price set to 24.99");
    }

    private static void deleteRow(BigQuery bigquery) throws Exception {
        runDml(bigquery, "5. Row deleted", "DELETE FROM %s WHERE toy_id = 't1'".formatted(TABLE_SQL));
    }

    private static void readRows(BigQuery bigquery, String label) throws Exception {
        String sql = "SELECT toy_id, name, price, ARRAY_TO_STRING(tags, ',') AS tags FROM %s".formatted(TABLE_SQL);
        int count = 0;
        for (FieldValueList row : bigquery.query(QueryJobConfiguration.of(sql)).iterateAll()) {
            count++;
            System.out.println("   read (" + label + "): id=" + row.get("toy_id").getStringValue()
                    + " name=" + row.get("name").getStringValue()
                    + " price=" + row.get("price").getNumericValue()
                    + " tags=" + row.get("tags").getStringValue());
        }
        if (count == 0) {
            System.out.println("   read (" + label + "): no rows");
        }
    }

    private static void runDml(BigQuery bigquery, String message, String sql) throws Exception {
        bigquery.query(QueryJobConfiguration.of(sql)).iterateAll();
        System.out.println(message);
    }

    private static void cleanup(BigQuery bigquery) {
        boolean tableDropped = bigquery.delete(TableId.of(DATASET_ID, TABLE_ID));
        System.out.println("6. Table dropped: " + tableDropped);
        // deleteContents is not set, so this fails loudly if anything is still inside the dataset.
        boolean datasetDropped = bigquery.delete(DATASET_ID);
        System.out.println("7. Dataset dropped: " + datasetDropped);
    }
}
