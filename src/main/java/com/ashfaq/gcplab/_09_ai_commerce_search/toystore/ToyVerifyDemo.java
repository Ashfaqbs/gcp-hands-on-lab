package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import com.google.cloud.retail.v2.ListProductsRequest;
import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.ProductServiceClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Step 2: read the catalog back and compare with ToyCatalog. Retries for up
 * to ~60s because the import is eventually consistent.
 *
 * Run: mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.toystore.ToyVerifyDemo
 */
public final class ToyVerifyDemo {

    private ToyVerifyDemo() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, Product> expected = new HashMap<>();
        for (Product p : ToyCatalog.all()) {
            expected.put(p.getId(), p);
        }

        try (ProductServiceClient client = ProductServiceClient.create()) {
            for (int attempt = 1; attempt <= 12; attempt++) {
                Map<String, Product> actual = new HashMap<>();
                for (Product p : client.listProducts(
                        ListProductsRequest.newBuilder().setParent(ToyCatalog.BRANCH).build()).iterateAll()) {
                    actual.put(p.getId(), p);
                }
                int problems = compare(expected, actual);
                System.out.println("Attempt " + attempt + ": remote=" + actual.size()
                        + " expected=" + expected.size() + " problems=" + problems);
                if (problems == 0) {
                    System.out.println("VERIFIED: remote catalog matches ToyCatalog.");
                    return;
                }
                Thread.sleep(5000);
            }
            System.out.println("NOT VERIFIED after retries.");
        }
    }

    private static int compare(Map<String, Product> expected, Map<String, Product> actual) {
        int problems = 0;
        for (Map.Entry<String, Product> e : expected.entrySet()) {
            Product remote = actual.get(e.getKey());
            if (remote == null) {
                System.out.println("  missing: " + e.getKey());
                problems++;
            } else if (!remote.getTitle().equals(e.getValue().getTitle())
                    || remote.getPriceInfo().getPrice() != e.getValue().getPriceInfo().getPrice()) {
                System.out.println("  differs: " + e.getKey());
                problems++;
            }
        }
        for (String id : actual.keySet()) {
            if (!expected.containsKey(id)) {
                System.out.println("  unexpected extra: " + id);
                problems++;
            }
        }
        return problems;
    }
}
