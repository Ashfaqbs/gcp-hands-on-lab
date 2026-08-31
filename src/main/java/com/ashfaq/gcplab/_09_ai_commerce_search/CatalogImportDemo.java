package com.ashfaq.gcplab._09_ai_commerce_search;

import com.google.cloud.retail.v2.ImportProductsRequest;
import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.ProductInputConfig;
import com.google.cloud.retail.v2.ProductInlineSource;
import com.google.cloud.retail.v2.ProductServiceClient;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsClient;

import java.util.List;

/**
 * Bulk-imports the generated catalog into the Retail API's default_catalog
 * via INLINE import (products passed directly as Java objects in the
 * request) - no GCS staging file needed, unlike a typical production bulk
 * load. Split into batches of 100 (inline import's hard max per request).
 *
 * NOTE: polls the raw Operation and only checks getDone()/getError(),
 * deliberately never unpacking into the typed ImportProductsResponse -
 * this SDK version (1.x google-cloud-retail) has a proto-unpacking bug on
 * that response type (confirmed via REST: the import genuinely succeeds
 * server-side even though the typed future.get() throws client-side).
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.CatalogImportDemo
 */
public final class CatalogImportDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String BRANCH =
            "projects/" + PROJECT_ID + "/locations/global/catalogs/default_catalog/branches/0";
    private static final int BATCH_SIZE = 100; // inline import max per request

    private CatalogImportDemo() {
    }

    public static void main(String[] args) throws Exception {
        List<Product> products = ProductCatalogGenerator.generate();
        System.out.println("Generated " + products.size() + " products.");

        try (ProductServiceClient client = ProductServiceClient.create()) {
            OperationsClient operationsClient = client.getOperationsClient();

            for (int start = 0; start < products.size(); start += BATCH_SIZE) {
                int end = Math.min(start + BATCH_SIZE, products.size());
                List<Product> batch = products.subList(start, end);

                ImportProductsRequest request = ImportProductsRequest.newBuilder()
                        .setParent(BRANCH)
                        .setInputConfig(ProductInputConfig.newBuilder()
                                .setProductInlineSource(ProductInlineSource.newBuilder()
                                        .addAllProducts(batch)
                                        .build())
                                .build())
                        .build();

                System.out.println("Importing batch " + start + "-" + end + " (" + batch.size() + " products)...");
                Operation operation = client.importProductsCallable().call(request);

                while (!operation.getDone()) {
                    Thread.sleep(2000);
                    operation = operationsClient.getOperation(operation.getName());
                }

                if (operation.hasError()) {
                    System.out.println("  BATCH FAILED: " + operation.getError());
                } else {
                    System.out.println("  Batch done.");
                }
            }

            System.out.println("Import complete.");
        }
    }
}
