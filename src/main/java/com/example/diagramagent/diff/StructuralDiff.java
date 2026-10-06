package com.example.diagramagent.diff;

import java.util.List;

public record StructuralDiff(
    List<StructuralChange> changes,
    String summary,
    boolean entryPointUnchanged
) {}
