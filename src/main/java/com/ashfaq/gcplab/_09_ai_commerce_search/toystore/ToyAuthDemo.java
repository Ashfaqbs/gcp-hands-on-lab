package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.google.cloud.retail.v2.Catalog;
import com.google.cloud.retail.v2.CatalogServiceClient;
import com.google.cloud.retail.v2.GetDefaultBranchRequest;
import com.google.cloud.retail.v2.ListProductsRequest;
import com.google.cloud.retail.v2.Product;
import com.google.cloud.retail.v2.ProductServiceClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Shows how authentication works against the Retail API, then lists what is
 * in the project: catalogs, the default branch, and the product count in
 * each of branches 0, 1 and 2.
 *
 * Part 1: where the credentials come from (Application Default Credentials).
 * Part 2: the same REST call without and with a token (401 vs 200).
 * Part 3: catalog, default branch, and size of each branch.
 * Part 4: the user event store (no list or count API exists for it).
 *
 * The access token itself is never printed.
 *
 * Run: mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._09_ai_commerce_search.toystore.ToyAuthDemo
 */
public final class ToyAuthDemo {

    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String LOCATION = "projects/" + ToyCatalog.PROJECT_ID + "/locations/global";
    private static final String CATALOGS_URL = "https://retail.googleapis.com/v2/" + LOCATION + "/catalogs";

    private ToyAuthDemo() {
    }

    public static void main(String[] args) throws Exception {
        GoogleCredentials credentials = showCredentials();
        showRawRestCalls(credentials);
        showCatalogAndBranches();
        showUserEventStore();
    }

    private static GoogleCredentials showCredentials() throws Exception {
        System.out.println("=== Part 1: credentials (Application Default Credentials) ===");
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault().createScoped(SCOPE);
        System.out.println("Credential type : " + credentials.getClass().getSimpleName());
        if (credentials instanceof ServiceAccountCredentials sa) {
            System.out.println("Service account : " + sa.getClientEmail());
        } else if (credentials instanceof UserCredentials) {
            System.out.println("Identity        : a user logged in with gcloud auth application-default login");
        }
        credentials.refreshIfExpired();
        AccessToken token = credentials.getAccessToken();
        System.out.println("Access token    : obtained (not printed), expires " + token.getExpirationTime());
        System.out.println("Quota project   : " + credentials.getQuotaProjectId());
        System.out.println("Scope requested : " + SCOPE);
        System.out.println();
        return credentials;
    }

    private static void showRawRestCalls(GoogleCredentials credentials) throws Exception {
        System.out.println("=== Part 2: the same REST call without and with a token ===");
        HttpClient http = HttpClient.newHttpClient();

        HttpRequest anonymous = HttpRequest.newBuilder(URI.create(CATALOGS_URL)).GET().build();
        int anonymousStatus = http.send(anonymous, HttpResponse.BodyHandlers.discarding()).statusCode();
        System.out.println("GET /catalogs with no token   -> HTTP " + anonymousStatus + " (expected 401)");

        String bearer = "Bearer " + credentials.getAccessToken().getTokenValue();
        HttpRequest authed = HttpRequest.newBuilder(URI.create(CATALOGS_URL))
                .header("Authorization", bearer)
                .header("x-goog-user-project", ToyCatalog.PROJECT_ID)
                .GET().build();
        int authedStatus = http.send(authed, HttpResponse.BodyHandlers.discarding()).statusCode();
        System.out.println("GET /catalogs with the token  -> HTTP " + authedStatus + " (expected 200)");
        System.out.println("The client libraries do exactly this for you: they find the credentials,");
        System.out.println("refresh the token when it expires, and add the Authorization header.");
        System.out.println();
    }

    private static void showCatalogAndBranches() throws Exception {
        System.out.println("=== Part 3: catalog, default branch, branch sizes ===");
        try (CatalogServiceClient catalogs = CatalogServiceClient.create()) {
            for (Catalog catalog : catalogs.listCatalogs(LOCATION).iterateAll()) {
                System.out.println("Catalog         : " + catalog.getName() + " (" + catalog.getDisplayName() + ")");
            }
            String defaultBranch = catalogs.getDefaultBranch(
                    GetDefaultBranchRequest.newBuilder().setCatalog(ToyCatalog.CATALOG).build()).getBranch();
            System.out.println("Default branch  : " + (defaultBranch.isEmpty() ? "(none set explicitly)" : defaultBranch));
        }
        try (ProductServiceClient products = ProductServiceClient.create()) {
            for (int branch = 0; branch <= 2; branch++) {
                String parent = ToyCatalog.CATALOG + "/branches/" + branch;
                int count = 0;
                for (Product ignored : products.listProducts(
                        ListProductsRequest.newBuilder().setParent(parent).build()).iterateAll()) {
                    count++;
                }
                System.out.println("Branch " + branch + " size   : " + count + " products");
            }
        }
        System.out.println();
    }

    private static void showUserEventStore() {
        System.out.println("=== Part 4: user event store ===");
        System.out.println("Path            : " + ToyCatalog.CATALOG + "/userEvents");
        System.out.println("Branches        : none (one event stream per catalog)");
        System.out.println("Size            : not readable. The API can write and import events but has no");
        System.out.println("                  list or count call. Check event counts in the Console instead.");
    }
}
