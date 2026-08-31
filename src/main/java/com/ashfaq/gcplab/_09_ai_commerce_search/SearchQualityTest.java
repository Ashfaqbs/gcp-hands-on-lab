package com.ashfaq.gcplab._09_ai_commerce_search;

import com.google.cloud.retail.v2.GetProductRequest;
import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.ProductServiceClient;
import com.google.cloud.retail.v2.SearchRequest;
import com.google.cloud.retail.v2.SearchResponse;
import com.google.cloud.retail.v2.SearchServiceClient;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs 100 simulated shopper queries against the imported catalog, across
 * 4 buckets of 25 mirroring how a real (non-technical) person searches:
 * A) exact/close product-name queries, B) natural-language descriptions of
 * a need rather than a product name, C) queries mentioning weight/volume,
 * D) queries mentioning apparel/diaper sizes (S/M/L/XL/XXL). Top 3 results
 * per query, enriched with titles via a follow-up GetProduct call (search
 * itself only returns IDs). Output written to search-quality-results.txt
 * for manual relevance scoring afterward.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.SearchQualityTest
 */
public final class SearchQualityTest {

    private static final String PLACEMENT =
            "projects/884717715366/locations/global/catalogs/default_catalog/servingConfigs/default_search";
    private static final String PRODUCT_NAME_PREFIX =
            "projects/884717715366/locations/global/catalogs/default_catalog/branches/0/products/";

    record TestQuery(int number, String bucket, String query) {
    }

    private static final List<TestQuery> QUERIES = List.of(
            // Bucket A - exact/close product-name (25)
            new TestQuery(1, "A-exact", "basmati rice"),
            new TestQuery(2, "A-exact", "toothpaste"),
            new TestQuery(3, "A-exact", "cola"),
            new TestQuery(4, "A-exact", "shampoo"),
            new TestQuery(5, "A-exact", "milk"),
            new TestQuery(6, "A-exact", "bread"),
            new TestQuery(7, "A-exact", "potato chips"),
            new TestQuery(8, "A-exact", "butter"),
            new TestQuery(9, "A-exact", "eggs"),
            new TestQuery(10, "A-exact", "detergent powder"),
            new TestQuery(11, "A-exact", "sugar"),
            new TestQuery(12, "A-exact", "salt"),
            new TestQuery(13, "A-exact", "cooking oil"),
            new TestQuery(14, "A-exact", "tea"),
            new TestQuery(15, "A-exact", "coffee"),
            new TestQuery(16, "A-exact", "baby diapers"),
            new TestQuery(17, "A-exact", "hand sanitizer"),
            new TestQuery(18, "A-exact", "ice cream"),
            new TestQuery(19, "A-exact", "notebook"),
            new TestQuery(20, "A-exact", "umbrella"),
            new TestQuery(21, "A-exact", "water bottle"),
            new TestQuery(22, "A-exact", "face wash"),
            new TestQuery(23, "A-exact", "hair oil"),
            new TestQuery(24, "A-exact", "dishwash liquid"),
            new TestQuery(25, "A-exact", "chocolate"),

            // Bucket B - natural-language description of a need (25)
            new TestQuery(26, "B-descriptive", "something to wash my dishes with"),
            new TestQuery(27, "B-descriptive", "i need something for my dandruff"),
            new TestQuery(28, "B-descriptive", "drink to keep me hydrated in summer"),
            new TestQuery(29, "B-descriptive", "snack for movie night"),
            new TestQuery(30, "B-descriptive", "something to clean my bathroom"),
            new TestQuery(31, "B-descriptive", "protein supplement for gym"),
            new TestQuery(32, "B-descriptive", "soft drink for party"),
            new TestQuery(33, "B-descriptive", "baby skin care lotion"),
            new TestQuery(34, "B-descriptive", "rice for daily cooking"),
            new TestQuery(35, "B-descriptive", "spread for my toast"),
            new TestQuery(36, "B-descriptive", "spicy snack mix"),
            new TestQuery(37, "B-descriptive", "something for my dry hair"),
            new TestQuery(38, "B-descriptive", "cooking oil for frying"),
            new TestQuery(39, "B-descriptive", "medicine for body pain"),
            new TestQuery(40, "B-descriptive", "daily vitamins"),
            new TestQuery(41, "B-descriptive", "something to sanitize my hands"),
            new TestQuery(42, "B-descriptive", "frozen snack for kids"),
            new TestQuery(43, "B-descriptive", "sweet treat for kids"),
            new TestQuery(44, "B-descriptive", "flour to make chapati"),
            new TestQuery(45, "B-descriptive", "lentils for dal"),
            new TestQuery(46, "B-descriptive", "tissue for my nose"),
            new TestQuery(47, "B-descriptive", "juice with no added sugar"),
            new TestQuery(48, "B-descriptive", "something to write with"),
            new TestQuery(49, "B-descriptive", "light source for my room"),
            new TestQuery(50, "B-descriptive", "bag to carry my lunch"),

            // Bucket C - weight/volume mentioned (25)
            new TestQuery(51, "C-weight", "1kg rice"),
            new TestQuery(52, "C-weight", "5kg atta"),
            new TestQuery(53, "C-weight", "500ml shampoo"),
            new TestQuery(54, "C-weight", "1 litre milk"),
            new TestQuery(55, "C-weight", "2kg detergent"),
            new TestQuery(56, "C-weight", "200g butter"),
            new TestQuery(57, "C-weight", "100g coffee"),
            new TestQuery(58, "C-weight", "1 litre cooking oil"),
            new TestQuery(59, "C-weight", "500g paneer"),
            new TestQuery(60, "C-weight", "10kg rice bag"),
            new TestQuery(61, "C-weight", "2 litre cola"),
            new TestQuery(62, "C-weight", "1kg sugar"),
            new TestQuery(63, "C-weight", "250g chips"),
            new TestQuery(64, "C-weight", "1kg protein powder"),
            new TestQuery(65, "C-weight", "5 litre water"),
            new TestQuery(66, "C-weight", "100ml hair oil"),
            new TestQuery(67, "C-weight", "500ml body wash"),
            new TestQuery(68, "C-weight", "1kg toor dal"),
            new TestQuery(69, "C-weight", "200ml sanitizer"),
            new TestQuery(70, "C-weight", "700g bread"),
            new TestQuery(71, "C-weight", "1kg namkeen"),
            new TestQuery(72, "C-weight", "500g cookies"),
            new TestQuery(73, "C-weight", "2kg frozen peas"),
            new TestQuery(74, "C-weight", "100g turmeric powder"),
            new TestQuery(75, "C-weight", "250ml floor cleaner"),

            // Bucket D - size letters S/M/L/XL/XXL (25)
            new TestQuery(76, "D-size", "XL t-shirt"),
            new TestQuery(77, "D-size", "size L jeans"),
            new TestQuery(78, "D-size", "men's shirt size M"),
            new TestQuery(79, "D-size", "XXL diapers"),
            new TestQuery(80, "D-size", "small size kurti"),
            new TestQuery(81, "D-size", "medium track pants"),
            new TestQuery(82, "D-size", "large leggings"),
            new TestQuery(83, "D-size", "XL kids t-shirt"),
            new TestQuery(84, "D-size", "men's t-shirt in L"),
            new TestQuery(85, "D-size", "women's t-shirt XL"),
            new TestQuery(86, "D-size", "size S formal shirt"),
            new TestQuery(87, "D-size", "XL nightwear"),
            new TestQuery(88, "D-size", "baby diapers size M"),
            new TestQuery(89, "D-size", "innerwear size L"),
            new TestQuery(90, "D-size", "XXL t-shirt for men"),
            new TestQuery(91, "D-size", "small diapers"),
            new TestQuery(92, "D-size", "size XL kurti"),
            new TestQuery(93, "D-size", "jeans size L"),
            new TestQuery(94, "D-size", "women's leggings size M"),
            new TestQuery(95, "D-size", "XL track pants"),
            new TestQuery(96, "D-size", "formal shirt XXL"),
            new TestQuery(97, "D-size", "kurti size XL"),
            new TestQuery(98, "D-size", "men's vest size M"),
            new TestQuery(99, "D-size", "diapers size L"),
            new TestQuery(100, "D-size", "XL women's t-shirt")
    );

