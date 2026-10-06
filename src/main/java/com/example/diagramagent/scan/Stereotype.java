package com.example.diagramagent.scan;

public enum Stereotype {
    REST_CONTROLLER,
    CONTROLLER,
    SERVICE,
    REPOSITORY,
    COMPONENT,
    FEIGN_CLIENT,
    CONFIGURATION,
    ENTITY,
    UNKNOWN;

    public static Stereotype fromAnnotation(String annotationName) {
        if (annotationName == null) return UNKNOWN;
        String simple = annotationName.contains(".") ?
            annotationName.substring(annotationName.lastIndexOf('.') + 1) : annotationName;
        return switch (simple) {
            case "RestController" -> REST_CONTROLLER;
            case "Controller" -> CONTROLLER;
            case "Service" -> SERVICE;
            case "Repository" -> REPOSITORY;
            case "Component" -> COMPONENT;
            case "FeignClient" -> FEIGN_CLIENT;
            case "Configuration" -> CONFIGURATION;
            case "Entity", "Table", "Document" -> ENTITY;
            default -> UNKNOWN;
        };
    }
}
