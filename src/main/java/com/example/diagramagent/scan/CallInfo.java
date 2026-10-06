package com.example.diagramagent.scan;

public record CallInfo(
    String targetClass,
    String methodName,
    String arguments,
    CallKind kind,
    String condition
) {}
