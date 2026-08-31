package com.ashfaq.gcplab._08_vertexai;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Level 2b: a minimal agent - the tool-calling loop, hand-written (no ADK)
 * so every step is visible. One tool: multiply(a, b) - deliberately
 * something Gemini could get wrong by "guessing" on large numbers, so a
 * correct answer proves the tool was actually invoked, not just recited
 * from training data.
 *
 * The loop:
 * 1. Send the prompt + a Tool declaration describing multiply(a, b).
 * 2. Model responds with a FunctionCall instead of text (it "decided" to
 *    use the tool rather than answer directly).
 * 3. WE execute the function in plain Java - the model never runs code
 *    itself, it only ever asks.
 * 4. Send the result back as a FunctionResponse, continuing the same
 *    conversation (both turns included in the next request).
 * 5. Model produces a final text answer using the tool's result.
 *
 * Run with:
 *   mvn exec:java -Dexec.mainClass=com.ashfaq.gcplab._08_vertexai.SimpleAgentDemo
 */
public final class SimpleAgentDemo {

    private static final String MODEL = "gemini-2.5-flash";

    private SimpleAgentDemo() {
    }

    public static void main(String[] args) throws Exception {
        Client client = GeminiPromptDemo.buildClient();

        FunctionDeclaration multiply = FunctionDeclaration.builder()
                .name("multiply")
                .description("Multiplies two numbers and returns the exact product.")
                .parameters(Schema.builder()
                        .type(Type.Known.OBJECT)
                        .properties(Map.of(
                                "a", Schema.builder().type(Type.Known.NUMBER).build(),
                                "b", Schema.builder().type(Type.Known.NUMBER).build()))
                        .required(List.of("a", "b"))
                        .build())
                .build();

        GenerateContentConfig config = GenerateContentConfig.builder()
                .tools(List.of(Tool.builder().functionDeclarations(List.of(multiply)).build()))
                .build();

        String question = "What is 48213 multiplied by 7791? Use the multiply tool, don't estimate.";

        List<Content> conversation = new ArrayList<>();
        conversation.add(Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(question)))
                .build());

        GenerateContentResponse first = client.models.generateContent(MODEL, conversation, config);

        List<FunctionCall> functionCalls = first.functionCalls();
        if (functionCalls.isEmpty()) {
            System.out.println("Model answered directly (no tool call): " + first.text());
            return;
        }

        FunctionCall call = functionCalls.get(0);
        System.out.println("Model requested tool call: " + call.name().get() + call.args().get());

        double a = ((Number) call.args().get().get("a")).doubleValue();
        double b = ((Number) call.args().get().get("b")).doubleValue();
        double result = multiply(a, b);
        System.out.println("Java executed multiply(" + a + ", " + b + ") = " + result);

        conversation.add(Content.builder()
                .role("model")
                .parts(List.of(Part.fromFunctionCall(call.name().get(), call.args().get())))
                .build());
        conversation.add(Content.builder()
                .role("user")
                .parts(List.of(Part.fromFunctionResponse(
                        call.name().get(),
                        Map.of("result", result))))
                .build());

        GenerateContentResponse second = client.models.generateContent(MODEL, conversation, config);
        System.out.println();
        System.out.println("Final answer: " + second.text());
    }

    private static double multiply(double a, double b) {
        return a * b;
    }
}
