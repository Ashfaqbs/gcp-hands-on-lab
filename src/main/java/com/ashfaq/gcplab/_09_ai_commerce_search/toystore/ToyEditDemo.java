package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import com.google.cloud.retail.v2.PriceInfo;
import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.ProductServiceClient;
import com.google.cloud.retail.v2.UpdateProductRequest;
import com.google.protobuf.FieldMask;

/**
 * Step 4: modify one product (toy-3 price and title) and delete another
 * (toy-21). Pass "update" or "delete" as the first argument; no argument
 * runs both.
 *
 * Run: mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.toystore.ToyEditDemo -Dexec.args="update"
 */
public final class ToyEditDemo {

    private ToyEditDemo() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "both";
        try (ProductServiceClient client = ProductServiceClient.create()) {
            if (mode.equals("update") || mode.equals("both")) {
                update(client);
            }
            if (mode.equals("delete") || mode.equals("both")) {
                delete(client);
            }
        }
    }

    private static void update(ProductServiceClient client) {
        Product current = client.getProduct(ToyCatalog.productName("toy-3"));
        Product changed = current.toBuilder()
                .setTitle("Remote Control Racing Car (Sale)")
                .setPriceInfo(PriceInfo.newBuilder().setCurrencyCode("INR").setPrice(999f).build())
                .build();
        Product result = client.updateProduct(UpdateProductRequest.newBuilder()
                .setProduct(changed)
                .setUpdateMask(FieldMask.newBuilder().addPaths("title").addPaths("price_info").build())
                .build());
        System.out.println("Updated: " + result.getName() + " -> " + result.getTitle()
                + " @ " + result.getPriceInfo().getPrice());
    }

    private static void delete(ProductServiceClient client) {
        String name = ToyCatalog.productName("toy-21");
        client.deleteProduct(name);
        System.out.println("Deleted: " + name);
    }
}
