package com.ashfaq.gcplab._09_ai_commerce_search;

import com.google.cloud.retail.v2.ProductServiceClient;
import com.google.cloud.retail.v2.PurgeProductsRequest;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsClient;

/**
 * Bulk-deletes every product in the catalog via PurgeProducts (filter "*"
 * = everything), instead of 703 individual DeleteProduct calls. Same raw
 * Operation polling pattern as CatalogImportDemo - avoids the same
 * proto-unpacking bug on this SDK version.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.CatalogPurgeDemo
 */
public final class CatalogPurgeDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String BRANCH =
            "projects/" + PROJECT_ID + "/locations/global/catalogs/default_catalog/branches/0";

    private CatalogPurgeDemo() {
    }

    public static void main(String[] args) throws Exception {
        try (ProductServiceClient client = ProductServiceClient.create()) {
            PurgeProductsRequest request = PurgeProductsRequest.newBuilder()
                    .setParent(BRANCH)
                    .setFilter("*")
                    .setForce(true)
                    .build();

            System.out.println("Submitting purge (deletes all products in " + BRANCH + ")...");
            Operation operation = client.purgeProductsCallable().call(request);

            OperationsClient operationsClient = client.getOperationsClient();
            while (!operation.getDone()) {
                Thread.sleep(2000);
                operation = operationsClient.getOperation(operation.getName());
            }

            if (operation.hasError()) {
                System.out.println("PURGE FAILED: " + operation.getError());
            } else {
                System.out.println("Purge complete.");
            }
        }
    }
}
