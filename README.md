# Diagram Agent

**Diagram Agent** is an extensible developer tool and Spring Boot service powered by **Spring AI** and **Google Gemini** that statically analyzes Java (Spring Boot) source code and generates verified architecture diagrams in **Mermaid**, **SVG**, or **PNG**:
- **Sequence diagram** (`sequenceDiagram`)
- **Flowchart** (`flowchart TD`)
- **State diagram** (`stateDiagram-v2`)
- **Git Diff diagram** (comparing two commits/branches without checking out code)

The application does **NOT** dump raw source code into the LLM. Instead, it operates in two deterministic stages:
1. **Deterministic Extraction (JavaParser & JGit)**: Scans `.java` files, extracting classes, stereotypes (`@RestController`, `@Service`, `@Repository`), dependency injection graphs, Spring HTTP endpoints, call chains (with external call categorization), control flows (`if/else`, `switch`, `try/catch`, loops), and state/enum transitions.
2. **LLM Generation & Validation (Spring AI + Gemini + Mermaid CLI)**: Synthesizes diagrams using only the extracted structure via Spring AI's provider-neutral `ChatClient` abstraction, with post-processing, validation (`mmdc` or structural fallback), and automated self-correction retries.

Multiple interfaces are supported against the exact same core engine:
- **REST API** (`/api/diagrams/*`)
- **Offline Web UI** (`/` with bundled local Mermaid.js)
- **Command Line Interface (CLI)** (`scripts/diagram-agent` & `.cmd`)
- **Model Context Protocol (MCP) Server** (for Claude Desktop, Claude Code, and Cursor)
- **Docker & Docker Compose** (bundled with Chromium & `mermaid-cli`)

---

## 1. Features & Architecture Overview

- **Caffeine Multi-Tier Cache**: Fast project AST scanning cache with cheap tree fingerprint invalidation (`list of .java files + lastModified + size` hashed). Re-scanning a 170+ endpoint project is eliminated unless files actually change. Optional LLM result caching (`diagram.cache.results=true`).
- **Git Diff Visualizer**: Uses **JGit** in-memory object loaders to compare two git revisions without touching working trees or switching branches. Generates deterministic structural diffs in code and renders visual diff diagrams with green (added), dashed-red (removed), and amber (modified) highlights.
- **Image Export (SVG & PNG)**: Headless rendering using `@mermaid-js/mermaid-cli` (`mmdc`) with a sandboxed Chromium configuration (`puppeteer-config.json`).
- **Offline Single-Page Web UI**: Lightweight static dashboard served at `/` with bundled local `mermaid.min.js` (no CDN dependencies, no data leakage). Features endpoint auto-discovery, diagram preview, and direct SVG/PNG downloads.
- **Headless CLI (Picocli)**: Standalone command-line executable with subcommands `generate`, `endpoints`, `diff`, and `validate`. Strict exit codes (0: success, 2: bad args, 3: insufficient info, 4: provider error, 5: validation failure), and stderr-routed logging to ensure clean stdout piping.
- **Model Context Protocol (MCP)**: Native tools provider exposing `list_endpoints`, `generate_diagram`, `diff_diagram`, and `render_diagram` over stdio and HTTP/SSE transports.
- **Strict Security Guardrails**: `PathGuard` confinement against directory traversal and symlink escapes, read-only code analysis, and strict Git ref validation.

---

## 2. Configuration Reference

All settings can be configured via `application.yml` or environment variables:

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `diagram.allowed-root` | `DIAGRAM_ROOT` | `.` | Mandatory base directory boundary for all operations |
| `diagram.max-context-chars` | - | `60000` | Maximum character budget sent to LLM prompts |
| `diagram.max-retries` | - | `2` | Automated syntax self-correction retries on validation failure |
| `diagram.max-depth` | - | `4` | Maximum call chain traversal depth |
| `diagram.agentic` | - | `false` | Enable multi-step agentic tool calling with Gemini |
| `diagram.max-tool-calls` | - | `15` | Maximum agentic tool iterations |
| `diagram.mermaid-cli-path` | `DIAGRAM_MERMAID_CLI_PATH` | `mmdc` | Executable path for Mermaid CLI |
| `diagram.puppeteer-config-file`| `DIAGRAM_PUPPETEER_CONFIG_FILE`| `puppeteer-config.json` | Puppeteer configuration for mmdc (`--no-sandbox`) |
| `diagram.max-files-scanned` | `DIAGRAM_MAX_FILES_SCANNED` | `5000` | Upper cap of Java files scanned per project |
| `diagram.max-file-size-bytes` | - | `1048576` (1MB) | Maximum individual file size scanned |
| `diagram.cache.enabled` | `DIAGRAM_CACHE_ENABLED` | `true` | Enable ServiceModel AST in-memory cache |
| `diagram.cache.max-projects` | `DIAGRAM_CACHE_MAX_PROJECTS` | `5` | Maximum projects cached concurrently |
| `diagram.cache.ttl-minutes` | `DIAGRAM_CACHE_TTL_MINUTES` | `30` | Cache time-to-live in minutes |
| `diagram.cache.results` | `DIAGRAM_CACHE_RESULTS` | `false` | Enable caching of completed diagram generation results |
| `spring.ai.google.genai.api-key`| `GOOGLE_API_KEY` | - | Google Gemini API key |
| `spring.ai.google.genai.chat.options.model`| `DIAGRAM_MODEL`| `gemini-3.5-flash` | Gemini model name |
| `spring.ai.mcp.server.stdio` | `SPRING_AI_MCP_SERVER_STDIO`| `true` | Run MCP server in stdio transport mode |

