package com.ashfaq.gcplab._03_storage;

import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageClass;
import com.google.cloud.storage.StorageOptions;

import java.util.List;

/**
 * Bucket-level admin operations, run as YOUR OWN credentials (ADC) - not
 * backend-dev-sa, since backendDeveloper deliberately excludes
 * storage.buckets.* permissions (see package-info).
 *
 * Bucket names are globally unique across all of GCP, so this uses the
 * project ID as a prefix to avoid collisions with anyone else's buckets.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._03_storage.BucketDemo -Dexec.args=create
 *   ... -Dexec.args=get
 *   ... -Dexec.args=delete
 */
public final class BucketDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    static final String BUCKET_NAME = PROJECT_ID + "-learning-bucket";

    // Always Free tier for Standard storage only applies in these US regions.
    private static final String FREE_TIER_LOCATION = "US-CENTRAL1";

    private BucketDemo() {
    }

    public static void main(String[] args) {
        if (args.length != 1 || !List.of("create", "get", "delete").contains(args[0])) {
            System.out.println("Usage: BucketDemo <create|get|delete>");
            return;
        }

        Storage storage = StorageOptions.newBuilder().setProjectId(PROJECT_ID).build().getService();

        switch (args[0]) {
            case "create" -> createBucket(storage);
            case "get" -> getBucket(storage);
            case "delete" -> deleteBucket(storage);
            default -> throw new IllegalStateException("unreachable");
        }
    }

    private static void createBucket(Storage storage) {
        BucketInfo bucketInfo = BucketInfo.newBuilder(BUCKET_NAME)
                .setLocation(FREE_TIER_LOCATION)
                .setStorageClass(StorageClass.STANDARD)
                .setIamConfiguration(BucketInfo.IamConfiguration.newBuilder()
                        .setIsUniformBucketLevelAccessEnabled(true)
                        .build())
                .build();

        Bucket bucket = storage.create(bucketInfo);

        System.out.println("Created bucket: " + bucket.getName());
        System.out.println("Location: " + bucket.getLocation());
        System.out.println("Storage class: " + bucket.getStorageClass());
        System.out.println("Uniform bucket-level access: "
                + bucket.getIamConfiguration().isUniformBucketLevelAccessEnabled());
    }

    private static void getBucket(Storage storage) {
        Bucket bucket = storage.get(BUCKET_NAME);
        if (bucket == null) {
            System.out.println("Bucket " + BUCKET_NAME + " does not exist.");
            return;
        }
        System.out.println("Bucket: " + bucket.getName());
        System.out.println("Location: " + bucket.getLocation());
        System.out.println("Storage class: " + bucket.getStorageClass());
        System.out.println("Created: " + bucket.getCreateTimeOffsetDateTime());
    }

    private static void deleteBucket(Storage storage) {
        Bucket bucket = storage.get(BUCKET_NAME);
        if (bucket == null) {
            System.out.println("Bucket " + BUCKET_NAME + " does not exist - nothing to delete.");
            return;
        }
        boolean deleted = bucket.delete();
        System.out.println(deleted
                ? "Deleted bucket: " + BUCKET_NAME
                : "Delete failed - bucket may still contain objects (delete objects first).");
    }
}
