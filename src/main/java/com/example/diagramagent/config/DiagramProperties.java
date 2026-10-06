package com.example.diagramagent.config;

import java.nio.file.Files;
import java.nio.file.Path;
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
    long maxFileSizeBytes,
    CacheProperties cache,
    String puppeteerConfigFile
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
            maxFilesScanned = 5000;
        }
        if (maxFileSizeBytes <= 0) {
            maxFileSizeBytes = 1048576L; // 1 MB default
        }
        if (cache == null) {
            cache = new CacheProperties(true, 5, 30, false);
        }
        if (puppeteerConfigFile == null || puppeteerConfigFile.isBlank()) {
            if (Files.exists(Path.of("puppeteer-config.json"))) {
                puppeteerConfigFile = "puppeteer-config.json";
            }
        }
    }

    public record CacheProperties(
        boolean enabled,
        int maxProjects,
        int ttlMinutes,
        boolean results
    ) {
        public CacheProperties {
            if (maxProjects <= 0) maxProjects = 5;
            if (ttlMinutes <= 0) ttlMinutes = 30;
        }
    }
}
