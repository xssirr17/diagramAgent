package com.example.diagramagent.api;

import com.example.diagramagent.diff.StructuralChange;
import com.example.diagramagent.scan.DiagramType;
import java.util.List;

public record DiffResponse(
    DiagramType type,
    String fromRef,
    String toRef,
    String mermaid,
    boolean valid,
    int attempts,
    List<String> warnings,
    List<StructuralChange> changes,
    String summary,
    String image,
    String contentType
) {
    public DiffResponse(
        DiagramType type,
        String fromRef,
        String toRef,
        String mermaid,
        boolean valid,
        int attempts,
        List<String> warnings,
        List<StructuralChange> changes,
        String summary
    ) {
        this(type, fromRef, toRef, mermaid, valid, attempts, warnings, changes, summary, null, null);
    }
}
