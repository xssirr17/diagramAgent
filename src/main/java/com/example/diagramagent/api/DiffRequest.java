package com.example.diagramagent.api;

import com.example.diagramagent.render.DiagramFormat;
import com.example.diagramagent.scan.DiagramType;
import jakarta.validation.constraints.NotNull;

public record DiffRequest(
    String path,
    String projectPath,
    String fromRef,
    String toRef,
    @NotNull(message = "Diagram type is required (SEQUENCE, FLOWCHART, or STATE)")
    DiagramType type,
    String entryPoint,
    Integer maxDepth,
    DiagramFormat format
) {
    public String effectivePath() {
        return path != null && !path.isBlank() ? path : projectPath;
    }

    public String resolvedFromRef() {
        return fromRef != null && !fromRef.isBlank() ? fromRef.trim() : "HEAD~1";
    }

    public String resolvedToRef() {
        return toRef != null && !toRef.isBlank() ? toRef.trim() : "HEAD";
    }

    public DiagramFormat resolvedFormat() {
        return format != null ? format : DiagramFormat.MERMAID;
    }
}
