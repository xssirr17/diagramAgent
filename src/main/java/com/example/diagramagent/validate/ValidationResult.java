package com.example.diagramagent.validate;

public record ValidationResult(
    boolean valid,
    String errorMessage,
    boolean usedFallback
) {}
