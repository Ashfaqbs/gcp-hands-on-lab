package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import com.google.cloud.retail.v2.ImportProductsRequest;
import com.google.cloud.retail.v2.ProductInlineSource;
import com.google.cloud.retail.v2.ProductInputConfig;
import com.google.cloud.retail.v2.ProductServiceClient;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsClient;

/**
 * Step 1: bulk-import the 20 toys into branch 0 (inline import).
 * Branch 0 should be purged first (CatalogPurgeDemo) so only toys remain.
 *
 * Run: mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.toystore.ToyUploadDemo
 */
public final class ToyUploadDemo {

    private ToyUploadDemo() {
    }

    public static void main(String[] args) throws Exception {
        try (ProductServiceClient client = ProductServiceClient.create()) {
            ImportProductsRequest request = ImportProductsRequest.newBuilder()
                    .setParent(ToyCatalog.BRANCH)
                    .setInputConfig(ProductInputConfig.newBuilder()
                            .setProductInlineSource(ProductInlineSource.newBuilder()
                                    .addAllProducts(ToyCatalog.all()).build())
                            .build())
                    .build();

            System.out.println("Importing " + ToyCatalog.all().size() + " toys into " + ToyCatalog.BRANCH);
            Operation operation = client.importProductsCallable().call(request);
            OperationsClient operations = client.getOperationsClient();
            while (!operation.getDone()) {
                Thread.sleep(2000);
                operation = operations.getOperation(operation.getName());
            }
            System.out.println(operation.hasError() ? "IMPORT FAILED: " + operation.getError() : "Import done.");
        }
    }
}
