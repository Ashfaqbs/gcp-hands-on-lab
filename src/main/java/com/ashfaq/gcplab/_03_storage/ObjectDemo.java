package com.ashfaq.gcplab._03_storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Object-level operations (upload/download/list/delete), run IMPERSONATING
 * backend-dev-sa - proving the storage.objects.* permissions granted by
 * backendDeveloper (see _01_iam, _02_identities_bindings) actually work for
 * real object operations, not just the abstract testIamPermissions check.
 *
 * Requires backend-dev-sa to exist and hold backendDeveloper, and your ADC
 * user to hold roles/iam.serviceAccountTokenCreator on it (set up in
 * _02_identities_bindings).
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._03_storage.ObjectDemo -Dexec.args=upload
 *   ... -Dexec.args=list
 *   ... -Dexec.args=download
 *   ... -Dexec.args=delete
 */
public final class ObjectDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";
    private static final String OBJECT_KEY = "hello.txt";
    private static final String OBJECT_CONTENT = "Hello from backend-dev-sa, via backendDeveloper role.";

    private ObjectDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("upload", "list", "download", "delete").contains(args[0])) {
            System.out.println("Usage: ObjectDemo <upload|list|download|delete>");
            return;
        }

        Storage storage = buildImpersonatedStorageClient();

        switch (args[0]) {
            case "upload" -> upload(storage);
            case "list" -> list(storage);
            case "download" -> download(storage);
            case "delete" -> delete(storage);
            default -> throw new IllegalStateException("unreachable");
        }
    }

    private static Storage buildImpersonatedStorageClient() throws Exception {
        GoogleCredentials sourceCredentials = GoogleCredentials.getApplicationDefault();

        ImpersonatedCredentials impersonated = ImpersonatedCredentials.create(
                sourceCredentials,
                SERVICE_ACCOUNT_EMAIL,
                null,
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                300);

        return StorageOptions.newBuilder()
                .setProjectId(PROJECT_ID)
                .setCredentials(impersonated)
                .build()
                .getService();
    }

    private static void upload(Storage storage) {
        BlobId blobId = BlobId.of(BucketDemo.BUCKET_NAME, OBJECT_KEY);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("text/plain").build();

        Blob blob = storage.create(blobInfo, OBJECT_CONTENT.getBytes(StandardCharsets.UTF_8));

        System.out.println("Uploaded as backend-dev-sa: gs://" + BucketDemo.BUCKET_NAME + "/" + blob.getName());
        System.out.println("Size: " + blob.getSize() + " bytes");
    }

    private static void list(Storage storage) {
        System.out.println("Objects in gs://" + BucketDemo.BUCKET_NAME + " (listed as backend-dev-sa):");
        for (Blob blob : storage.list(BucketDemo.BUCKET_NAME).iterateAll()) {
            System.out.println("  " + blob.getName() + " (" + blob.getSize() + " bytes)");
        }
    }

    private static void download(Storage storage) {
        Blob blob = storage.get(BlobId.of(BucketDemo.BUCKET_NAME, OBJECT_KEY));
        if (blob == null) {
            System.out.println("Object " + OBJECT_KEY + " not found.");
            return;
        }
        String content = new String(blob.getContent(), StandardCharsets.UTF_8);
        System.out.println("Downloaded as backend-dev-sa: " + content);
    }

    private static void delete(Storage storage) {
        boolean deleted = storage.delete(BlobId.of(BucketDemo.BUCKET_NAME, OBJECT_KEY));
        System.out.println(deleted
                ? "Deleted object as backend-dev-sa: " + OBJECT_KEY
                : "Object " + OBJECT_KEY + " did not exist.");
    }
}
