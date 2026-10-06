package com.example.diagramagent.api;

import com.example.diagramagent.scan.DiagramType;
import jakarta.validation.constraints.NotNull;

public record DiagramRequest(
    String path,
    String projectPath,
    @NotNull(message = "Diagram type is required (SEQUENCE, FLOWCHART, or STATE)")
    DiagramType type,
    String entryPoint,
    Integer maxDepth
) {
    public String effectivePath() {
        return path != null && !path.isBlank() ? path : projectPath;
    }
}
