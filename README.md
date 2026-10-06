# Diagram Agent

**Diagram Agent** is a Spring Boot service powered by **Spring AI** and **Google Gemini** that takes a filesystem path to a Java (Spring) service's source code and generates verified Mermaid diagrams:
- **Sequence diagram** (`sequenceDiagram`)
- **Flowchart** (`flowchart TD`)
- **State diagram** (`stateDiagram-v2`)

The application does **NOT** dump raw source code into the LLM. Instead, it operates in two stages:
1. **Deterministic Extraction (JavaParser)**: Scans `.java` files under `src/main/java`, extracting classes, stereotypes (`@RestController`, `@Service`, `@Repository`), dependency injection graphs, Spring HTTP endpoints, call chains (with external call categorization), control flows (`if/else`, `switch`, `try/catch`, loops), and state/enum transitions.
2. **LLM Generation (Spring AI + Gemini)**: Generates Mermaid diagrams using only the extracted structure via Spring AI's provider-neutral `ChatClient` abstraction, with post-processing, validation, and automated retries.

---

## 1. Tech Stack & Dependencies

- **Java**: 17+
- **Spring Boot**: 3.4.3
- **Build System**: Gradle 8.12.1 (Kotlin DSL) with version catalog (`gradle/libs.versions.toml`) and wrapper (`./gradlew`)
- **Spring AI**: 1.1.8 managed via `spring-ai-bom`
- **Model Providers**:
  - **Google GenAI Starter** (Preferred): `org.springframework.ai:spring-ai-starter-model-google-genai` (Google AI Studio Developer API, API key auth)
  - **Vertex AI Starter** (Alternative): `org.springframework.ai:spring-ai-starter-model-vertex-ai-gemini` (Google Cloud Vertex AI, project/location/ADC auth)
- **Java Parser**: `com.github.javaparser:javaparser-core` & `javaparser-symbol-solver-core` (3.28.2)
- **Architecture**: No Lombok, constructor injection, immutable Java records for DTOs and models.

---

## 2. Environment Variables & Setup

### Environment Variables

| Variable | Description | Default / Example |
|---|---|---|
| `GOOGLE_API_KEY` or `GEMINI_API_KEY` | Gemini Developer API key (for `gemini-api` profile) | `AIzaSy...` |
| `DIAGRAM_ROOT` | Allowed root path on the local filesystem (mandatory for security) | E.g. `/workspace` or `.` |
| `DIAGRAM_MODEL` | Gemini model name | `gemini-2.5-flash` (or `gemini-3.5-flash`) |
| `DIAGRAM_MAX_FILES_SCANNED` | Maximum Java source files scanned (caps large repository scans) | `5000` (default) |
| `GCP_PROJECT_ID` | GCP Project ID (for `vertex` profile) | `my-gcp-project` |
| `GCP_LOCATION` | GCP Region (for `vertex` profile) | `us-central1` |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `gemini-api` (default) or `vertex` |

