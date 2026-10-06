package com.example.diagramagent.validate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MermaidSanitizerTest {

    @Test
    void stripsPreambleAndMarkdownFences() {
        String geminiRaw = """
            Here is the requested Mermaid sequence diagram for your service:
            ```mermaid
            sequenceDiagram
                Client->>OrderController: POST /orders
                OrderController->>OrderService: createOrder()
                OrderService-->>OrderController: Order
                OrderController-->>Client: 200 OK
            ```
            Hope this helps with your architectural review!
            """;

        String sanitized = MermaidSanitizer.sanitize(geminiRaw);

        assertFalse(sanitized.contains("Here is the requested"));
        assertFalse(sanitized.contains("```"));
        assertFalse(sanitized.contains("Hope this helps"));
        assertTrue(sanitized.startsWith("sequenceDiagram"));
        assertTrue(sanitized.contains("Client->>OrderController: POST /orders"));
    }

    @Test
    void extractsInsufficientInformationLine() {
        String geminiResponse = """
            Certainly! Here is my assessment:
            %% INSUFFICIENT_INFORMATION: No enum or state field found in project for STATE diagram
            Extra notes about the missing entity...
            """;

        String sanitized = MermaidSanitizer.sanitize(geminiResponse);

        assertTrue(MermaidSanitizer.isInsufficientInformation(sanitized));
        assertEquals(
            "No enum or state field found in project for STATE diagram",
            MermaidSanitizer.extractInsufficientReason(sanitized)
        );
    }

    @Test
    void handlesAlreadyCleanDiagram() {
        String clean = """
            flowchart TD
                start([Start]) --> proc[Process]
                proc --> endNode([End])
            """;

        String sanitized = MermaidSanitizer.sanitize(clean);

        assertTrue(sanitized.startsWith("flowchart TD"));
        assertTrue(sanitized.contains("start([Start])"));
    }
}