---

## 3. Quick Start: Web Application

### 1. Build and Run
```bash
# Set your Gemini API key and allowed root
export GOOGLE_API_KEY="AIzaSy..."
export DIAGRAM_ROOT="."

./gradlew bootRun
```

On Windows PowerShell:
```powershell
$env:GOOGLE_API_KEY="AIzaSy..."
$env:DIAGRAM_ROOT="."
.\gradlew.bat bootRun
```

### 2. Open the Web UI
Navigate to `http://localhost:8080/` in your browser.
- Enter a relative path to your service (e.g., `src/test/resources/fixtures/order-service`).
- Click **"Load Endpoints"** to automatically discover available controllers and routes.
- Choose a diagram type (**Sequence**, **Flowchart**, or **State**) and click **"Generate Diagram"**.
- View the rendered diagram, inspect syntax warnings, and download as **SVG** or **PNG**.

---

## 4. Quick Start: Command Line Interface (CLI)

The CLI runs standalone without starting the web server (`spring.main.web-application-type=none`). Wrapper scripts are provided in `scripts/`.

### Commands

#### List Endpoints
```bash
# Bash
./scripts/diagram-agent endpoints --path src/test/resources/fixtures/order-service

# Windows PowerShell
.\scripts\diagram-agent.cmd endpoints --path src/test/resources/fixtures/order-service
```

#### Generate Diagram
```bash
# Generate Sequence Diagram to stdout
./scripts/diagram-agent generate --path src/test/resources/fixtures/order-service --type SEQUENCE --entry "OrderController#payOrder"

# Save directly to file in SVG format
./scripts/diagram-agent generate --path src/test/resources/fixtures/order-service --type SEQUENCE --entry "OrderController#payOrder" --format svg --out order-flow.svg
```

#### Git Diff Diagram
```bash
# Compare HEAD~1 with HEAD
./scripts/diagram-agent diff --path . --from HEAD~1 --to HEAD --type FLOWCHART --entry "OrderController#payOrder"
```

#### Validate Mermaid File
```bash
# Validate existing diagram syntax
./scripts/diagram-agent validate docs/sample-output/sequence.mmd
# Or shorthand
./scripts/diagram-agent --validate-only docs/sample-output/sequence.mmd
```

### CLI Exit Codes
- `0`: Success
- `2`: Bad arguments or invalid/forbidden path/git ref
- `3`: Insufficient information in source code to generate diagram
- `4`: LLM provider failure (e.g., API error or network issue)
- `5`: Diagram syntax validation failed

---

## 5. Quick Start: Model Context Protocol (MCP) Server

Diagram Agent can be registered as an MCP tool provider for **Claude Desktop**, **Claude Code**, and **Cursor**.

### Exposed MCP Tools
1. `list_endpoints(path)`: Lists detected HTTP endpoints (method, path, handler).
2. `generate_diagram(path, type, entryPoint?, maxDepth?)`: Generates Mermaid code with validation status and warnings.
3. `diff_diagram(path, fromRef, toRef, type, entryPoint?, maxDepth?)`: Generates a Git diff Mermaid diagram with structured change details.
4. `render_diagram(mermaid, format)`: Renders Mermaid diagrams into SVG or PNG format.

### Claude Desktop Configuration
Add the server to your `claude_desktop_config.json` (`%APPDATA%\Claude\claude_desktop_config.json` on Windows or `~/Library/Application Support/Claude/claude_desktop_config.json` on macOS):

