package com.ashfaq.gcplab._01_iam;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.iam.v1.Iam;
import com.google.api.services.iam.v1.model.Role;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds Firestore permissions to the REAL backendDeveloper role - the
 * missing CRUD verb we never exercised on roles (IamRoleLifecycleDemo only
 * covers create/get/delete, on a throwaway role). This is a targeted patch,
 * not a full role rewrite: fetch current permissions, add new ones,
 * PATCH with an update mask so only includedPermissions changes.
 *
 * Firestore's IAM permissions still use the "datastore.*" namespace - a
 * naming holdover from when Firestore was Cloud Datastore.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._01_iam.UpdateRoleDemo
 */
public final class UpdateRoleDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String ROLE_NAME = "projects/" + PROJECT_ID + "/roles/backendDeveloper";

    private static final List<String> FIRESTORE_PERMISSIONS = List.of(
            "datastore.entities.get",
            "datastore.entities.create",
            "datastore.entities.update",
            "datastore.entities.delete",
            "datastore.entities.list"
    );

    private UpdateRoleDemo() {
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

        Role current = iam.projects().roles().get(ROLE_NAME).execute();
        System.out.println("Before: " + current.getIncludedPermissions());

        Set<String> updatedPermissions = new LinkedHashSet<>(current.getIncludedPermissions());
        updatedPermissions.addAll(FIRESTORE_PERMISSIONS);

        Role patch = new Role().setIncludedPermissions(new ArrayList<>(updatedPermissions));

        Role updated = iam.projects().roles().patch(ROLE_NAME, patch)
                .setUpdateMask("includedPermissions")
                .execute();

        System.out.println("After:  " + updated.getIncludedPermissions());
    }
}
