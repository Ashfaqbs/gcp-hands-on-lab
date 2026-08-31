package com.ashfaq.gcplab._09_ai_commerce_search;

import com.google.cloud.retail.v2.CustomAttribute;
import com.google.cloud.retail.v2.Product;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Dumps the exact same synthetic catalog used for the import/search-quality
 * exercise to a JSON file, so it can actually be read and judged for data
 * quality instead of only being visible indirectly through search result
 * titles. Nothing calls the Retail API here - this is a pure local export
 * of what ProductCatalogGenerator produces.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.CatalogExportDemo
 */
public final class CatalogExportDemo {

    private CatalogExportDemo() {
    }

    public static void main(String[] args) throws Exception {
        List<Product> products = ProductCatalogGenerator.generate();

        try (PrintWriter out = new PrintWriter(Path.of("search-experiments", "catalog-export.json").toFile(), StandardCharsets.UTF_8)) {
            out.println("[");
            for (int i = 0; i < products.size(); i++) {
                Product p = products.get(i);
                String attributeKey = "";
                String attributeValue = "";
                for (Map.Entry<String, CustomAttribute> e : p.getAttributesMap().entrySet()) {
                    if (!e.getValue().getTextList().isEmpty()) {
                        attributeKey = e.getKey();
                        attributeValue = e.getValue().getText(0);
                    }
                }

                out.println("  {");
                out.println("    \"id\": " + json(p.getId()) + ",");
                out.println("    \"title\": " + json(p.getTitle()) + ",");
                out.println("    \"description\": " + json(p.getDescription()) + ",");
                out.println("    \"category\": " + json(p.getCategoriesList().isEmpty() ? "" : p.getCategoriesList().get(0)) + ",");
                out.println("    \"brand\": " + json(p.getBrandsList().isEmpty() ? "" : p.getBrandsList().get(0)) + ",");
                out.println("    \"priceInr\": " + p.getPriceInfo().getPrice() + ",");
                out.println("    \"attributeKey\": " + json(attributeKey) + ",");
                out.println("    \"attributeValue\": " + json(attributeValue));
                out.print("  }");
                out.println(i < products.size() - 1 ? "," : "");
            }
            out.println("]");
        }

        System.out.println("Exported " + products.size() + " products to search-experiments/catalog-export.json");
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