    private SearchQualityTest() {
    }

    public static void main(String[] args) throws Exception {
        Path outPath = Path.of("search-quality-results.txt");

        try (SearchServiceClient searchClient = SearchServiceClient.create();
             ProductServiceClient productClient = ProductServiceClient.create();
             PrintWriter out = new PrintWriter(outPath.toFile())) {

            for (TestQuery tq : QUERIES) {
                SearchRequest request = SearchRequest.newBuilder()
                        .setPlacement(PLACEMENT)
                        .setVisitorId("quality-test-visitor")
                        .setQuery(tq.query())
                        .setPageSize(3)
                        .build();

                SearchResponse response = searchClient.search(request).getPage().getResponse();

                StringBuilder line = new StringBuilder();
                line.append("Q").append(tq.number())
                        .append(" [").append(tq.bucket()).append("] \"")
                        .append(tq.query()).append("\" (totalSize=")
                        .append(response.getTotalSize()).append(") -> ");

                if (response.getResultsCount() == 0) {
                    line.append("NO RESULTS");
                } else {
                    List<String> titles = response.getResultsList().stream()
                            .map(r -> fetchTitle(productClient, r.getId()))
                            .toList();
                    line.append(String.join(" | ", titles));
                }

                System.out.println(line);
                out.println(line);
            }
        }

        System.out.println();
        System.out.println("Full results written to " + outPath.toAbsolutePath());
    }

    private static String fetchTitle(ProductServiceClient client, String productId) {
        try {
            Product product = client.getProduct(GetProductRequest.newBuilder()
                    .setName(PRODUCT_NAME_PREFIX + productId)
                    .build());
            return product.getTitle();
        } catch (Exception e) {
            return "[error fetching " + productId + "]";
        }
    }
}
