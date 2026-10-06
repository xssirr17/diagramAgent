package com.example.diagramagent.api;

public record EndpointResponse(
    String httpMethod,
    String path,
    String handler
) {}
