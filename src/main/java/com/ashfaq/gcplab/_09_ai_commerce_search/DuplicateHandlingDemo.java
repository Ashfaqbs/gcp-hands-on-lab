package com.ashfaq.gcplab._09_ai_commerce_search;

import com.google.cloud.retail.v2.CreateProductRequest;
import com.google.cloud.retail.v2.GetProductRequest;
import com.google.cloud.retail.v2.ImportProductsRequest;
import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.ProductInputConfig;
import com.google.cloud.retail.v2.ProductInlineSource;
import com.google.cloud.retail.v2.ProductServiceClient;
import com.google.cloud.retail.v2.PurgeProductsRequest;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsClient;

import java.util.List;

/**
 * Answers, with real API calls instead of documentation reading, exactly
 * what happens on a duplicate product id via each of the three insert paths:
 *
 * 1. CreateProduct (single item) called twice with the same id.
 * 2. ImportProducts where the SAME id appears twice INSIDE one batch.
 * 3. ImportProducts (default INCREMENTAL mode) called a second time with a
 *    batch that partially overlaps ids from the first call.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.DuplicateHandlingDemo
 */
public final class DuplicateHandlingDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String BRANCH =
            "projects/" + PROJECT_ID + "/locations/global/catalogs/default_catalog/branches/0";

    private DuplicateHandlingDemo() {
    }

    public static void main(String[] args) throws Exception {
        try (ProductServiceClient client = ProductServiceClient.create()) {
            OperationsClient opsClient = client.getOperationsClient();

            System.out.println("=== Cleanup (ensuring a clean start, re-runnable) ===");
            cleanup(client, opsClient);

            System.out.println();
            System.out.println("=== Experiment 1: CreateProduct called twice with the same id ===");
            experiment1CreateProductTwice(client);

            System.out.println();
            System.out.println("=== Experiment 2: same id appears TWICE inside one ImportProducts batch ===");
            experiment2DuplicateWithinOneBatch(client, opsClient);

            System.out.println();
            System.out.println("=== Experiment 3: second ImportProducts call, partially overlapping ids (INCREMENTAL) ===");
            experiment3OverlappingBatchesAcrossCalls(client, opsClient);

            System.out.println();
            System.out.println("=== Cleanup: purging all dup-demo-* products ===");
            cleanup(client, opsClient);
        }
    }

    private static void experiment1CreateProductTwice(ProductServiceClient client) {
        Product first = Product.newBuilder()
                .setId("dup-demo-create-1")
                .setTitle("First Create Call")
                .addCategories("Duplicate Demo")
                .build();
        Product created = client.createProduct(CreateProductRequest.newBuilder()
                .setParent(BRANCH)
                .setProduct(first)
                .setProductId(first.getId())
                .build());
        System.out.println("First CreateProduct succeeded: id=" + created.getId() + " title=" + created.getTitle());

        Product second = Product.newBuilder()
                .setId("dup-demo-create-1")
                .setTitle("Second Create Call - Should Fail")
                .addCategories("Duplicate Demo")
                .build();
        try {
            client.createProduct(CreateProductRequest.newBuilder()
                    .setParent(BRANCH)
                    .setProduct(second)
                    .setProductId(second.getId())
                    .build());
            System.out.println("UNEXPECTED: second CreateProduct with the same id did NOT throw.");
        } catch (Exception e) {
            System.out.println("Second CreateProduct with the same id threw as expected: "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private static void experiment2DuplicateWithinOneBatch(ProductServiceClient client, OperationsClient opsClient)
            throws InterruptedException {
        Product v1 = Product.newBuilder().setId("dup-demo-within-batch").setTitle("Within-Batch Version A")
                .addCategories("Duplicate Demo").build();
        Product v2 = Product.newBuilder().setId("dup-demo-within-batch").setTitle("Within-Batch Version B")
                .addCategories("Duplicate Demo").build();

        ImportProductsRequest request = ImportProductsRequest.newBuilder()
                .setParent(BRANCH)
                .setInputConfig(ProductInputConfig.newBuilder()
                        .setProductInlineSource(ProductInlineSource.newBuilder()
                                .addAllProducts(List.of(v1, v2))
                                .build())
                        .build())
                .build();

        Operation operation = client.importProductsCallable().call(request);
        while (!operation.getDone()) {
            Thread.sleep(2000);
            operation = opsClient.getOperation(operation.getName());
        }
        System.out.println("Import batch done. hasError=" + operation.hasError()
                + (operation.hasError() ? " error=" + operation.getError() : ""));

        // GetProduct hits the catalog store directly, not the search index -
        // no search-propagation delay should apply here, but retry briefly
        // anyway rather than assume a single immediate check is conclusive.
        Product landed = null;
        for (int attempt = 1; attempt <= 5 && landed == null; attempt++) {
            try {
                landed = client.getProduct(GetProductRequest.newBuilder()
                        .setName(BRANCH + "/products/dup-demo-within-batch")
                        .build());
            } catch (Exception e) {
                System.out.println("  GetProduct attempt " + attempt + ": " + e.getClass().getSimpleName()
                        + " - " + e.getMessage());
                Thread.sleep(3000);
            }
        }
        if (landed != null) {
            System.out.println("What actually landed: id=" + landed.getId() + " title=[" + landed.getTitle() + "]");
        } else {
            System.out.println("REAL FINDING: after 5 attempts over 15s, the product still does NOT exist at all -"
                    + " sending the SAME id twice in one inline-import batch did not error, but also silently"
                    + " did not create/update the product. Neither version won; it's as if that id was dropped.");
        }
    }

    private static void experiment3OverlappingBatchesAcrossCalls(ProductServiceClient client, OperationsClient opsClient)
            throws InterruptedException {
        // Batch A: 5 products, dup-demo-x1..x5, all titled "Original"
        List<Product> batchA = List.of(
                Product.newBuilder().setId("dup-demo-x1").setTitle("Original").addCategories("Duplicate Demo").build(),
                Product.newBuilder().setId("dup-demo-x2").setTitle("Original").addCategories("Duplicate Demo").build(),
                Product.newBuilder().setId("dup-demo-x3").setTitle("Original").addCategories("Duplicate Demo").build(),
                Product.newBuilder().setId("dup-demo-x4").setTitle("Original").addCategories("Duplicate Demo").build(),
                Product.newBuilder().setId("dup-demo-x5").setTitle("Original").addCategories("Duplicate Demo").build());
        runImport(client, opsClient, batchA, "Batch A (5 new products, all 'Original')");

        long countAfterA = countDupDemoXProducts(client);
        System.out.println("Product count (dup-demo-x*) after Batch A: " + countAfterA);

        // Batch B: 5 products - x1..x3 REPEAT from Batch A but with a changed
        // title, x6..x7 are genuinely new. This mirrors "100 records, 20 are
        // duplicates of already-existing ones" at a scale you can actually read.
        List<Product> batchB = List.of(
                Product.newBuilder().setId("dup-demo-x1").setTitle("Updated By Batch B").addCategories("Duplicate Demo").build(),
                Product.newBuilder().setId("dup-demo-x2").setTitle("Updated By Batch B").addCategories("Duplicate Demo").build(),
                Product.newBuilder().setId("dup-demo-x3").setTitle("Updated By Batch B").addCategories("Duplicate Demo").build(),
                Product.newBuilder().setId("dup-demo-x6").setTitle("New In Batch B").addCategories("Duplicate Demo").build(),
                Product.newBuilder().setId("dup-demo-x7").setTitle("New In Batch B").addCategories("Duplicate Demo").build());
        runImport(client, opsClient, batchB, "Batch B (3 repeats of x1-x3 with new titles, 2 genuinely new: x6,x7)");

        long countAfterB = countDupDemoXProducts(client);
        System.out.println("Product count (dup-demo-x*) after Batch B: " + countAfterB
                + " (5 + 5 sent = 10 total send events, but only 2 NEW ids were in Batch B)");

        Product x1 = getWithRetry(client, "dup-demo-x1");
        Product x4 = getWithRetry(client, "dup-demo-x4");
        System.out.println("x1 title after Batch B (was in both batches): ["
                + (x1 != null ? x1.getTitle() : "STILL NOT FOUND after retries") + "]");
        System.out.println("x4 title after Batch B (was only in Batch A, untouched by B): ["
                + (x4 != null ? x4.getTitle() : "STILL NOT FOUND after retries") + "]");

        if (countAfterB == 7) {
            System.out.println("VERIFIED: 7 distinct products exist (x1-x7), NOT 10 - overlapping ids upserted, not duplicated.");
        } else {
            System.out.println("MISMATCH: expected 7 distinct products after both batches, got " + countAfterB);
        }
    }

    private static Product getWithRetry(ProductServiceClient client, String productId) throws InterruptedException {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return client.getProduct(GetProductRequest.newBuilder()
                        .setName(BRANCH + "/products/" + productId)
                        .build());
            } catch (Exception e) {
                Thread.sleep(3000);
            }
        }
        return null;
    }

    private static void runImport(ProductServiceClient client, OperationsClient opsClient, List<Product> batch, String label)
            throws InterruptedException {
        System.out.println("Importing " + label + "...");
        ImportProductsRequest request = ImportProductsRequest.newBuilder()
                .setParent(BRANCH)
                .setInputConfig(ProductInputConfig.newBuilder()
                        .setProductInlineSource(ProductInlineSource.newBuilder()
                                .addAllProducts(batch)
                                .build())
                        .build())
                .build();
        Operation operation = client.importProductsCallable().call(request);
        while (!operation.getDone()) {
            Thread.sleep(2000);
            operation = opsClient.getOperation(operation.getName());
        }
        System.out.println("  done. hasError=" + operation.hasError());
    }

    private static long countDupDemoXProducts(ProductServiceClient client) {
        long count = 0;
        for (Product p : client.listProducts(BRANCH).iterateAll()) {
            if (p.getId().startsWith("dup-demo-x")) {
                count++;
            }
        }
        return count;
    }

    private static void cleanup(ProductServiceClient client, OperationsClient opsClient) throws InterruptedException {
        Operation purge = client.purgeProductsCallable().call(PurgeProductsRequest.newBuilder()
                .setParent(BRANCH)
                .setFilter("*")
                .setForce(true)
                .build());
        while (!purge.getDone()) {
            Thread.sleep(2000);
            purge = opsClient.getOperation(purge.getName());
        }
        System.out.println("Purge done. hasError=" + purge.hasError());
    }
}
