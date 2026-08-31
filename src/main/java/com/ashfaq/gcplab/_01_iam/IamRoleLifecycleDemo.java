package com.ashfaq.gcplab._01_iam;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.iam.v1.Iam;
import com.google.api.services.iam.v1.model.CreateRoleRequest;
import com.google.api.services.iam.v1.model.Role;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.util.List;

/**
 * Demonstrates creating and deleting a custom IAM role via the IAM Admin
 * API, instead of the console UI. Operates on a throwaway role so it is
 * safe to run repeatedly.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab.iam.IamRoleLifecycleDemo -Dexec.args=create
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab.iam.IamRoleLifecycleDemo -Dexec.args=get
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab.iam.IamRoleLifecycleDemo -Dexec.args=delete
 *
 * Requires Application Default Credentials (gcloud auth application-default login).
 */
public final class IamRoleLifecycleDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String ROLE_ID = "dummyTestRole";
    private static final String PARENT = "projects/" + PROJECT_ID;
    private static final String ROLE_NAME = PARENT + "/roles/" + ROLE_ID;

    private IamRoleLifecycleDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("create", "get", "delete").contains(args[0])) {
            System.out.println("Usage: IamRoleLifecycleDemo <create|get|delete>");
            return;
        }

        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");

        Iam iam = new Iam.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("gcp-learning-lab")
                .build();

        switch (args[0]) {
            case "create" -> createRole(iam);
            case "get" -> getRole(iam);
            case "delete" -> deleteRole(iam);
            default -> throw new IllegalStateException("unreachable");
        }
    }

    private static void createRole(Iam iam) throws Exception {
        Role roleDefinition = new Role()
                .setTitle("Dummy Test Role")
                .setDescription("Throwaway role created via IAM Admin API to prove code-based role creation works")
                .setIncludedPermissions(List.of("logging.logEntries.list"))
                .setStage("GA");

        CreateRoleRequest request = new CreateRoleRequest()
                .setRoleId(ROLE_ID)
                .setRole(roleDefinition);

        Role created = iam.projects().roles().create(PARENT, request).execute();

        System.out.println("Created role: " + created.getName());
        System.out.println("Title: " + created.getTitle());
        System.out.println("Permissions: " + created.getIncludedPermissions());
    }

    private static void getRole(Iam iam) throws Exception {
        Role role = iam.projects().roles().get(ROLE_NAME).execute();
        System.out.println("Role: " + role.getName());
        System.out.println("Title: " + role.getTitle());
        System.out.println("Stage: " + role.getStage());
        System.out.println("Deleted: " + Boolean.TRUE.equals(role.getDeleted()));
        System.out.println("Permissions: " + role.getIncludedPermissions());
    }

    private static void deleteRole(Iam iam) throws Exception {
        iam.projects().roles().delete(ROLE_NAME).execute();
        System.out.println("Deleted (soft-delete) role: " + ROLE_NAME);
        System.out.println("Note: IAM soft-deletes custom roles - it stays visible/undeletable-recoverable");
        System.out.println("for ~7 days before being permanently purged by GCP.");
    }
}