```json
{
  "mcpServers": {
    "diagram-agent": {
      "command": "java",
      "args": [
        "-Dspring.profiles.active=mcp",
        "-Dspring.main.web-application-type=none",
        "-Ddiagram.allowed-root=C:/path/to/your/projects",
        "-Dspring.ai.google.genai.api-key=YOUR_API_KEY",
        "-jar",
        "C:/path/to/chartAgent/build/libs/diagram-agent-0.0.1-SNAPSHOT.jar"
      ]
    }
  }
}
```

### Claude Code Registration
```bash
claude mcp add diagram-agent -- java -Dspring.profiles.active=mcp -Dspring.main.web-application-type=none -Ddiagram.allowed-root=/path/to/projects -Dspring.ai.google.genai.api-key=YOUR_API_KEY -jar /path/to/chartAgent/build/libs/diagram-agent-0.0.1-SNAPSHOT.jar
```

### HTTP / SSE Transport
To expose MCP tools over HTTP/SSE instead of stdio:
```bash
java -Dspring.profiles.active=mcp-web -Ddiagram.allowed-root=. -jar build/libs/diagram-agent-0.0.1-SNAPSHOT.jar
```
The SSE endpoint is available at `http://localhost:8080/mcp/message`.

---

## 6. Quick Start: Docker & Docker Compose

A multi-stage `Dockerfile` is provided with pre-installed Chromium, Node.js 20, and `@mermaid-js/mermaid-cli`, configured with a non-root user and sandboxed puppeteer settings.

### Running with Docker Compose
```bash
# 1. Create your .env file with your API key
echo "GOOGLE_API_KEY=AIzaSy..." > .env

# 2. Start the service (mounts your projects read-only to /projects)
DIAGRAM_PROJECTS_DIR="/path/to/my/projects" docker compose up --build
```

### Proxy Configuration in Docker
If behind a corporate firewall or proxy:
```bash
# In .env:
ALL_PROXY=socks5://host.docker.internal:12080
HTTPS_PROXY=http://host.docker.internal:12080
```
On Windows / Docker Desktop, `host.docker.internal` automatically maps to the host machine gateway.

---

## 7. REST API Reference

### `POST /api/diagrams`
Generates a diagram from code structure.
```json
{
  "path": "src/test/resources/fixtures/order-service",
  "type": "SEQUENCE",
  "entryPoint": "OrderController#payOrder",
  "maxDepth": 4,
  "format": "MERMAID"
}
```
`format` supports: `MERMAID` (default), `SVG`, `PNG`. When `SVG` or `PNG` is specified, the response includes `image` (base64-encoded) and `contentType`.

### `POST /api/diagrams/diff`
Generates a visual Git diff diagram between two commits.
```json
{
  "path": ".",
  "fromRef": "HEAD~1",
  "toRef": "HEAD",
  "type": "FLOWCHART",
  "entryPoint": "OrderController#payOrder",
  "format": "MERMAID"
}
```

### `POST /api/diagrams/render`
Renders raw Mermaid syntax to image bytes (`Content-Type: image/svg+xml` or `image/png`).
```json
{
  "mermaid": "flowchart TD\nStart --> End",
  "format": "SVG"
}
```

### `GET /api/diagrams/endpoints?path=...`
Detects and lists all Spring MVC / Web endpoints in the target project.

### `DELETE /api/diagrams/cache`
Invalidates and clears the in-memory ServiceModel AST and DiagramResult caches.

---

## 8. Continuous Integration (GitHub Actions)

Example workflow to automatically generate and validate architecture diagrams on PR:

```yaml
name: Generate Architecture Diagram

on:
  pull_request:
    paths:
      - 'src/main/java/**'

jobs:
  diagram:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Generate Sequence Diagram
        env:
          GOOGLE_API_KEY: ${{ secrets.GOOGLE_API_KEY }}
        run: |
          ./gradlew bootJar -q
          ./scripts/diagram-agent generate --path . --type SEQUENCE --entry "OrderController#payOrder" --out pr-flow.mmd

      - name: Validate Diagram
        run: |
          ./scripts/diagram-agent validate pr-flow.mmd
```

---

## 9. Privacy Notice

**Data Privacy Policy**:
- Diagram Agent **never** transmits entire source code files to the LLM.
- Extracted structural metadata (class names, method signatures, call flows, and enum constant names) is transmitted to the configured Google Gemini model API endpoint for diagram generation.
- No project code or credentials are ever logged, cached on disk, or committed to version control.
