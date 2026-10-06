package com.example.diagramagent.scan;

import java.util.List;

public record ClassInfo(
    String name,
    String packageName,
    Stereotype stereotype,
    List<String> annotations,
    List<DependencyInfo> dependencies,
    List<MethodInfo> methods,
    String sourceFilePath
) {}
