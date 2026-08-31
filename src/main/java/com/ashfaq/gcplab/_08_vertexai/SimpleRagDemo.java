package com.ashfaq.gcplab._08_vertexai;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.google.genai.types.GenerateContentResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Level 2a: RAG (Retrieval-Augmented Generation), built from scratch on
 * top of GeminiPromptDemo's raw call - no managed vector DB, no Vertex AI
 * Search. This is the actual plumbing RAG requires, made deliberately
 * visible instead of hidden behind a managed feature:
 *
 * 1. EMBED a small fixed set of "documents" (short facts about this repo)
 *    into vectors, once, at startup - normally done ahead of time and
 *    stored in a vector DB (pgvector, Firestore, a dedicated vector store);
 *    here it's just an in-memory List for clarity.
 * 2. EMBED the user's question the same way.
 * 3. RETRIEVE the most similar document via cosine similarity (a plain
 *    dot-product/magnitude calculation - no library needed at this scale).
 * 4. GENERATE - stuff the retrieved document into the prompt as context,
 *    ask Gemini to answer USING it. The model has no idea these "facts"
 *    exist otherwise - this is what "grounding" actually means
 *    mechanically.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._08_vertexai.SimpleRagDemo
 */
public final class SimpleRagDemo {

    private static final String EMBEDDING_MODEL = "text-embedding-005";
    private static final String GENERATION_MODEL = "gemini-2.5-flash";

    // Deliberately obscure/made-up facts Gemini could NOT know without retrieval -
    // proves the answer genuinely comes from OUR data, not the model's training.
    private static final List<String> DOCUMENTS = List.of(
            "The gcp-learning-lab project's Cloud SQL instance was named free-trial-first-project.",
            "The bastion VM used to reach Memorystore Redis was named redis-bastion.",
            "The custom IAM role for backend developers in this repo is called backendDeveloper.",
            "The GKE cluster deployed in this repo was named learning-gke and ran in Autopilot mode."
    );

    private SimpleRagDemo() {
    }

    public static void main(String[] args) throws Exception {
        Client client = GeminiPromptDemo.buildClient();

        String question = "What mode did the GKE cluster in this repo run in?";

        List<float[]> documentEmbeddings = new ArrayList<>();
        for (String doc : DOCUMENTS) {
            documentEmbeddings.add(embed(client, doc));
        }
        float[] questionEmbedding = embed(client, question);

        String bestMatch = retrieveMostSimilar(question, questionEmbedding, documentEmbeddings);
        System.out.println("Retrieved context: " + bestMatch);

        String groundedPrompt = """
                Answer the question using ONLY the context below. If the context doesn't
                contain the answer, say you don't know.

                Context: %s

                Question: %s
                """.formatted(bestMatch, question);

        GenerateContentResponse response = client.models.generateContent(GENERATION_MODEL, groundedPrompt, null);
        System.out.println();
        System.out.println("Answer: " + response.text());
    }

    private static float[] embed(Client client, String text) {
        EmbedContentResponse response = client.models.embedContent(
                EMBEDDING_MODEL, text, EmbedContentConfig.builder().build());
        ContentEmbedding embedding = response.embeddings().get().get(0);
        List<Float> values = embedding.values().get();
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static String retrieveMostSimilar(String question, float[] questionEmbedding,
                                               List<float[]> documentEmbeddings) {
        int bestIndex = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < documentEmbeddings.size(); i++) {
            double score = cosineSimilarity(questionEmbedding, documentEmbeddings.get(i));
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return DOCUMENTS.get(bestIndex);
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double magA = 0;
        double magB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            magA += a[i] * a[i];
            magB += b[i] * b[i];
        }
        return dot / (Math.sqrt(magA) * Math.sqrt(magB));
    }
}
