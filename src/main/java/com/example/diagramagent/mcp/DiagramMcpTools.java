package com.example.diagramagent.mcp;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.api.DiagramResponse;
import com.example.diagramagent.api.DiffResponse;
import com.example.diagramagent.cache.ServiceModelCache;
import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.diff.GitRefValidator;
import com.example.diagramagent.diff.GitService;
import com.example.diagramagent.diff.ModelDiffer;
import com.example.diagramagent.diff.StructuralDiff;
import com.example.diagramagent.render.DiagramFormat;
import com.example.diagramagent.render.DiagramRenderer;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.security.PathGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Model Context Protocol (MCP) tools for Diagram Agent.
 * Exposes core services to AI tools/clients (Claude Desktop, Claude Code, Cursor, etc.).
 */
@Component
@Profile("mcp | mcp-web")
public class DiagramMcpTools {

    private final PathGuard pathGuard;
    private final ServiceModelCache serviceModelCache;
    private final DiagramAgent diagramAgent;
    private final DiagramRenderer diagramRenderer;
    private final GitService gitService;
    private final ModelDiffer modelDiffer;
    private final DiagramProperties properties;

    public record EndpointDto(String method, String path, String handler) {}

    public record RenderDiagramResponse(String format, String contentType, String content, String base64) {}

    public DiagramMcpTools(
            PathGuard pathGuard,
            ServiceModelCache serviceModelCache,
            DiagramAgent diagramAgent,
            DiagramRenderer diagramRenderer,
            GitService gitService,
            ModelDiffer modelDiffer,
            DiagramProperties properties) {
        this.pathGuard = pathGuard;
        this.serviceModelCache = serviceModelCache;
        this.diagramAgent = diagramAgent;
        this.diagramRenderer = diagramRenderer;
        this.gitService = gitService;
        this.modelDiffer = modelDiffer;
        this.properties = properties;
    }

    @Tool(
            name = "list_endpoints",
            description = "List detected Spring HTTP endpoints (HTTP method, route path, and handler) in a Java project")
    public List<EndpointDto> listEndpoints(
            @ToolParam(description = "Relative or absolute path to the Java project root (within allowed-root)") String path) {
        Path resolved = pathGuard.validateAndResolve(path);
        ServiceModel model = serviceModelCache.getOrScan(resolved);
        return model.endpoints().stream()
                .map(ep -> new EndpointDto(ep.httpMethod(), ep.path(), ep.controllerClass() + "#" + ep.methodName()))
                .toList();
    }

    @Tool(
            name = "generate_diagram",
            description = "Generate a Mermaid diagram (SEQUENCE, FLOWCHART, or STATE) for a Java project using static analysis and LLM")
    public DiagramResponse generateDiagram(
            @ToolParam(description = "Relative or absolute path to the Java project root (within allowed-root)") String path,
            @ToolParam(description = "Diagram type: SEQUENCE, FLOWCHART, or STATE") String type,
            @ToolParam(description = "Entry point in format 'ClassName#methodName' (optional for FLOWCHART/STATE)", required = false) String entryPoint,
            @ToolParam(description = "Maximum call-depth to trace (default 4)", required = false) Integer maxDepth) {
        Path resolved = pathGuard.validateAndResolve(path);
        DiagramType diagramType = DiagramType.valueOf(type.toUpperCase());
        int depth = maxDepth != null && maxDepth > 0 ? maxDepth : properties.maxDepth();

        DiagramAgent.DiagramResult cached = serviceModelCache.getCachedResult(resolved, diagramType, entryPoint, depth);
        if (cached != null) {
            return new DiagramResponse(
                    cached.type(),
                    cached.mermaid(),
                    cached.valid(),
                    cached.attempts(),
                    cached.warnings(),
                    true
            );
        }

        ServiceModel model = serviceModelCache.getOrScan(resolved);
        DiagramAgent.DiagramResult result = diagramAgent.generateDiagram(resolved, model, diagramType, entryPoint, depth);
        serviceModelCache.putResult(resolved, diagramType, entryPoint, depth, result);

        return new DiagramResponse(
                result.type(),
                result.mermaid(),
                result.valid(),
                result.attempts(),
                result.warnings(),
                result.cached()
        );
    }

    @Tool(
            name = "diff_diagram",
            description = "Generate a Git diff Mermaid diagram showing structural additions, removals, and changes between two git revisions")
    public DiffResponse diffDiagram(
            @ToolParam(description = "Relative or absolute path to the Git repository root (within allowed-root)") String path,
            @ToolParam(description = "Starting git commit/ref (default HEAD~1)", required = false) String fromRef,
            @ToolParam(description = "Target git commit/ref (default HEAD)", required = false) String toRef,
            @ToolParam(description = "Diagram type: SEQUENCE, FLOWCHART, or STATE") String type,
            @ToolParam(description = "Entry point in format 'ClassName#methodName' (optional for FLOWCHART/STATE)", required = false) String entryPoint,
            @ToolParam(description = "Maximum call-depth to trace (default 4)", required = false) Integer maxDepth) {
        Path resolved = pathGuard.validateAndResolve(path);
        String safeFrom = GitRefValidator.validate(fromRef != null && !fromRef.isBlank() ? fromRef : "HEAD~1");
        String safeTo = GitRefValidator.validate(toRef != null && !toRef.isBlank() ? toRef : "HEAD");
        DiagramType diagramType = DiagramType.valueOf(type.toUpperCase());
        int depth = maxDepth != null && maxDepth > 0 ? maxDepth : properties.maxDepth();

        ServiceModel fromModel = gitService.extractModelAtRef(resolved, safeFrom);
        ServiceModel toModel = gitService.extractModelAtRef(resolved, safeTo);
        StructuralDiff diff = modelDiffer.diff(fromModel, toModel, diagramType, entryPoint);

        DiagramAgent.DiagramResult result = diagramAgent.generateDiffDiagram(
                resolved, fromModel, toModel, diff, diagramType, entryPoint, depth
        );

        return new DiffResponse(
                result.type(),
                safeFrom,
                safeTo,
                result.mermaid(),
                result.valid(),
                result.attempts(),
                result.warnings(),
                diff.changes(),
                diff.summary()
        );
    }

    @Tool(
            name = "render_diagram",
            description = "Render a Mermaid diagram into SVG or PNG format (requires mmdc / mermaid-cli)")
    public RenderDiagramResponse renderDiagram(
            @ToolParam(description = "The Mermaid diagram syntax code") String mermaid,
            @ToolParam(description = "Output format: SVG or PNG") String format) {
        if (!diagramRenderer.isMmdcAvailable()) {
            throw new IllegalStateException("mmdc (Mermaid CLI) is not available. Please install @mermaid-js/mermaid-cli.");
        }
        DiagramFormat fmt = DiagramFormat.valueOf(format.toUpperCase());
        byte[] bytes = diagramRenderer.render(mermaid, fmt);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String textContent = fmt == DiagramFormat.SVG ? new String(bytes, StandardCharsets.UTF_8) : "data:image/png;base64," + base64;
        return new RenderDiagramResponse(fmt.name(), fmt.contentType(), textContent, base64);
    }
}
