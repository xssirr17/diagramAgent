package com.example.diagramagent.scan;

import java.util.List;

public record StateHint(
    String targetClass,
    String fieldName,
    String enumType,
    List<StateTransitionHint> transitions
) {}
