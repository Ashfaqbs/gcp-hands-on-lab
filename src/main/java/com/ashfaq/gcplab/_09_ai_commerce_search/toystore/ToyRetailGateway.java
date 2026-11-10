package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import com.google.cloud.retail.v2.ProductDetail;
import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.PurchaseTransaction;
import com.google.cloud.retail.v2.SearchRequest;
import com.google.cloud.retail.v2.SearchResponse;
import com.google.cloud.retail.v2.SearchServiceClient;
import com.google.cloud.retail.v2.UserEvent;
import com.google.cloud.retail.v2.UserEventServiceClient;
import com.google.cloud.retail.v2.WriteUserEventRequest;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Timestamp;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The only class that talks to the Retail API for the toy store. The web
 * controller and the user simulator both go through it, so a search or an
 * event is built in exactly one place.
 */
@Component
public class ToyRetailGateway {

    public static final String SEARCH = "search";
    public static final String DETAIL_PAGE_VIEW = "detail-page-view";
    public static final String ADD_TO_CART = "add-to-cart";
    public static final String PURCHASE_COMPLETE = "purchase-complete";

    private static final int PAGE_SIZE = 20;

    private final SearchServiceClient searchClient;
    private final UserEventServiceClient eventClient;

    private final ToyEventLog eventLog;

    public ToyRetailGateway(ToyEventLog eventLog) throws IOException {
        this.eventLog = eventLog;
        this.searchClient = SearchServiceClient.create();
        this.eventClient = UserEventServiceClient.create();
    }

    /** One search outcome: the ranked product ids plus the token that links later events to this search. */
    public record SearchOutcome(String attributionToken, List<String> productIds) {
    }

    public SearchOutcome search(String query, String visitorId) {
        SearchRequest request = SearchRequest.newBuilder()
                .setPlacement(ToyCatalog.SERVING_CONFIG)
                .setBranch(ToyCatalog.BRANCH)
                .setQuery(query)
                .setVisitorId(visitorId)
                .setPageSize(PAGE_SIZE)
                .build();
        SearchResponse response = searchClient.search(request).getPage().getResponse();
        List<String> ids = response.getResultsList().stream()
                .map(SearchResponse.SearchResult::getId)
                .toList();
        return new SearchOutcome(response.getAttributionToken(), ids);
    }

    /**
     * Sends one shopper event. productId is needed for every type except search;
     * searchQuery is used for search events only.
     */
    public void sendEvent(String eventType, String visitorId, String attributionToken,
                          String productId, String searchQuery) {
        UserEvent.Builder event = UserEvent.newBuilder()
                .setEventType(eventType)
                .setVisitorId(visitorId)
                .setEventTime(now());
        if (attributionToken != null && !attributionToken.isBlank()) {
            event.setAttributionToken(attributionToken);
        }
        if (eventType.equals(SEARCH)) {
            event.setSearchQuery(searchQuery == null ? "" : searchQuery);
        } else {
            ProductDetail detail = ProductDetail.newBuilder()
                    .setProduct(Product.newBuilder().setId(productId).build())
                    .setQuantity(Int32Value.of(1))
                    .build();
            event.addProductDetails(detail);
        }
        if (eventType.equals(PURCHASE_COMPLETE)) {
            event.setPurchaseTransaction(PurchaseTransaction.newBuilder()
                    .setRevenue(priceOf(productId))
                    .setCurrencyCode("INR")
                    .build());
        }
        eventClient.writeUserEvent(WriteUserEventRequest.newBuilder()
                .setParent(ToyCatalog.CATALOG)
                .setUserEvent(event.build())
                .build());
        eventLog.append(visitorId, eventType, productId, searchQuery);
    }

    @PreDestroy
    public void close() {
        searchClient.close();
        eventClient.close();
    }

    private static Timestamp now() {
        Instant instant = Instant.now();
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static float priceOf(String productId) {
        Map<String, Product> byId = ToyCatalog.byId();
        Product product = byId.get(productId);
        return product == null ? 0f : product.getPriceInfo().getPrice();
    }
}