### How to get a Gemini API Key
1. Go to [Google AI Studio](https://aistudio.google.com/).
2. Sign in with your Google account.
3. Click **"Get API key"** and create a new key.
4. Export the key in your terminal:
   ```bash
   export GOOGLE_API_KEY="AIzaSy..."
   # On Windows PowerShell:
   $env:GOOGLE_API_KEY="AIzaSy..."
   ```

### Choosing a Gemini Model
Set `DIAGRAM_MODEL` to any current Gemini model:
- `gemini-2.5-flash` (recommended for low latency, high token limit, and accurate code extraction)
- `gemini-2.0-flash`
- `gemini-1.5-pro`

---

## 3. Provider Switching (`gemini-api` vs `vertex`)

The application code depends strictly on Spring AI's provider-neutral `ChatClient` and `ChatModel` interfaces. Provider selection is completely decoupled and switchable via configuration.

### Switching at Runtime (Spring Profiles)
By default, the active profile is `gemini-api`. To switch to Vertex AI:

```bash
# Using Google AI Studio Gemini API (default):
java -jar build/libs/diagram-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=gemini-api

# Using Google Cloud Vertex AI:
java -jar build/libs/diagram-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=vertex
```

### Switching at Build Time (Gradle Property)
The Gradle build accepts an `-PaiProvider` property:
```bash
# Build with Google GenAI starter (default):
./gradlew build -PaiProvider=gemini-api

# Build with Vertex AI starter:
./gradlew build -PaiProvider=vertex

# Build with both starters included:
./gradlew build -PaiProvider=both
```

---

## 4. Building and Running

### Build and Test
```bash
# Run full clean build and tests
./gradlew clean build

# Run unit and integration tests only
./gradlew test
```

### Run Application
```bash
export GOOGLE_API_KEY="AIzaSy..."
export DIAGRAM_ROOT="."
./gradlew bootRun
```

---

## 5. REST API & Curl Examples

### 1. List Endpoints in a Project
Inspect detected HTTP endpoints before requesting a diagram:
```bash
curl -X GET "http://localhost:8080/api/diagrams/endpoints?path=src/test/resources/fixtures/order-service"
```
**Response:**
```json
[
  {
    "httpMethod": "POST",
    "path": "/orders",
    "handler": "OrderController#createOrder"
  },
  {
    "httpMethod": "POST",
    "path": "/orders/{id}/pay",
    "handler": "OrderController#payOrder"
  },
  {
    "httpMethod": "POST",
    "path": "/orders/{id}/ship",
    "handler": "OrderController#shipOrder"
  },
  {
    "httpMethod": "GET",
    "path": "/orders/{id}",
    "handler": "OrderController#getOrder"
  }
]
```

---

### 2. Generate Sequence Diagram (`SEQUENCE`)
```bash
curl -X POST "http://localhost:8080/api/diagrams" \
  -H "Content-Type: application/json" \
  -d '{
    "path": "src/test/resources/fixtures/order-service",
    "type": "SEQUENCE",
    "entryPoint": "POST /orders/{id}/pay",
    "maxDepth": 4
  }'
```
**Response:**
```json
{
  "type": "SEQUENCE",
  "mermaid": "sequenceDiagram\n    Client->>OrderController: POST /orders/{id}/pay\n    OrderController->>OrderService: payOrder(id)\n    ...",
  "valid": true,
  "attempts": 1,
  "warnings": []
}
```

---

### 3. Generate Flowchart (`FLOWCHART`)
```bash
curl -X POST "http://localhost:8080/api/diagrams" \
  -H "Content-Type: application/json" \
  -d '{
    "path": "src/test/resources/fixtures/order-service",
    "type": "FLOWCHART",
    "entryPoint": "OrderController#payOrder",
    "maxDepth": 4
  }'
```
**Response:**
```json
{
  "type": "FLOWCHART",
  "mermaid": "flowchart TD\n    start([Start: POST /orders/{id}/pay]) --> findOrder[OrderRepository.findById]\n    ...",
  "valid": true,
  "attempts": 1,
  "warnings": []
}
```

---

### 4. Generate State Diagram (`STATE`)
`STATE` scans the project for enums, entity status fields, assignments, conditions, and state machine configurations:
```bash
curl -X POST "http://localhost:8080/api/diagrams" \
  -H "Content-Type: application/json" \
  -d '{
    "path": "src/test/resources/fixtures/order-service",
    "type": "STATE"
  }'
```
**Response:**
```json
{
  "type": "STATE",
  "mermaid": "stateDiagram-v2\n    [*] --> NEW: OrderService#createOrder\n    NEW --> PAID: OrderService#payOrder\n    ...",
  "valid": true,
  "attempts": 1,
  "warnings": []
}
```

---

## 6. Mermaid Validation & Automated Retries

### Validation Strategy
1. **Primary Validator (`mmdc` / mermaid-cli)**:
   - If installed on `PATH` or configured via `diagram.mermaid-cli-path`, `MermaidValidator` executes `mmdc` in a sandbox temp file. Any rendering syntax errors are captured from `stderr`.
2. **Fallback Structural Validator**:
   - If `mmdc` is not found, the validator executes a structural check:
     - Header keywords match diagram type (`sequenceDiagram`, `flowchart TD`, `stateDiagram-v2`).
     - Balanced block markers (`alt/else/opt/loop/par` and `end`, or braces `{}`).
     - Rejection of leftover markdown code fences (` ``` `).
     - Non-empty diagram body.
     - Adds a warning: `"Full mermaid-cli (mmdc) validation was skipped; structural check applied."`

### Enabling `mmdc` Validation
To enable full CLI validation, install Mermaid CLI either locally as a development dependency or globally:

```bash
# Option A: Install locally in project
npm install --save-dev @mermaid-js/mermaid-cli

# Option B: Install globally
npm install -g @mermaid-js/mermaid-cli
```

If installed locally, Diagram Agent automatically detects `node_modules/.bin/mmdc.cmd` (or `node_modules/.bin/mmdc` on Unix). You can also explicitly configure the path using `diagram.mermaid-cli-path` in `application.yml`:
```yaml
diagram:
  mermaid-cli-path: node_modules/.bin/mmdc.cmd
```

### Automated Correction Loop (`DiagramRetryService`)
If the model produces invalid Mermaid code:
1. `DiagramRetryService` captures the exact syntax error message from the validator.
2. It prompts the Gemini model with the previous output and the error, asking for the corrected diagram.
3. It retries up to `diagram.max-retries` (default 2).
4. If still invalid after max attempts, it returns the final attempt with `valid: false` and the error listed under `warnings`.

---

## 7. Agentic Mode (`diagram.agentic=true`)

When enabled via configuration (`diagram.agentic: true`), Spring AI provides read-only tool functions (`@Tool`) to Gemini:
- `listClasses()`: Scans and lists class names and stereotypes.
- `getClassSummary(className)`: Retrieves class dependencies, methods, and packages.
- `readMethodSource(className, methodName)`: Reads source of a method, truncated to safe size.
- `findCallers(className, methodName)`: Searches all methods that call the specified method.

All tool executions are restricted strictly within the verified `PathGuard` workspace.

---

## 8. Subfolder & Module Scans

The `path` parameter in API requests (`POST /api/diagrams` and `GET /api/diagrams/endpoints`) supports:
- Repository root: `.` or an absolute path within `diagram.allowed-root`.
- Subfolders / Gradle submodules: e.g. `service-core` or `src/main/java/com/example/orderservice`.
When pointing to a subfolder without a dedicated `src/main/java` hierarchy, the scanner traverses `.java` files directly under that subfolder.

---

## 9. Security & Guardrails

- **Path Confinement (`PathGuard`)**: `diagram.allowed-root` is mandatory. All requested paths are resolved against this root, normalized, and evaluated with `toRealPath()` to block directory traversal (`../`), absolute paths outside root, and symlink escapes.
- **No Code Execution**: Scanned Java code is parsed strictly as abstract syntax trees (ASTs) using JavaParser. Project binaries or classes are never loaded or executed.
- **Resource Caps**: Scans are bounded by `diagram.max-files-scanned` (default `5000`, configurable via `DIAGRAM_MAX_FILES_SCANNED`) and `diagram.max-file-size-bytes` (default 1MB). When file limits are exceeded, classes are prioritized by architectural significance (Controllers > Services > Repositories/Clients > Configurations > Others).
- **Fast Failure (422)**: If an entry point class is missing from the scanned model (e.g. invalid name or excluded by scan limits), the agent immediately fails with `422 Unprocessable Entity` rather than making an uninformative LLM call.
- **Privacy Notice**: Extracted structural metadata (class names, method signatures, call flows, enum values) is transmitted to Google's Gemini API for diagram generation. Users must ensure compliance with their organization's data privacy policies.

---

## 9. Error Handling (RFC 7807 Problem Details)

All exceptions return standard RFC 7807 `application/problem+json`:
- `400 Bad Request`: Invalid or escaping path, or malformed request parameters.
- `404 Not Found`: No `.java` files found under `src/main/java` in the specified directory.
- `422 Unprocessable Entity`: The model or project lacks required information (e.g. no state machine or enums for a `STATE` diagram).
- `502 Bad Gateway`: LLM provider failures (e.g. safety filter blocking).
- `429 Too Many Requests`: Upstream Gemini rate limits after backoff retries are exhausted.
- `504 Gateway Timeout`: Processing timeouts.

---

## 10. Sample Output

Reference diagrams generated from the included test fixture project (`order-service`) are located in:
- [sequence.mmd](file:///docs/sample-output/sequence.mmd)
- [flowchart.mmd](file:///docs/sample-output/flowchart.mmd)
- [state.mmd](file:///docs/sample-output/state.mmd)

---

## 11. Known Limitations

- **Language Support**: Strictly Java 17+ Spring Boot services adhering to standard `src/main/java` structure.
- **Dynamic Reflection**: Dynamically dispatched calls (e.g., reflection, Spring SpEL expressions) cannot be resolved via static AST parsing.
- **Gemini Thinking Models**: When using experimental thinking models (e.g., `gemini-2.0-flash-thinking`), internal thinking tokens count toward the overall output limit. Ensure `max-output-tokens` is sized appropriately (4000+).
