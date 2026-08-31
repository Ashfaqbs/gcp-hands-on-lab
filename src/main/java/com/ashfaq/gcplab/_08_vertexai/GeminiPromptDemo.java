package com.ashfaq.gcplab._08_vertexai;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;

import java.util.List;

/**
 * Level 1: a raw model call, nothing else. No RAG, no tools, no agent loop
 * - just prompt in, text out. Everything else in this package builds on
 * top of this same primitive. Runs impersonating data-ml-sa (bound to the
 * dataMlEngineer custom role from _01_iam, first real use of that role).
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._08_vertexai.GeminiPromptDemo
 */
public final class GeminiPromptDemo {

    private static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    private static final String LOCATION = "us-central1";
    private static final String MODEL = "gemini-2.5-flash";
    private static final String SERVICE_ACCOUNT_EMAIL =
            "data-ml-sa@" + PROJECT_ID + ".iam.gserviceaccount.com";

    private GeminiPromptDemo() {
    }

    public static void main(String[] args) throws Exception {
        Client client = buildClient();

        GenerateContentResponse response = client.models.generateContent(
                MODEL,
                "Explain what GCP Vertex AI is, in exactly two sentences.",
                null);

        System.out.println(response.text());
    }

    static Client buildClient() throws Exception {
        GoogleCredentials sourceCredentials = GoogleCredentials.getApplicationDefault();
        ImpersonatedCredentials impersonated = ImpersonatedCredentials.create(
                sourceCredentials,
                SERVICE_ACCOUNT_EMAIL,
                null,
                List.of("https://www.googleapis.com/auth/cloud-platform"),
                300);

        return Client.builder()
                .project(PROJECT_ID)
                .location(LOCATION)
                .vertexAI(true)
                .credentials(impersonated)
                .build();
    }
}
