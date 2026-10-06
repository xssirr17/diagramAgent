package com.example.diagramagent.api;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.cache.ServiceModelCache;
import com.example.diagramagent.diff.GitService;
import com.example.diagramagent.diff.ModelDiffer;
import com.example.diagramagent.diff.StructuralDiff;
import com.example.diagramagent.render.DiagramFormat;
import com.example.diagramagent.render.DiagramRenderer;
import com.example.diagramagent.scan.EndpointInfo;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.security.PathGuard;
import jakarta.validation.Valid;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/diagrams")
public class DiagramController {

    private final PathGuard pathGuard;
    private final ServiceModelCache serviceModelCache;
    private final DiagramAgent diagramAgent;
    private final DiagramRenderer diagramRenderer;
    private final GitService gitService;
    private final ModelDiffer modelDiffer;

    public DiagramController(
        PathGuard pathGuard,
        ServiceModelCache serviceModelCache,
        DiagramAgent diagramAgent,
        DiagramRenderer diagramRenderer,
        GitService gitService,
        ModelDiffer modelDiffer
    ) {
        this.pathGuard = pathGuard;
        this.serviceModelCache = serviceModelCache;
        this.diagramAgent = diagramAgent;
        this.diagramRenderer = diagramRenderer;
        this.gitService = gitService;
        this.modelDiffer = modelDiffer;
    }

    @PostMapping
    public ResponseEntity<DiagramResponse> generateDiagram(@Valid @RequestBody DiagramRequest request) {
        Path resolvedPath = pathGuard.validateAndResolve(request.effectivePath());
        int maxDepth = request.maxDepth() != null && request.maxDepth() > 0 ? request.maxDepth() : 4;
        DiagramFormat format = request.resolvedFormat();

        // Check result cache first if enabled
        DiagramAgent.DiagramResult cachedResult = serviceModelCache.getCachedResult(
            resolvedPath, request.type(), request.entryPoint(), maxDepth
        );
        if (cachedResult != null) {
            String imageBase64 = null;
            String contentType = null;
            if (format != DiagramFormat.MERMAID) {
                byte[] bytes = diagramRenderer.render(cachedResult.mermaid(), format);
                imageBase64 = Base64.getEncoder().encodeToString(bytes);
                contentType = format.contentType();
            }
            return ResponseEntity.ok(new DiagramResponse(
                cachedResult.type(),
                cachedResult.mermaid(),
                cachedResult.valid(),
                cachedResult.attempts(),
                cachedResult.warnings(),
                true,
                imageBase64,
                contentType
            ));
        }

        ServiceModel model = serviceModelCache.getOrScan(resolvedPath);

        DiagramAgent.DiagramResult result = diagramAgent.generateDiagram(
            resolvedPath,
            model,
            request.type(),
            request.entryPoint(),
            maxDepth
        );

        serviceModelCache.putResult(resolvedPath, request.type(), request.entryPoint(), maxDepth, result);

        String imageBase64 = null;
        String contentType = null;
        if (format != DiagramFormat.MERMAID) {
            byte[] bytes = diagramRenderer.render(result.mermaid(), format);
            imageBase64 = Base64.getEncoder().encodeToString(bytes);
            contentType = format.contentType();
        }

        return ResponseEntity.ok(new DiagramResponse(
            result.type(),
            result.mermaid(),
            result.valid(),
            result.attempts(),
            result.warnings(),
            result.cached(),
            imageBase64,
            contentType
        ));
    }

    @PostMapping(value = "/render", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> renderDiagram(@Valid @RequestBody RenderRequest request) {
        byte[] imageBytes = diagramRenderer.render(request.mermaid(), request.format());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, request.format().contentType())
            .body(imageBytes);
    }

    @PostMapping("/diff")
    public ResponseEntity<DiffResponse> generateDiffDiagram(@Valid @RequestBody DiffRequest request) {
        Path resolvedPath = pathGuard.validateAndResolve(request.effectivePath());
        int maxDepth = request.maxDepth() != null && request.maxDepth() > 0 ? request.maxDepth() : 4;
        String fromRef = request.resolvedFromRef();
        String toRef = request.resolvedToRef();

        ServiceModel fromModel = gitService.extractModelAtRef(resolvedPath, fromRef);
        ServiceModel toModel = gitService.extractModelAtRef(resolvedPath, toRef);

        StructuralDiff diff = modelDiffer.diff(fromModel, toModel, request.type(), request.entryPoint());

        DiagramAgent.DiagramResult result = diagramAgent.generateDiffDiagram(
            resolvedPath,
            fromModel,
            toModel,
            diff,
            request.type(),
            request.entryPoint(),
            maxDepth
        );

        DiagramFormat format = request.resolvedFormat();
        String imageBase64 = null;
        String contentType = null;
        if (format != DiagramFormat.MERMAID) {
            byte[] bytes = diagramRenderer.render(result.mermaid(), format);
            imageBase64 = Base64.getEncoder().encodeToString(bytes);
            contentType = format.contentType();
        }

        return ResponseEntity.ok(new DiffResponse(
            result.type(),
            fromRef,
            toRef,
            result.mermaid(),
            result.valid(),
            result.attempts(),
            result.warnings(),
            diff.changes(),
            diff.summary(),
            imageBase64,
            contentType
        ));
    }

    @GetMapping("/endpoints")
    public ResponseEntity<List<EndpointResponse>> listEndpoints(
        @RequestParam(name = "path", required = false) String path,
        @RequestParam(name = "projectPath", required = false) String projectPath
    ) {
        String targetPath = path != null && !path.isBlank() ? path : projectPath;
        Path resolvedPath = pathGuard.validateAndResolve(targetPath);
        ServiceModel model = serviceModelCache.getOrScan(resolvedPath);

        List<EndpointResponse> dtos = model.endpoints().stream()
            .map(e -> new EndpointResponse(
                e.httpMethod(),
                e.path(),
                e.controllerClass() + "#" + e.methodName()
            ))
            .toList();

        return ResponseEntity.ok(dtos);
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, String>> clearCache() {
        serviceModelCache.clear();
        return ResponseEntity.ok(Map.of(
            "status", "CLEARED",
            "message", "Service model and diagram result caches have been cleared."
        ));
    }
}
