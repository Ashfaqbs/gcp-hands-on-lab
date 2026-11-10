package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import com.google.cloud.retail.v2.Product;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UI -> this controller -> Retail API.
 *
 * Search returns only product ids from the Retail API. Title and price are
 * then read from our own catalog (ToyCatalog), which plays the role of the
 * real database: the index finds, our own data displays.
 */
@RestController
@RequestMapping("/api")
public class ToyController {

    private static final Set<String> EVENT_TYPES = Set.of(
            ToyRetailGateway.SEARCH,
            ToyRetailGateway.DETAIL_PAGE_VIEW,
            ToyRetailGateway.ADD_TO_CART,
            ToyRetailGateway.PURCHASE_COMPLETE);

    private final ToyRetailGateway gateway;
    private final Map<String, Product> catalog = ToyCatalog.byId();

    private final ToyEventLog eventLog;

    public ToyController(ToyRetailGateway gateway, ToyEventLog eventLog) {
        this.gateway = gateway;
        this.eventLog = eventLog;
    }

    /** Events we sent, newest first. Leave visitorId empty for all shoppers. */
    @GetMapping("/events/history")
    public List<ToyEventLog.Entry> history(@RequestParam(defaultValue = "") String visitorId) {
        return eventLog.read(visitorId, 200);
    }

    public record ToyView(String id, String title, float price, String brand, String category) {
    }

    public record SearchView(String query, String attributionToken, List<ToyView> results) {
    }

    public record EventRequest(String eventType, String visitorId, String attributionToken,
                               String productId, String searchQuery) {
    }

    @GetMapping("/toys/search")
    public SearchView search(@RequestParam(defaultValue = "") String q,
                             @RequestParam String visitorId) {
        ToyRetailGateway.SearchOutcome outcome = gateway.search(q, visitorId);
        List<ToyView> results = outcome.productIds().stream().map(this::toView).toList();
        return new SearchView(q, outcome.attributionToken(), results);
    }

    @PostMapping("/events")
    public ResponseEntity<String> event(@RequestBody EventRequest request) {
        if (request.eventType() == null || !EVENT_TYPES.contains(request.eventType())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("eventType must be one of " + EVENT_TYPES);
        }
        if (request.visitorId() == null || request.visitorId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("visitorId is required");
        }
        boolean needsProduct = !request.eventType().equals(ToyRetailGateway.SEARCH);
        if (needsProduct && (request.productId() == null || request.productId().isBlank())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("productId is required for " + request.eventType());
        }
        gateway.sendEvent(request.eventType(), request.visitorId(), request.attributionToken(),
                request.productId(), request.searchQuery());
        return ResponseEntity.ok("sent " + request.eventType());
    }

    private ToyView toView(String id) {
        Product product = catalog.get(id);
        if (product == null) {
            return new ToyView(id, "(not in local catalog)", 0f, "", "");
        }
        return new ToyView(id, product.getTitle(), product.getPriceInfo().getPrice(),
                product.getBrands(0), product.getCategories(0));
    }
}
