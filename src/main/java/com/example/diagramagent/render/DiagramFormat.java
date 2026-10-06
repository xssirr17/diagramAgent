package com.example.diagramagent.render;

public enum DiagramFormat {
    MERMAID,
    SVG,
    PNG;

    public String contentType() {
        return switch (this) {
            case MERMAID -> "text/plain";
            case SVG -> "image/svg+xml";
            case PNG -> "image/png";
        };
    }
}
