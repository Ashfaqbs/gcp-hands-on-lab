package com.ashfaq.gcplab._01_iam;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.iam.v1.Iam;
import com.google.api.services.iam.v1.model.Role;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Standalone check (not wired into the Spring app context) that reads every
 * custom project role back via the IAM Admin API and diffs its permissions
 * against what we expect, so role creation can be verified without relying
 * on the console UI.
 *
 * Run with: mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab.iam.IamRoleVerifier
 * Requires Application Default Credentials (gcloud auth application-default login).
 */
public final class IamRoleVerifier {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";

    private static final Map<String, Set<String>> EXPECTED_ROLES = new LinkedHashMap<>();

    static {
        EXPECTED_ROLES.put("backendDeveloper", Set.of(
                "cloudsql.instances.get",
                "cloudsql.instances.connect",
                "storage.objects.create",
                "storage.objects.get",
                "storage.objects.list",
                "storage.objects.delete",
                "logging.logEntries.list"
        ));
        EXPECTED_ROLES.put("dataMlEngineer", Set.of(
                "aiplatform.endpoints.predict",
                "storage.objects.get",
                "storage.objects.list",
                "bigquery.tables.getData"
        ));
        EXPECTED_ROLES.put("siteReliabilityEngineer", Set.of(
                "container.pods.get",
                "container.pods.list",
                "compute.instances.get",
                "compute.instances.reset",
                "monitoring.timeSeries.list",
                "logging.logEntries.list"
        ));
        EXPECTED_ROLES.put("securityAdmin", Set.of(
                "resourcemanager.projects.getIamPolicy",
                "resourcemanager.projects.setIamPolicy",
                "iam.roles.get",
                "iam.roles.list"
        ));
    }

    private IamRoleVerifier() {
    }

    public static void main(String[] args) throws Exception {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");

        Iam iam = new Iam.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("gcp-learning-lab")
                .build();

        boolean allMatched = true;
        for (Map.Entry<String, Set<String>> entry : EXPECTED_ROLES.entrySet()) {
            allMatched &= verifyRole(iam, entry.getKey(), entry.getValue());
        }

        System.out.println();
        System.out.println(allMatched
                ? "ALL ROLES MATCH expected permissions."
                : "One or more roles DID NOT MATCH — see MISSING/UNEXPECTED above.");
    }

    private static boolean verifyRole(Iam iam, String roleId, Set<String> expectedPermissions) throws Exception {
        String roleName = "projects/%s/roles/%s".formatted(PROJECT_ID, roleId);
        Role role;
        try {
            role = iam.projects().roles().get(roleName).execute();
        } catch (Exception e) {
            System.out.println("Role: " + roleId + " -> NOT FOUND (" + e.getMessage() + ")");
            return false;
        }

        Set<String> actual = new TreeSet<>(
                role.getIncludedPermissions() == null ? List.of() : role.getIncludedPermissions());
        Set<String> expected = new TreeSet<>(expectedPermissions);

        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);

        boolean matched = missing.isEmpty() && unexpected.isEmpty();

        System.out.println("Role: " + roleId + " (" + role.getTitle() + ") -> "
                + (matched ? "MATCH" : "MISMATCH"));
        if (!matched) {
            if (!missing.isEmpty()) {
                System.out.println("  MISSING:    " + missing);
            }
            if (!unexpected.isEmpty()) {
                System.out.println("  UNEXPECTED: " + unexpected);
            }
        }
        return matched;
    }
}
