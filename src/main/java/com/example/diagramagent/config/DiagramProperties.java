package com.example.diagramagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "diagram")
public record DiagramProperties(
    String allowedRoot,
    int maxContextChars,
    int maxRetries,
    int maxDepth,
    boolean agentic,
    int maxToolCalls,
    String mermaidCliPath,
    int maxFilesScanned,
    long maxFileSizeBytes
) {
    public DiagramProperties {
        if (maxContextChars <= 0) {
            maxContextChars = 60000;
        }
        if (maxRetries <= 0) {
            maxRetries = 2;
        }
        if (maxDepth <= 0) {
            maxDepth = 4;
        }
        if (maxToolCalls <= 0) {
            maxToolCalls = 15;
        }
        if (mermaidCliPath == null || mermaidCliPath.isBlank()) {
            mermaidCliPath = "mmdc";
        }
        if (maxFilesScanned <= 0) {
            maxFilesScanned = 500;
        }
        if (maxFileSizeBytes <= 0) {
            maxFileSizeBytes = 1048576L; // 1 MB default
        }
    }
}
