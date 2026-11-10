package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import java.util.List;
import java.util.Random;

/**
 * Five fake shoppers who all like toy-2. Each session: search, see ranked
 * results, click (mostly toy-2 when it is shown, wherever it sits), sometimes
 * add to cart, sometimes buy. Every step is sent as a user event with the
 * attribution token of that search.
 *
 * Modes:
 *   rank            print the current ranking for the query, send nothing
 *   events [N]      run N sessions (default 100) and send the events
 *
 * Run: mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.toystore.ToyUserSimulator -Dexec.args="rank"
 *
 * Learning from events is not instant. Run "rank" before, run "events", then
 * run "rank" again later (hours or days) and compare.
 */
public final class ToyUserSimulator {

    private static final String FAVOURITE = "toy-2";
    private static final String QUERY = "toy";
    private static final List<String> VISITORS = List.of("user1", "user2", "user3", "user4", "user5");

    private ToyUserSimulator() {
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "rank";
        int sessions = args.length > 1 ? Integer.parseInt(args[1]) : 100;

        ToyRetailGateway gateway;
        try {
            gateway = new ToyRetailGateway(new ToyEventLog());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not create Retail clients", e);
        }
        try {
            if (mode.equals("rank")) {
                printRanking(gateway);
            } else if (mode.equals("events")) {
                printRanking(gateway);
                runSessions(gateway, sessions);
                printRanking(gateway);
            } else {
                System.out.println("Unknown mode '" + mode + "'. Use: rank | events [N]");
            }
        } finally {
            gateway.close();
        }
    }

    private static void printRanking(ToyRetailGateway gateway) {
        ToyRetailGateway.SearchOutcome outcome = gateway.search(QUERY, "ranking-probe");
        int position = outcome.productIds().indexOf(FAVOURITE) + 1;
        System.out.println("Ranking for '" + QUERY + "': " + outcome.productIds());
        System.out.println(FAVOURITE + " position: " + (position == 0 ? "not in results" : position));
    }

    private static void runSessions(ToyRetailGateway gateway, int sessions) {
        Random random = new Random(42);
        int views = 0;
        int carts = 0;
        int purchases = 0;
        for (int i = 0; i < sessions; i++) {
            String visitor = VISITORS.get(random.nextInt(VISITORS.size()));
            ToyRetailGateway.SearchOutcome outcome = gateway.search(QUERY, visitor);
            List<String> ids = outcome.productIds();
            if (ids.isEmpty()) {
                continue;
            }
            String token = outcome.attributionToken();
            gateway.sendEvent(ToyRetailGateway.SEARCH, visitor, token, null, QUERY);

            String clicked = pickClick(ids, random);
            gateway.sendEvent(ToyRetailGateway.DETAIL_PAGE_VIEW, visitor, token, clicked, null);
            views++;
            if (random.nextDouble() < 0.5) {
                gateway.sendEvent(ToyRetailGateway.ADD_TO_CART, visitor, token, clicked, null);
                carts++;
                if (random.nextDouble() < 0.5) {
                    gateway.sendEvent(ToyRetailGateway.PURCHASE_COMPLETE, visitor, token, clicked, null);
                    purchases++;
                }
            }
        }
        System.out.println("Sent " + sessions + " sessions: " + views + " views, "
                + carts + " add-to-cart, " + purchases + " purchases.");
    }

    /** 70% of the time click the favourite if it is shown; otherwise click one of the top five. */
    private static String pickClick(List<String> ids, Random random) {
        if (ids.contains(FAVOURITE) && random.nextDouble() < 0.7) {
            return FAVOURITE;
        }
        return ids.get(random.nextInt(Math.min(5, ids.size())));
    }
}
