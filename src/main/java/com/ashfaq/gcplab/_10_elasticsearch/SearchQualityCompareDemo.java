package com.ashfaq.gcplab._10_elasticsearch;

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the SAME 25 queries (a stratified subset of the 100 used against AI
 * Commerce Search in _09, same query numbers, same buckets) against the
 * self-hosted Elasticsearch index, so the two result sets can be scored
 * with the identical rubric and placed side by side. Uses a plain
 * multi_match query across title/description/attribute - no tuning, no
 * synonyms, no custom analyzers - to keep the comparison honest: "what you
 * get out of the box" vs "what you get out of the box."
 *
 * The target Elasticsearch node is read from the ES_URL environment
 * variable - never hardcoded (the learning VM it targets is torn down
 * after each session).
 *
 * Run with:
 *   export ES_URL=http://<vm-external-ip>:9200   (bash)
 *   $env:ES_URL = "http://<vm-external-ip>:9200"  (PowerShell)
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._10_elasticsearch.SearchQualityCompareDemo
 */
public final class SearchQualityCompareDemo {

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
    private static final Pattern TITLE_PATTERN = Pattern.compile("\"title\":\"(.*?)\"");

    record TestQuery(int number, String bucket, String query) {
    }

    private static final List<TestQuery> QUERIES = List.of(
            new TestQuery(1, "A-exact", "basmati rice"),
            new TestQuery(3, "A-exact", "cola"),
            new TestQuery(7, "A-exact", "potato chips"),
            new TestQuery(10, "A-exact", "detergent powder"),
            new TestQuery(16, "A-exact", "baby diapers"),
            new TestQuery(25, "A-exact", "chocolate"),

            new TestQuery(27, "B-descriptive", "i need something for my dandruff"),
            new TestQuery(29, "B-descriptive", "snack for movie night"),
            new TestQuery(34, "B-descriptive", "rice for daily cooking"),
            new TestQuery(38, "B-descriptive", "cooking oil for frying"),
            new TestQuery(44, "B-descriptive", "flour to make chapati"),
            new TestQuery(49, "B-descriptive", "light source for my room"),

            new TestQuery(51, "C-weight", "1kg rice"),
            new TestQuery(56, "C-weight", "200g butter"),
            new TestQuery(60, "C-weight", "10kg rice bag"),
            new TestQuery(64, "C-weight", "1kg protein powder"),
            new TestQuery(68, "C-weight", "1kg toor dal"),
            new TestQuery(73, "C-weight", "2kg frozen peas"),

            new TestQuery(76, "D-size", "XL t-shirt"),
            new TestQuery(78, "D-size", "men's shirt size M"),
            new TestQuery(82, "D-size", "large leggings"),
            new TestQuery(88, "D-size", "baby diapers size M"),
            new TestQuery(92, "D-size", "size XL kurti"),
            new TestQuery(96, "D-size", "formal shirt XXL"),
            new TestQuery(99, "D-size", "diapers size L")
    );

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        try (PrintWriter out = new PrintWriter(Path.of("elasticsearch-quality-results.txt").toFile(), StandardCharsets.UTF_8)) {
            for (TestQuery q : QUERIES) {
                String searchBody = """
                        {
                          "size": 3,
                          "query": {
                            "multi_match": {
                              "query": "%s",
                              "fields": ["title^2", "description", "attribute"]
                            }
                          }
                        }
                        """.formatted(q.query().replace("\"", "\\\""));

                HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(ES_URL + "/" + INDEX + "/_search"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(searchBody))
                        .build(), HttpResponse.BodyHandlers.ofString());

                List<String> titles = extractTitles(resp.body());

                String line = String.format("#%-3d [%s] \"%s\" -> %s",
                        q.number(), q.bucket(), q.query(), titles.isEmpty() ? "(no results)" : titles);
                System.out.println(line);
                out.println(line);
            }
        }
        System.out.println("Written to elasticsearch-quality-results.txt");
    }

    private static List<String> extractTitles(String responseBody) {
        Matcher m = TITLE_PATTERN.matcher(responseBody);
        List<String> titles = new java.util.ArrayList<>();
        while (m.find() && titles.size() < 3) {
            titles.add(m.group(1));
        }
        return titles;
    }
}
