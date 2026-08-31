package com.ashfaq.gcplab._06_firestore;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;

import java.util.List;
import java.util.Map;

/**
 * CRUD against the "employees" collection, via Firestore's own native Java
 * SDK (google-cloud-firestore) - IMPERSONATING backend-dev-sa, same
 * pattern as ObjectDemo in _03_storage, so this actually runs as "the app"
 * rather than as your own human login. Requires backendDeveloper to hold
 * datastore.entities.* (added via UpdateRoleDemo in _01_iam - the role's
 * first PATCH, not just create/get/delete) and your ADC user to hold
 * roles/iam.serviceAccountTokenCreator on backend-dev-sa (set up in
 * _02_identities_bindings).
 *
 * No schema was pre-created (unlike _04_cloudsql's CREATE TABLE) - the
 * collection is created implicitly by the first document write, the
 * defining trait of a document DB.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._06_firestore.EmployeeDocCrudDemo -Dexec.args=create
 *   ... -Dexec.args=read
 *   ... -Dexec.args=update
 *   ... -Dexec.args=delete
 */
public final class EmployeeDocCrudDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String DATABASE_ID = "learning-native";
    private static final String COLLECTION = "employees";
    private static final String EMPLOYEE_ID = "emp-1";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";

    private EmployeeDocCrudDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("create", "read", "update", "delete").contains(args[0])) {
            System.out.println("Usage: EmployeeDocCrudDemo <create|read|update|delete>");
            return;
        }

        GoogleCredentials sourceCredentials = GoogleCredentials.getApplicationDefault();
        ImpersonatedCredentials impersonated = ImpersonatedCredentials.create(
                sourceCredentials,
                SERVICE_ACCOUNT_EMAIL,
                null,
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                300);

        Firestore firestore = FirestoreOptions.newBuilder()
                .setProjectId(PROJECT_ID)
                .setDatabaseId(DATABASE_ID)
                .setCredentialsProvider(FixedCredentialsProvider.create(impersonated))
                .build()
                .getService();

        try {
            DocumentReference doc = firestore.collection(COLLECTION).document(EMPLOYEE_ID);

            switch (args[0]) {
                case "create" -> create(doc);
                case "read" -> read(doc);
                case "update" -> update(doc);
                case "delete" -> delete(doc);
                default -> throw new IllegalStateException("unreachable");
            }
        } finally {
            firestore.close();
        }
    }

    private static void create(DocumentReference doc) throws Exception {
        Map<String, Object> data = Map.of(
                "name", "Ashfaq",
                "role", "Backend Developer",
                "skills", List.of("Java", "Spring Boot", "GCP"));

        doc.set(data).get(); // .get() blocks until the write completes (API is async by default)
        System.out.println("Created document: " + doc.getPath());
    }

    private static void read(DocumentReference doc) throws Exception {
        DocumentSnapshot snapshot = doc.get().get();
        System.out.println(snapshot.exists()
                ? "Found: " + snapshot.getData()
                : "Document " + EMPLOYEE_ID + " not found.");
    }

    private static void update(DocumentReference doc) throws Exception {
        doc.update("role", "Senior Backend Developer").get();
        System.out.println("Updated role field on " + doc.getPath());
    }

    private static void delete(DocumentReference doc) throws Exception {
        doc.delete().get();
        System.out.println("Deleted document: " + doc.getPath());
    }
}
