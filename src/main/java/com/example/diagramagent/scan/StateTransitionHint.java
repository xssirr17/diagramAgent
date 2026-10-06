package com.example.diagramagent.scan;

public record StateTransitionHint(
    String sourceState,
    String targetState,
    String triggeringMethod,
    String eventOrCondition
) {}
