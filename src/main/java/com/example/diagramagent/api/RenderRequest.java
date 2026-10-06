package com.example.diagramagent.api;

import com.example.diagramagent.render.DiagramFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RenderRequest(
    @NotBlank(message = "mermaid content must not be blank")
    String mermaid,

    @NotNull(message = "format must be specified (SVG or PNG)")
    DiagramFormat format
) {}
