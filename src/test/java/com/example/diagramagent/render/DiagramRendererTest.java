package com.example.diagramagent.render;

import com.example.diagramagent.config.DiagramProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagramRendererTest {

    private DiagramProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DiagramProperties(
            null, 60000, 2, 4, false, 15, "mmdc", 500, 1048576L,
            new DiagramProperties.CacheProperties(true, 5, 30, false),
            null
        );
    }

    @Test
    void renderSvgSuccessWithFakeExecutor() {
        byte[] expectedSvg = "<svg>diagram</svg>".getBytes();

        DiagramRenderer.ProcessExecutor fakeExecutor = (command, timeout) -> {
            // Find output file in args after -o
            int outIdx = command.indexOf("-o");
            if (outIdx >= 0 && outIdx + 1 < command.size()) {
                Path outPath = Path.of(command.get(outIdx + 1));
                Files.write(outPath, expectedSvg);
            }
            return new DiagramRenderer.ProcessResult(0, "success", "");
        };

        DiagramRenderer renderer = new DiagramRenderer(properties, fakeExecutor);
        byte[] result = renderer.render("sequenceDiagram\nA->>B: hi", DiagramFormat.SVG);

        assertNotNull(result);
        assertArrayEquals(expectedSvg, result);
    }

    @Test
    void renderThrowsMmdcNotAvailableWhenProcessFails() {
        DiagramRenderer.ProcessExecutor fakeExecutor = (command, timeout) -> {
            throw new IOException("Cannot run program 'mmdc': CreateProcess error=2, The system cannot find the file specified");
        };

        DiagramRenderer renderer = new DiagramRenderer(properties, fakeExecutor);

        MmdcNotAvailableException ex = assertThrows(
            MmdcNotAvailableException.class,
            () -> renderer.render("sequenceDiagram\nA->>B: hi", DiagramFormat.SVG)
        );
        assertTrue(ex.getMessage().contains("Mermaid CLI (mmdc) is not available"));
    }

    @Test
    void renderThrowsMmdcNotAvailableWhenExitCodeNonZero() {
        DiagramRenderer.ProcessExecutor fakeExecutor = (command, timeout) ->
            new DiagramRenderer.ProcessResult(1, "", "Syntax error in diagram");

        DiagramRenderer renderer = new DiagramRenderer(properties, fakeExecutor);

        MmdcNotAvailableException ex = assertThrows(
            MmdcNotAvailableException.class,
            () -> renderer.render("sequenceDiagram\nA->>B: hi", DiagramFormat.PNG)
        );
        assertTrue(ex.getMessage().contains("Mermaid CLI failed with exit code 1"));
    }

    @Test
    void isMmdcAvailableReturnsFalseWhenNotInstalled() {
        DiagramRenderer.ProcessExecutor fakeExecutor = (command, timeout) -> {
            throw new IOException("mmdc not found");
        };

        DiagramRenderer renderer = new DiagramRenderer(properties, fakeExecutor);
        assertFalse(renderer.isMmdcAvailable());
    }

    @Test
    void isMmdcAvailableReturnsTrueWhenSuccess() {
        DiagramRenderer.ProcessExecutor fakeExecutor = (command, timeout) ->
            new DiagramRenderer.ProcessResult(0, "10.9.1", "");

        DiagramRenderer renderer = new DiagramRenderer(properties, fakeExecutor);
        assertTrue(renderer.isMmdcAvailable());
    }
}
