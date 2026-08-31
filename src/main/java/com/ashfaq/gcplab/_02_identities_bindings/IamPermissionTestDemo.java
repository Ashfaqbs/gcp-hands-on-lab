package com.ashfaq.gcplab._02_identities_bindings;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.cloud.resourcemanager.v3.ProjectsSettings;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;

import java.util.List;
import java.util.TreeSet;

/**
 * Proves the backendDeveloper binding on backend-dev-sa actually restricts
 * access, instead of just trusting the policy we wrote. Impersonates the
 * service account (via ImpersonatedCredentials - your own ADC user briefly
 * "acts as" the SA) and asks GCP which of a mixed list of permissions the
 * SA genuinely holds.
 *
 * Requires your ADC user to hold roles/iam.serviceAccountTokenCreator on
 * backend-dev-sa (Owner already includes this).
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._02_identities_bindings.IamPermissionTestDemo
 */
public final class IamPermissionTestDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";

    // Deliberately mixed: some ARE in backendDeveloper's permission list, some are NOT.
    private static final List<String> PERMISSIONS_TO_CHECK = List.of(
            "storage.objects.get",              // granted
            "storage.objects.create",           // granted
            "cloudsql.instances.connect",       // granted
            "compute.instances.delete",         // NOT granted
            "iam.roles.create",                 // NOT granted
            "resourcemanager.projects.setIamPolicy" // NOT granted
    );

    private IamPermissionTestDemo() {
    }

    public static void main(String[] args) throws Exception {
        GoogleCredentials sourceCredentials = GoogleCredentials.getApplicationDefault();

        ImpersonatedCredentials impersonated = ImpersonatedCredentials.create(
                sourceCredentials,
                ServiceAccountDemo.ACCOUNT_EMAIL,
                null,
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                300);

        ProjectsSettings settings = ProjectsSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(impersonated))
                .build();

        try (ProjectsClient client = ProjectsClient.create(settings)) {
            TestIamPermissionsResponse response = client.testIamPermissions(
                    TestIamPermissionsRequest.newBuilder()
                            .setResource("projects/" + PROJECT_ID)
                            .addAllPermissions(PERMISSIONS_TO_CHECK)
                            .build());

            TreeSet<String> granted = new TreeSet<>(response.getPermissionsList());
            TreeSet<String> denied = new TreeSet<>(PERMISSIONS_TO_CHECK);
            denied.removeAll(granted);

            System.out.println("Testing as: " + ServiceAccountDemo.ACCOUNT_EMAIL);
            System.out.println();
            System.out.println("GRANTED (backend-dev-sa can do these): " + granted);
            System.out.println("DENIED  (backend-dev-sa cannot do these): " + denied);
        }
    }
}
