package com.ashfaq.gcplab._02_identities_bindings;

import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.cloud.resourcemanager.v3.ProjectsSettings;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Binds/unbinds/lists the backendDeveloper custom role against the
 * backend-dev-sa service account, on the PROJECT resource. This is a
 * different API (Resource Manager) from the IAM Admin API used in
 * _01_iam and ServiceAccountDemo, because the policy being edited belongs
 * to the project (the resource being protected), not to the role or the
 * identity.
 *
 * Uses the standard read-modify-write pattern: getIamPolicy -> mutate the
 * bindings list in memory -> setIamPolicy with the same etag, so a
 * concurrent edit elsewhere is detected instead of silently overwritten.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._02_identities_bindings.IamBindingDemo -Dexec.args=bind
 *   ... -Dexec.args=list
 *   ... -Dexec.args=unbind
 */
public final class IamBindingDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String CUSTOM_ROLE = "projects/" + PROJECT_ID + "/roles/backendDeveloper";
    private static final String MEMBER = "serviceAccount:" + ServiceAccountDemo.ACCOUNT_EMAIL;

    private IamBindingDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("bind", "unbind", "list").contains(args[0])) {
            System.out.println("Usage: IamBindingDemo <bind|unbind|list>");
            return;
        }

        try (ProjectsClient client = ProjectsClient.create(ProjectsSettings.newBuilder().build())) {
            switch (args[0]) {
                case "bind" -> bind(client);
                case "unbind" -> unbind(client);
                case "list" -> list(client);
                default -> throw new IllegalStateException("unreachable");
            }
        }
    }

    private static void bind(ProjectsClient client) {
        String resource = "projects/" + PROJECT_ID;
        Policy currentPolicy = client.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build());

        Optional<Binding> existingBindingForRole = currentPolicy.getBindingsList().stream()
                .filter(b -> b.getRole().equals(CUSTOM_ROLE))
                .findFirst();

        Policy.Builder newPolicy = currentPolicy.toBuilder();

        if (existingBindingForRole.isPresent()) {
            int index = currentPolicy.getBindingsList().indexOf(existingBindingForRole.get());
            Binding updated = existingBindingForRole.get().toBuilder().addMembers(MEMBER).build();
            newPolicy.setBindings(index, updated);
        } else {
            newPolicy.addBindings(Binding.newBuilder()
                    .setRole(CUSTOM_ROLE)
                    .addMembers(MEMBER)
                    .build());
        }

        Policy result = client.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource(resource)
                .setPolicy(newPolicy.build())
                .build());

        System.out.println("Bound " + MEMBER + " to " + CUSTOM_ROLE);
        System.out.println("New policy etag: " + result.getEtag());
    }

    private static void unbind(ProjectsClient client) {
        String resource = "projects/" + PROJECT_ID;
        Policy currentPolicy = client.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build());

        List<Binding> updatedBindings = new ArrayList<>();
        boolean removed = false;
        for (Binding b : currentPolicy.getBindingsList()) {
            if (b.getRole().equals(CUSTOM_ROLE) && b.getMembersList().contains(MEMBER)) {
                List<String> remainingMembers = new ArrayList<>(b.getMembersList());
                remainingMembers.remove(MEMBER);
                removed = true;
                if (!remainingMembers.isEmpty()) {
                    updatedBindings.add(b.toBuilder().clearMembers().addAllMembers(remainingMembers).build());
                }
                // if no members remain, drop the binding entirely (don't re-add)
            } else {
                updatedBindings.add(b);
            }
        }

        if (!removed) {
            System.out.println(MEMBER + " was not bound to " + CUSTOM_ROLE + " - nothing to do.");
            return;
        }

        Policy newPolicy = currentPolicy.toBuilder()
                .clearBindings()
                .addAllBindings(updatedBindings)
                .build();

        client.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource(resource)
                .setPolicy(newPolicy)
                .build());

        System.out.println("Unbound " + MEMBER + " from " + CUSTOM_ROLE);
    }

    private static void list(ProjectsClient client) {
        String resource = "projects/" + PROJECT_ID;
        Policy policy = client.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build());

        System.out.println("IAM policy bindings on " + resource + ":");
        for (Binding b : policy.getBindingsList()) {
            System.out.println("  " + b.getRole());
            for (String member : b.getMembersList()) {
                System.out.println("    - " + member);
            }
        }
    }
}
