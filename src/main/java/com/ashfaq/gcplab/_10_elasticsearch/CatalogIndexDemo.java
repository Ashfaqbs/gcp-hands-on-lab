package com.ashfaq.gcplab._10_elasticsearch;

import com.ashfaq.gcplab._09_ai_commerce_search.ProductCatalogGenerator;
import com.google.cloud.retail.v2.CustomAttribute;
import com.google.cloud.retail.v2.Product;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Reuses the same synthetic catalog from _09_ai_commerce_search (703
 * products, same generator) and bulk-indexes it into the self-hosted
 * Elasticsearch node via the Bulk API, using plain HttpClient (no ES
 * client library dependency needed for this exercise). Mapping is hand
 * designed: title/description as analyzed text, brand/category as
 * keyword (for faceting), a numeric price, and a size/weight attribute
 * as keyword (needed since bucket C/D queries search on these).
 *
 * The target Elasticsearch node is read from the ES_URL environment
 * variable - never hardcoded, since the learning VM it points to is
 * created and torn down per session (see _10_elasticsearch's package-info
 * for the exact gcloud commands to stand one back up).
 *
 * Run with:
 *   export ES_URL=http://<vm-external-ip>:9200   (bash)
 *   $env:ES_URL = "http://<vm-external-ip>:9200"  (PowerShell)
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._10_elasticsearch.CatalogIndexDemo
 */
public final class CatalogIndexDemo {

    private static final String ES_URL = requireEsUrl();
    private static final String INDEX = "products";

    private static String requireEsUrl() {
        String url = System.getenv("ES_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "ES_URL environment variable is not set - point it at the running Elasticsearch VM's external IP, e.g. http://<ip>:9200");
        }
        return url;
    }

    private CatalogIndexDemo() {
    }

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        // 1. Create index with an explicit mapping (drop it first if it exists, for a clean re-run).
        http.send(HttpRequest.newBuilder(URI.create(ES_URL + "/" + INDEX))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());

        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "title":       { "type": "text" },
                      "description": { "type": "text" },
                      "category":    { "type": "keyword" },
                      "brand":       { "type": "keyword" },
                      "attribute":   { "type": "keyword" },
                      "price":       { "type": "float" }
                    }
                  }
                }
                """;
        HttpResponse<String> createResp = http.send(HttpRequest.newBuilder(URI.create(ES_URL + "/" + INDEX))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapping))
                .build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("Create index: " + createResp.statusCode() + " " + createResp.body());

        // 2. Bulk-index every product from the same generator used for AI Commerce Search.
        List<Product> products = ProductCatalogGenerator.generate();
        StringBuilder bulk = new StringBuilder();
        for (Product p : products) {
            String attribute = "";
            for (Map.Entry<String, CustomAttribute> e : p.getAttributesMap().entrySet()) {
                if (!e.getValue().getTextList().isEmpty()) {
                    attribute = e.getValue().getText(0);
                }
            }
            bulk.append("{\"index\":{\"_index\":\"").append(INDEX).append("\",\"_id\":\"").append(p.getId()).append("\"}}\n");
            bulk.append(String.format(java.util.Locale.ROOT,
                    "{\"title\":%s,\"description\":%s,\"category\":%s,\"brand\":%s,\"attribute\":%s,\"price\":%.2f}%n",
                    json(p.getTitle()), json(p.getDescription()),
                    json(p.getCategoriesList().isEmpty() ? "" : p.getCategoriesList().get(0)),
                    json(p.getBrandsList().isEmpty() ? "" : p.getBrandsList().get(0)),
                    json(attribute), p.getPriceInfo().getPrice()));
        }

        HttpResponse<String> bulkResp = http.send(HttpRequest.newBuilder(URI.create(ES_URL + "/_bulk"))
                .header("Content-Type", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(bulk.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());

        boolean hasErrors = bulkResp.body().contains("\"errors\":true");
        System.out.println("Bulk index HTTP " + bulkResp.statusCode() + ", errors=" + hasErrors
                + ", products submitted=" + products.size());

        // 3. Refresh so the docs are immediately searchable, then confirm count.
        http.send(HttpRequest.newBuilder(URI.create(ES_URL + "/" + INDEX + "/_refresh"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        HttpResponse<String> countResp = http.send(HttpRequest.newBuilder(URI.create(ES_URL + "/" + INDEX + "/_count"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("Document count: " + countResp.body());
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
