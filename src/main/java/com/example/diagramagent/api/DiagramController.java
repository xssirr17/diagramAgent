package com.example.diagramagent.api;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.scan.EndpointInfo;
import com.example.diagramagent.scan.ProjectScanner;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.security.PathGuard;
import jakarta.validation.Valid;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.ResponseEntity;
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
    private final ProjectScanner projectScanner;
    private final DiagramAgent diagramAgent;

    public DiagramController(
        PathGuard pathGuard,
        ProjectScanner projectScanner,
        DiagramAgent diagramAgent
    ) {
        this.pathGuard = pathGuard;
        this.projectScanner = projectScanner;
        this.diagramAgent = diagramAgent;
    }

    @PostMapping
    public ResponseEntity<DiagramResponse> generateDiagram(@Valid @RequestBody DiagramRequest request) {
        Path resolvedPath = pathGuard.validateAndResolve(request.effectivePath());
        ServiceModel model = projectScanner.scan(resolvedPath);

        int maxDepth = request.maxDepth() != null && request.maxDepth() > 0 ? request.maxDepth() : 4;
        DiagramAgent.DiagramResult result = diagramAgent.generateDiagram(
            resolvedPath,
            model,
            request.type(),
            request.entryPoint(),
            maxDepth
        );

        return ResponseEntity.ok(new DiagramResponse(
            result.type(),
            result.mermaid(),
            result.valid(),
            result.attempts(),
            result.warnings()
        ));
    }

    @GetMapping("/endpoints")
    public ResponseEntity<List<EndpointResponse>> listEndpoints(
        @RequestParam(name = "path", required = false) String path,
        @RequestParam(name = "projectPath", required = false) String projectPath
    ) {
        String targetPath = path != null && !path.isBlank() ? path : projectPath;
        Path resolvedPath = pathGuard.validateAndResolve(targetPath);
        ServiceModel model = projectScanner.scan(resolvedPath);

        List<EndpointResponse> dtos = model.endpoints().stream()
            .map(e -> new EndpointResponse(
                e.httpMethod(),
                e.path(),
                e.controllerClass() + "#" + e.methodName()
            ))
            .toList();

        return ResponseEntity.ok(dtos);
    }
}
