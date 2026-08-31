package com.ashfaq.gcplab._09_ai_commerce_search;

import com.google.cloud.retail.v2.SearchRequest;
import com.google.cloud.retail.v2.SearchResponse;
import com.google.cloud.retail.v2.SearchServiceClient;

/** One-off sanity check before running the full 100-query test. */
public final class QuickSearchTest {

    private static final String PLACEMENT =
            "projects/884717715366/locations/global/catalogs/default_catalog/servingConfigs/default_search";

    private QuickSearchTest() {
    }

    public static void main(String[] args) throws Exception {
        try (SearchServiceClient client = SearchServiceClient.create()) {
            SearchRequest request = SearchRequest.newBuilder()
                    .setPlacement(PLACEMENT)
                    .setVisitorId("test-visitor-1")
                    .setQuery("basmati rice")
                    .setPageSize(5)
                    .build();

            SearchResponse response = client.search(request).getPage().getResponse();
            System.out.println("totalSize=" + response.getTotalSize());
            System.out.println("resultsCount=" + response.getResultsCount());
            for (SearchResponse.SearchResult r : response.getResultsList()) {
                System.out.println("id=" + r.getId() + " title=[" + r.getProduct().getTitle() + "]");
            }
        }
    }
}
