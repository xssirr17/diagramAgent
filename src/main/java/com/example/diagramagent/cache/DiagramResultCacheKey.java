package com.example.diagramagent.cache;

import com.example.diagramagent.scan.DiagramType;

public record DiagramResultCacheKey(
    String modelFingerprint,
    DiagramType type,
    String entryPoint,
    int maxDepth,
    String modelName,
    String promptVersion
) {}
