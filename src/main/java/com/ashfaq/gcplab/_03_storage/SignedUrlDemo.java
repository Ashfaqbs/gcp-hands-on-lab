package com.ashfaq.gcplab._03_storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generates a V4 signed URL - a time-limited, pre-authorized link that lets
 * ANYONE holding it upload or download one specific object directly against
 * GCS, without ever going through your backend or needing their own GCP
 * credentials. This is the single most common real-world GCS pattern
 * ({@link BucketDemo}/{@link ObjectDemo} prove the API works; this is what
 * a real frontend/mobile client actually uses it for) and was entirely
 * absent from this module until now - see package-info.java's Production
 * practices section for why it matters.
 *
 * Signing works here WITHOUT a downloaded JSON key file - it goes through
 * the same impersonation pattern as every other demo in this repo:
 * {@code Storage.signUrl(...)} detects that the credential is an
 * {@link ImpersonatedCredentials} and transparently calls the IAM
 * Credentials API's {@code signBlob} RPC to sign, rather than needing a
 * private key locally. Requires the caller's ADC identity to hold
 * {@code roles/iam.serviceAccountTokenCreator} on backend-dev-sa (same
 * grant every impersonation demo in this repo already depends on) PLUS
 * backend-dev-sa itself needs {@code iam.serviceAccounts.signBlob} - added
 * to backendDeveloper alongside every other permission this repo's roles
 * have accumulated.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._03_storage.SignedUrlDemo -Dexec.args=upload
 *   ... -Dexec.args=download
 */
public final class SignedUrlDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "backend-dev-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";
    private static final String OBJECT_KEY = "signed-url-demo.txt";
    private static final String CONTENT = "Uploaded through a signed URL - no ADC/impersonation on the client side.";

    private SignedUrlDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("upload", "download").contains(args[0])) {
            System.out.println("Usage: SignedUrlDemo <upload|download>");
            return;
        }

        GoogleCredentials sourceCredentials = GoogleCredentials.getApplicationDefault();
        ImpersonatedCredentials impersonated = ImpersonatedCredentials.create(
                sourceCredentials,
                SERVICE_ACCOUNT_EMAIL,
                null,
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                300);

        Storage storage = StorageOptions.newBuilder()
                .setProjectId(PROJECT_ID)
                .setCredentials(impersonated)
                .build()
                .getService();

        if (args[0].equals("upload")) {
            uploadViaSignedUrl(storage);
        } else {
            downloadViaSignedUrl(storage);
        }
    }

    private static void uploadViaSignedUrl(Storage storage) throws Exception {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BucketDemo.BUCKET_NAME, OBJECT_KEY))
                .setContentType("text/plain")
                .build();

        // A real signed PUT URL must be generated with the SAME content-type the
        // client will actually send - GCS validates the signature against it.
        URI signedUploadUrl = storage.signUrl(blobInfo, 10, TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.withContentType()).toURI();

        System.out.println("Signed PUT URL (valid 10 min): " + signedUploadUrl);

        // Prove it actually works: a plain HttpClient PUT, no GCP credentials at all -
        // this is exactly what a browser/mobile client would do with the URL.
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(signedUploadUrl)
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(CONTENT, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Upload via signed URL - HTTP status: " + response.statusCode()
                + (response.statusCode() == 200 ? " (success, no credentials used client-side)" : " (failed)"));
    }

    private static void downloadViaSignedUrl(Storage storage) throws Exception {
        BlobId blobId = BlobId.of(BucketDemo.BUCKET_NAME, OBJECT_KEY);

        URI signedDownloadUrl = storage.signUrl(BlobInfo.newBuilder(blobId).build(), 10, TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                Storage.SignUrlOption.withV4Signature()).toURI();

        System.out.println("Signed GET URL (valid 10 min): " + signedDownloadUrl);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(signedDownloadUrl).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Download via signed URL - HTTP status: " + response.statusCode());
        System.out.println("Body: " + response.body());
    }
}
