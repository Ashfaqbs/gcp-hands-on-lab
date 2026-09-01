/**
 * Reading order: 08 (comes after _01_iam ... _07_gke).
 *
 * <h2>"Vertex AI" was rebranded to "Gemini Enterprise Agent Platform"</h2>
 * At Google Cloud Next 2026, Google folded Vertex AI (Model Garden, custom
 * training, AutoML, Model Registry, Endpoints, Pipelines) and Vertex AI
 * Agent Builder together and rebranded the whole thing. Searching "Vertex
 * AI" in Console now redirects to Gemini Enterprise - the old name no
 * longer resolves to its own product page. Package name kept as
 * {@code _08_vertexai} anyway since that's still the concept/capability
 * being learned (and what most existing tutorials/docs still call it),
 * just noting the rename so it's not confusing when Console doesn't match
 * older material.
 *
 * <h2>Two DIFFERENT products confusingly share "Gemini Enterprise" branding</h2>
 * Verified by visiting both directly:
 * <ul>
 *   <li>{@code console.cloud.google.com/gemini-enterprise/products} - a
 *       SEPARATE subscription-based SaaS product (enterprise search/agents
 *       across connected work apps - Apps, Data stores, Manage users nav).
 *       Offers its own "Start 30-day free trial" - almost certainly a
 *       per-seat licensed product, NOT what we want, did not click it.</li>
 *   <li>{@code console.cloud.google.com/vertex-ai} - redirects to
 *       {@code console.cloud.google.com/agent-platform/overview}, which
 *       explicitly says "Vertex AI is now Agent Platform." THIS is the
 *       developer platform - confirmed by left nav: <b>Overview, Studio,
 *       Models, Agents, Notebooks</b>, plus a code sample using
 *       {@code google.genai} (the modern unified Python SDK) and an
 *       "Enable APIs" button (turns on aiplatform.googleapis.com, free to
 *       enable, billed per actual usage after).</li>
 * </ul>
 * The old {@code /vertex-ai} URL still works as a redirect, which is the
 * most reliable way to land on the right page - typing "Gemini Enterprise"
 * into Console search is ambiguous between the two products.
 *
 * <h2>Billing / Cost</h2>
 * No ongoing free tier via the GCP Console path - pay-per-token, but
 * covered by this project's existing $300/90-day free trial credit, no
 * separate billing setup needed. A DIFFERENT zero-setup path exists
 * outside GCP entirely - Google AI Studio / a plain Gemini API key, ~1,000
 * free requests/day, no GCP project or IAM at all - worth knowing about
 * but not what we're using here, since the whole point of this repo is
 * learning the GCP-project-scoped, IAM-integrated path.
 * <p>
 * Pricing is per-token, split into input and output tokens (output costs
 * more per token than input), and varies by model (the fast/cheap tier vs.
 * the higher-quality tier). RAG adds embedding-generation cost on top (also
 * per-token, a separate much cheaper rate) for every chunk embedded at
 * index time AND every query embedded at search time. Formula:
 * <pre>
 * cost_per_call = (input_tokens / 1000 x input_rate) + (output_tokens / 1000 x output_rate)
 * monthly_cost  = calls_per_month x avg_cost_per_call
 *              + (embedding_tokens / 1000 x embedding_rate)   [RAG only, indexing + query embeds]
 * </pre>
 * Pricing reference: {@code cloud.google.com/vertex-ai/generative-ai/
 * pricing} (per-model token rates; the Console's Agent Studio also shows a
 * running token count per request in its "Code"/response panel, useful for
 * estimating real usage before scaling a prototype).
 *
 * <h2>First real prompt, verified via Console UI (2026-08-31)</h2>
 * Studio -&gt; typed "Explain what GCP Vertex AI is, in exactly two
 * sentences." against gemini-3.7-flash -&gt; got a real, billed response
 * back. Confirms Studio and billing genuinely work end to end, independent
 * of any code we write.
 *
 * <h2>What this platform actually covers - two distinct capabilities</h2>
 * <ul>
 *   <li><b>Traditional ML lifecycle</b>: train (AutoML or custom code with
 *       PyTorch/TensorFlow/etc), manage datasets, track experiments, and
 *       deploy models to scalable endpoints - the original "Vertex AI"
 *       scope, full MLOps tooling.</li>
 *   <li><b>LLM / generative AI + agents</b>: Model Garden (foundation
 *       models incl. Gemini) + Agent Builder/Agent Engine for building
 *       conversational chatbots and autonomous agents grounded in your own
 *       data (RAG against Drive/BigQuery/databases/websites), using
 *       Google's Agent Development Kit (ADK) and the Agent2Agent (A2A)
 *       protocol for multi-agent workflows.</li>
 * </ul>
 * These are related but separate - the "traditional ML" half barely
 * overlaps with the "build a chatbot" half beyond sharing infrastructure
 * and IAM.
 *
 * <h2>MCP (Model Context Protocol) and this platform</h2>
 * Two distinct roles, easy to conflate:
 * <ul>
 *   <li><b>Agent AS an MCP client</b> - a Vertex AI/ADK agent can connect
 *       OUT to an existing MCP server (yours or third-party) to discover
 *       and call its tools dynamically. The platform supports this
 *       natively.</li>
 *   <li><b>Hosting your OWN MCP server</b> - NOT something Agent
 *       Builder/Studio does directly. An MCP server is just a lightweight
 *       JSON-RPC microservice (Node.js/Python/etc, typically over SSE or
 *       HTTP) - you'd write it yourself and deploy it on Cloud Run or
 *       {@link com.ashfaq.gcplab._07_gke GKE} (a genuine future use for
 *       that module), then any MCP-compatible client (a Vertex AI agent,
 *       Claude Desktop, Cursor, etc) can talk to it over the standard
 *       transport.</li>
 * </ul>
 *
 * <h2>The raw REST API shape (seen via Studio's "Code" tab)</h2>
 * {@code POST https://aiplatform.googleapis.com/v1/publishers/google/
 * models/gemini-3.7-flash:streamGenerateContent} - body has
 * {@code contents} (the conversation), {@code generationConfig}
 * (including {@code thinkingConfig.thinkingLevel} - Gemini 3's explicit
 * reasoning-effort knob), {@code safetySettings} (per-category
 * thresholds), and optional {@code tools} (e.g. {@code googleSearch},
 * {@code googleMaps} - built-in grounding tools the model can invoke
 * itself). IMPORTANT: the Console's own generated snippet authenticates
 * with a raw {@code ?key=API_KEY} query param - that's the quick-start
 * path, NOT what we use in this repo's Java code (see
 * docs/auth-approach.md) - our SDK calls authenticate via ADC/impersonated
 * backend-dev-sa instead, no API key generated or stored anywhere.
 *
 * <h2>Java SDK: three levels, each built on top of the last (2026-08-31)</h2>
 * Uses {@code com.google.genai:google-genai} - the modern unified Java SDK
 * (mirrors the Python {@code from google import genai} shown in Console),
 * targeting the Vertex AI backend ({@code Client.builder().vertexAI(true)})
 * rather than the separate Gemini Developer API/AI Studio backend the same
 * SDK also supports.
 * <ul>
 *   <li>{@link GeminiPromptDemo} - Level 1, a raw generateContent call,
 *       nothing else. Runs impersonating data-ml-sa (NEW service account,
 *       bound to dataMlEngineer - first real use of that role since
 *       _01_iam; backend-dev-sa was the wrong persona for an AI/ML task).
 *       Verified: real 2-sentence explanation back from gemini-2.5-flash.</li>
 *   <li>{@link SimpleRagDemo} - Level 2a, RAG built from scratch (embed a
 *       handful of documents with text-embedding-005, embed the question,
 *       cosine-similarity retrieval by hand, stuff the winner into the
 *       prompt). Deliberately used made-up repo-specific facts Gemini
 *       could not know from training - verified: correctly retrieved "GKE
 *       cluster ran in Autopilot mode" out of 4 candidate facts and
 *       answered using only that context.</li>
 *   <li>{@link SimpleAgentDemo} - Level 2b, a hand-written tool-calling
 *       loop (no ADK) with one tool, multiply(a, b) - chosen because
 *       correct output on large numbers proves the tool actually ran
 *       rather than the model guessing. Verified: model requested
 *       multiply(48213, 7791), Java computed 375627483.0, model's final
 *       answer matched exactly.</li>
 * </ul>
 *
 * <h2>Identity setup for this module</h2>
 * {@code data-ml-sa} created and bound to {@code dataMlEngineer} the same
 * way backend-dev-sa was bound to backendDeveloper in _02_identities_bindings
 * (create SA -&gt; bind role -&gt; grant caller
 * roles/iam.serviceAccountTokenCreator on it for impersonation) - done via
 * gcloud this time rather than re-demonstrating the Java code path again,
 * since that pattern was already fully proven earlier in the repo.
 *
 * <h2>No teardown needed</h2>
 * Unlike every infrastructure module before this (_04_cloudsql, _05_redis,
 * _07_gke), Vertex AI has no standing resource to delete - it's pure
 * pay-per-call, nothing left running between calls. data-ml-sa is a
 * reusable identity, kept (same reasoning as backend-dev-sa).
 *
 * <h2>Stray finding: a pre-existing GOOGLE_API_KEY/GEMINI_API_KEY env var</h2>
 * The SDK logged a warning that both vars were already set at the User
 * environment level on this machine - NOT created by this repo or this
 * session. Traced to a Gemini API key the user had generated earlier via
 * AI Studio's "Get API Key" flow, unrelated to this project. Confirmed our
 * explicit ImpersonatedCredentials correctly took precedence over it every
 * time (the SDK's own warning message says so) - it never got used by any
 * code here. Left in place at the user's choice; lower-risk than a GCP
 * service account key since it's scoped to generative API access only,
 * not broad project access - still worth knowing it's there.
 *
 * <h2>Internal architecture: what happens between a prompt and a response</h2>
 * <pre>
 * GeminiPromptDemo -&gt; ImpersonatedCredentials token (as data-ml-sa) -&gt;
 *   aiplatform.googleapis.com generateContent/streamGenerateContent
 *   -&gt; request routed to a Google-managed prediction cluster serving the
 *      requested model (gemini-*-flash here) - you never provision or see
 *      this infrastructure; it's shared, multi-tenant, autoscaled capacity
 *      Google operates centrally, the same serving stack behind AI Studio,
 *      the Gemini app, and every other Gemini-API surface
 *   -&gt; tokenization -&gt; forward pass through the model -&gt; tokens streamed
 *      back incrementally (streamGenerateContent) so the client can render
 *      partial output before generation finishes, rather than waiting for
 *      the whole response
 * </pre>
 * {@code SimpleRagDemo}'s hand-rolled retrieval is a miniature version of
 * what a production RAG system does at scale: embed(document) once at index
 * time -&gt; store the vector -&gt; embed(query) at request time -&gt; nearest-
 * neighbor search across stored vectors (a linear cosine-similarity scan
 * here, since there were only 4 candidates; a real system swaps this one
 * step for an approximate-nearest-neighbor index like Vertex AI Vector
 * Search, see docs/roadmap.md Track C) -&gt; the top result(s) get concatenated
 * into the prompt as context -&gt; THEN the augmented prompt goes through the
 * exact same generateContent path as the plain prompt above. The model
 * itself has no separate "RAG mode" - RAG is entirely a client-side pattern
 * of what you put in the prompt before calling the same API.
 * <p>
 * {@code SimpleAgentDemo}'s tool-calling loop reveals the actual protocol
 * under "function calling": the model doesn't execute code - it returns a
 * structured {@code functionCall} object (name + arguments) INSTEAD of text
 * when it decides a declared tool would help, the calling code (our Java,
 * not Google's infrastructure) executes the real function locally, and the
 * result is fed back into a NEW generateContent call as a
 * {@code functionResponse} turn - the model then produces its final answer
 * having "seen" the tool's real output. An "agent" is this loop (call model
 * -&gt; maybe get a tool request -&gt; execute it -&gt; call model again with the
 * result -&gt; repeat until a plain text answer comes back) run to convergence
 * or a max-iteration cap; ADK (Google's Agent Development Kit) automates
 * exactly this loop instead of hand-writing it as SimpleAgentDemo does here.
 *
 * <h2>System design takeaway</h2>
 * Every capability in this module - raw prompting, RAG, agentic tool use -
 * is built from the SAME single primitive (one stateless generateContent
 * call) composed differently at the CLIENT layer, not different backend
 * products. That means the real system-design decisions live entirely on
 * your side of the API: how you chunk and store embeddings (RAG), how many
 * tool round-trips you allow before giving up (agent loop max-iterations,
 * critical for cost control - each round-trip is a full billed call), and
 * how much conversation history you resend on every call (Gemini has no
 * server-side session memory between calls unless you explicitly use a
 * stateful feature like Live API sessions - by default, YOU resend the
 * whole conversation, and that resent history is billed as input tokens
 * every single time).
 *
 * <h2>What this service actually is, in plain terms</h2>
 * Agent Platform (formerly Vertex AI) is Google's single umbrella product
 * for everything "AI" on GCP: a catalog of pre-trained foundation models you
 * can call over an API with zero infrastructure of your own (Model Garden -
 * Gemini, plus third-party/open models like Llama and Claude, all served
 * behind the same interface), tooling to fine-tune or fully custom-train
 * your own models on your own data, a place to deploy any trained model
 * behind a scalable prediction endpoint, and - the newest layer, the one
 * this module actually exercises - a framework for building AGENTS: programs
 * that don't just answer one prompt but can decide to call tools, retrieve
 * information, and take multi-step actions toward a goal. Concretely, what
 * you get by calling this API is a fully-hosted large language model you
 * send text (or images/audio/video - Gemini is natively multimodal, not
 * used in this module but available on the same endpoint) and get text (or
 * structured JSON, or a tool-call request) back, with Google running and
 * scaling the actual model weights, GPUs/TPUs, and serving infrastructure -
 * you never touch any of that, the same way _03_storage never requires you
 * to think about disks.
 *
 * <h2>Why this exists - the problem it solves</h2>
 * Before managed LLM APIs, using a model this capable meant either running
 * a huge model yourself (needing serious GPU/TPU infrastructure, model
 * ops expertise, and constant retraining/updating as better models ship) or
 * not having access to one at all. Agent Platform's value proposition is
 * "rent the intelligence, keep the data/logic": Google owns and continually
 * upgrades the model (a code change on Google's side, e.g. gemini-2.5 to
 * gemini-3.7, can improve every caller's results with zero work on your
 * part), while you own what you actually build with it - the prompts, the
 * retrieval data (RAG), the tools an agent can call, and the business logic
 * around when/how it's invoked. It also solves a narrower but very real
 * problem this repo hit directly: a model's training data has a cutoff and
 * no knowledge of YOUR private/internal data - {@code SimpleRagDemo} exists
 * specifically to prove the fix (grounding a model in facts it was never
 * trained on) rather than accept hallucination as inevitable.
 *
 * <h2>Real-world use cases - what this is actually built for</h2>
 * <ul>
 *   <li><b>Customer support copilots</b> - a support agent grounded via RAG
 *       on a company's actual help docs/ticket history, so answers are
 *       specific and current instead of generic model knowledge. This
 *       module's {@code SimpleRagDemo} is a 4-document toy version of
 *       exactly this pattern.</li>
 *   <li><b>Internal "chat with your data" tools</b> - RAG over internal
 *       wikis, contracts, codebases, or databases so employees can ask
 *       questions in plain English instead of hunting through search UIs -
 *       the same retrieval pattern, just pointed at a bigger, real corpus
 *       and usually backed by a proper vector index (see docs/roadmap.md
 *       Track C) instead of a hand-rolled cosine-similarity loop.</li>
 *   <li><b>Autonomous or semi-autonomous agents</b> - a system that can look
 *       up real data, call real APIs, and take real actions (book a
 *       meeting, file a ticket, query a live database, run a calculation)
 *       rather than just describe what it would do. {@code SimpleAgentDemo}'s
 *       {@code multiply(a, b)} tool is a minimal stand-in for this - the
 *       exact same mechanism scales to tools like "look up this order in
 *       Cloud SQL," "search the product catalog" (see the Rufus-clone
 *       capstone in docs/roadmap.md Track A, which wires this module's
 *       agent loop to {@code _09_ai_commerce_search} as a real tool), or
 *       "create a Jira ticket."</li>
 *   <li><b>Content generation and summarization at scale</b> - product
 *       descriptions, marketing copy, meeting/document summaries, code
 *       review comments - the plain generateContent call this whole module
 *       is built on, just with a task-specific prompt and no RAG/tools
 *       needed.</li>
 *   <li><b>Structured data extraction</b> - pulling specific fields (dates,
 *       amounts, names, categories) out of unstructured text/PDFs/images
 *       into JSON matching a schema, using Gemini's structured-output mode
 *       (a {@code responseSchema} on the same generateContent call) - not
 *       demonstrated in this module's demos but the identical API surface,
 *       just with a schema constraint added to the request.</li>
 *   <li><b>Classical ML (the "other half" of this platform, not touched by
 *       this module's demos)</b> - fraud/anomaly detection, demand
 *       forecasting, recommendation ranking - trained via AutoML or custom
 *       training jobs and served via prediction endpoints; see
 *       docs/roadmap.md Track B for this repo's planned entry point into
 *       that side of the platform.</li>
 * </ul>
 *
 * <h2>Sample usage walkthrough - each demo class, what it proves</h2>
 * <b>{@link GeminiPromptDemo} - the floor every other capability builds on:</b>
 * <pre>
 * Client client = Client.builder().vertexAI(true).project(PROJECT_ID)
 *     .location("us-central1").credentials(impersonatedCredentials).build();
 * GenerateContentResponse resp = client.models.generateContent(
 *     "gemini-2.5-flash",
 *     "Explain what GCP Vertex AI is, in exactly two sentences.",
 *     null);
 * System.out.println(resp.text());
 * </pre>
 * One call, one response, no state kept between calls - this is the entire
 * primitive. Every other demo in this module is this same call, either with
 * a richer prompt (RAG) or with {@code tools} declared in the request
 * (agent).
 * <p>
 * <b>{@link SimpleRagDemo} - grounding the model in facts it can't know:</b>
 * <pre>
 * // 1. Index time (once): embed each fact, keep the vector alongside the text
 * List&lt;String&gt; facts = List.of(
 *     "The GKE cluster in this repo ran in Autopilot mode.",
 *     "The Cloud SQL instance used PostgreSQL 18.",
 *     "The Redis instance was Basic tier, 1GB.",
 *     "The Firestore database used MongoDB compatibility mode.");
 * List&lt;float[]&gt; factEmbeddings = facts.stream()
 *     .map(f -&gt; embed(f)).toList();   // text-embedding-005 call per fact
 *
 * // 2. Query time: embed the question, find the nearest fact by cosine similarity
 * float[] queryEmbedding = embed("What mode did the GKE cluster run in?");
 * String bestFact = facts.get(nearestByCosineSimilarity(queryEmbedding, factEmbeddings));
 *
 * // 3. Stuff the retrieved fact into the prompt, THEN call generateContent
 * String prompt = "Using only this context, answer the question.\n"
 *     + "Context: " + bestFact + "\nQuestion: What mode did the GKE cluster run in?";
 * String answer = client.models.generateContent("gemini-2.5-flash", prompt, null).text();
 * // answer correctly says "Autopilot" - a fact the model was never trained on
 * </pre>
 * The point proven: the model has zero built-in knowledge of this repo, but
 * answers correctly once the right fact is retrieved and handed to it in
 * the prompt - RAG in miniature, no vector database needed at this scale.
 * <p>
 * <b>{@link SimpleAgentDemo} - letting the model request a real computation:</b>
 * <pre>
 * FunctionDeclaration multiplyFn = FunctionDeclaration.builder()
 *     .name("multiply")
 *     .description("Multiplies two numbers and returns the exact product.")
 *     .parameters(Schema.builder()
 *         .type("OBJECT")
 *         .properties(Map.of(
 *             "a", Schema.builder().type("NUMBER").build(),
 *             "b", Schema.builder().type("NUMBER").build()))
 *         .build())
 *     .build();
 *
 * GenerateContentResponse r1 = client.models.generateContent("gemini-2.5-flash",
 *     "What is 48213 times 7791?",
 *     GenerateContentConfig.builder().tools(List.of(Tool.builder()
 *         .functionDeclarations(List.of(multiplyFn)).build())).build());
 *
 * // r1 contains a functionCall: multiply(a=48213, b=7791) instead of text
 * FunctionCall call = r1.functionCalls().get(0);
 * double result = ((Number) call.args().get("a")).doubleValue()
 *                * ((Number) call.args().get("b")).doubleValue();   // our Java runs the real math: 375627483.0
 *
 * // Feed the real result back as a new turn, ask the model to finish its answer
 * GenerateContentResponse r2 = client.models.generateContent("gemini-2.5-flash",
 *     Content.builder().role("function").parts(List.of(Part.fromFunctionResponse(
 *         "multiply", Map.of("result", result)))).build(),
 *     sameToolsConfig);
 * System.out.println(r2.text());   // "48213 times 7791 is 375,627,483."
 * </pre>
 * Why this matters as a pattern: an LLM is fundamentally bad at exact large-
 * number arithmetic (it predicts plausible-looking tokens, not computes),
 * so this demo deliberately uses a case where a wrong answer is obvious and
 * checkable - proving the tool call genuinely ran in Java rather than the
 * model guessing a believable-looking number. The same loop, with a real
 * tool instead of {@code multiply}, is the entire mechanism behind every
 * "agent" use case listed above.
 */
package com.ashfaq.gcplab._08_vertexai;
