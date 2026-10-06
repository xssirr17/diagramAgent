package com.example.diagramagent.diff;

public record StructuralChange(
    ChangeType type,
    String name,
    ChangeStatus status,
    String details
) {}
