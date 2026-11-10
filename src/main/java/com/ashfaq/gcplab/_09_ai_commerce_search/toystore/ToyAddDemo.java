package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import com.google.cloud.retail.v2.CreateProductRequest;
import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.ProductServiceClient;

/**
 * Step 3: add one product (toy-21) to the existing catalog.
 *
 * Run: mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.toystore.ToyAddDemo
 */
public final class ToyAddDemo {

    private ToyAddDemo() {
    }

    public static void main(String[] args) throws Exception {
        Product toy = ToyCatalog.extraToy();
        try (ProductServiceClient client = ProductServiceClient.create()) {
            Product created = client.createProduct(CreateProductRequest.newBuilder()
                    .setParent(ToyCatalog.BRANCH)
                    .setProduct(toy)
                    .setProductId(toy.getId())
                    .build());
            System.out.println("Created: " + created.getName() + " - " + created.getTitle());
        }
    }
}
