package com.example.diagramagent.validate;

import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.DiagramType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidValidatorTest {

    private MermaidValidator validator;

    @BeforeEach
    void setUp() {
        DiagramProperties props = new DiagramProperties(
            null, 60000, 2, 4, false, 15, "non-existent-mmdc", 500, 1048576L
        );
        validator = new MermaidValidator(props);
    }

    @Test
    void validatesValidSequenceDiagramWithFallback() {
        String validSequence = """
            sequenceDiagram
                Client->>OrderController: POST /orders
                alt is valid
                    OrderController->>OrderService: create()
                else not valid
                    OrderController-->>Client: 400 Bad Request
                end
            """;

        ValidationResult result = validator.validate(validSequence, DiagramType.SEQUENCE);

        assertTrue(result.valid());
        assertTrue(result.usedFallback());
    }

    @Test
    void rejectsUnbalancedSequenceDiagramBlocks() {
        String unbalanced = """
            sequenceDiagram
                Client->>OrderController: POST /orders
                alt is valid
                    OrderController->>OrderService: create()
            """;

        ValidationResult result = validator.validate(unbalanced, DiagramType.SEQUENCE);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("Unbalanced"));
    }

    @Test
    void rejectsDiagramWithMarkdownFences() {
        String fenced = """
            ```mermaid
            sequenceDiagram
                A->>B: call
            ```
            """;

        ValidationResult result = validator.validate(fenced, DiagramType.SEQUENCE);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("markdown fences"));
    }

    @Test
    void rejectsIncorrectDiagramHeader() {
        String wrongHeader = """
            classDiagram
                class Order
            """;

        ValidationResult result = validator.validate(wrongHeader, DiagramType.FLOWCHART);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("First line does not match"));
    }

    @Test
    void rejectsEmptyBody() {
        String emptyBody = "flowchart TD";

        ValidationResult result = validator.validate(emptyBody, DiagramType.FLOWCHART);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("no content"));
    }
}
