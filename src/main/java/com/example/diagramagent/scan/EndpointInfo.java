package com.example.diagramagent.scan;

public record EndpointInfo(
    String httpMethod,
    String path,
    String controllerClass,
    String methodName
) {
    public String toDisplayString() {
        return httpMethod + " " + path + " -> " + controllerClass + "#" + methodName;
    }
}
