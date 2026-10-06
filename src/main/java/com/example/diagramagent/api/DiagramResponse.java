package com.example.diagramagent.api;

import com.example.diagramagent.scan.DiagramType;
import java.util.List;

public record DiagramResponse(
    DiagramType type,
    String mermaid,
    boolean valid,
    int attempts,
    List<String> warnings,
    boolean cached
) {
    public DiagramResponse(
        DiagramType type,
        String mermaid,
        boolean valid,
        int attempts,
        List<String> warnings
    ) {
        this(type, mermaid, valid, attempts, warnings, false);
    }
}
