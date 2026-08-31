package com.ashfaq.gcplab._02_identities_bindings;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.iam.v1.Iam;
import com.google.api.services.iam.v1.model.CreateServiceAccountRequest;
import com.google.api.services.iam.v1.model.ServiceAccount;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.util.List;

/**
 * Creates/reads/deletes a service account - the non-human identity we will
 * bind the backendDeveloper role to. Same create/get/delete shape as
 * IamRoleLifecycleDemo in _01_iam, different API surface
 * (projects().serviceAccounts() instead of projects().roles()).
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._02_identities_bindings.ServiceAccountDemo -Dexec.args=create
 *   ... -Dexec.args=get
 *   ... -Dexec.args=delete
 */
public final class ServiceAccountDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String ACCOUNT_ID = "backend-dev-sa";
    static final String ACCOUNT_EMAIL = ACCOUNT_ID + "@" + PROJECT_ID + ".iam.gserviceaccount.com";
    private static final String RESOURCE_NAME = "projects/" + PROJECT_ID + "/serviceAccounts/" + ACCOUNT_EMAIL;

    private ServiceAccountDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("create", "get", "delete").contains(args[0])) {
            System.out.println("Usage: ServiceAccountDemo <create|get|delete>");
            return;
        }

        Iam iam = buildIamClient();

        switch (args[0]) {
            case "create" -> createServiceAccount(iam);
            case "get" -> getServiceAccount(iam);
            case "delete" -> deleteServiceAccount(iam);
            default -> throw new IllegalStateException("unreachable");
        }
    }

    static Iam buildIamClient() throws Exception {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        return new Iam.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("gcp-learning-lab")
                .build();
    }

    private static void createServiceAccount(Iam iam) throws Exception {
        ServiceAccount accountDefinition = new ServiceAccount()
                .setDisplayName("Backend Developer SA")
                .setDescription("App identity bound to the backendDeveloper custom role");

        CreateServiceAccountRequest request = new CreateServiceAccountRequest()
                .setAccountId(ACCOUNT_ID)
                .setServiceAccount(accountDefinition);

        ServiceAccount created = iam.projects().serviceAccounts()
                .create("projects/" + PROJECT_ID, request)
                .execute();

        System.out.println("Created service account: " + created.getEmail());
        System.out.println("Unique ID: " + created.getUniqueId());
    }

    private static void getServiceAccount(Iam iam) throws Exception {
        ServiceAccount account = iam.projects().serviceAccounts().get(RESOURCE_NAME).execute();
        System.out.println("Service account: " + account.getEmail());
        System.out.println("Display name: " + account.getDisplayName());
        System.out.println("Disabled: " + Boolean.TRUE.equals(account.getDisabled()));
    }

    private static void deleteServiceAccount(Iam iam) throws Exception {
        iam.projects().serviceAccounts().delete(RESOURCE_NAME).execute();
        System.out.println("Deleted service account: " + ACCOUNT_EMAIL);
        System.out.println("Note: unlike custom roles, service account deletion is NOT soft-deleted");
        System.out.println("by default here - undelete is only possible within a short grace window.");
    }
}
