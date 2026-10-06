package com.example.diagramagent.scan;

import com.example.diagramagent.config.DiagramProperties;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScannerTest {

    @Test
    void scansOrderServiceFixtureSuccessfully() {
        DiagramProperties props = new DiagramProperties(
            null, 60000, 2, 4, false, 15, "mmdc", 500, 1048576L
        );
        EndpointExtractor endpointExtractor = new EndpointExtractor();
        CallChainExtractor callChainExtractor = new CallChainExtractor();
        StateExtractor stateExtractor = new StateExtractor();
        ProjectScanner scanner = new ProjectScanner(props, endpointExtractor, callChainExtractor, stateExtractor);

        Path fixturePath = Paths.get("src/test/resources/fixtures/order-service").toAbsolutePath();
        ServiceModel model = scanner.scan(fixturePath);

        assertNotNull(model);
        assertFalse(model.classes().isEmpty());

        // Classes detected
        assertTrue(model.findClass("OrderController").isPresent());
        assertTrue(model.findClass("OrderService").isPresent());
        assertTrue(model.findClass("OrderRepository").isPresent());
        assertTrue(model.findClass("PaymentClient").isPresent());

        // Endpoints detected
        assertFalse(model.endpoints().isEmpty());
        assertTrue(model.endpoints().stream().anyMatch(e ->
            e.httpMethod().equals("POST") && e.path().equals("/orders")));
        assertTrue(model.endpoints().stream().anyMatch(e ->
            e.httpMethod().equals("POST") && e.path().equals("/orders/{id}/pay")));
        assertTrue(model.endpoints().stream().anyMatch(e ->
            e.httpMethod().equals("POST") && e.path().equals("/orders/{id}/ship")));
        assertTrue(model.endpoints().stream().anyMatch(e ->
            e.httpMethod().equals("GET") && e.path().equals("/orders/{id}")));

        // Enums detected
        assertTrue(model.enums().stream().anyMatch(e ->
            e.name().equals("OrderStatus") && e.constants().contains("PAID")));

        // State hints detected
        assertFalse(model.stateHints().isEmpty());
        assertTrue(model.stateHints().stream().anyMatch(h ->
            h.fieldName().equals("status") && h.enumType().equals("OrderStatus")));

        // Prompt context generation works
        String seqCtx = model.toPromptContext(DiagramType.SEQUENCE, "POST /orders", 4, 60000);
        assertTrue(seqCtx.contains("OrderController"));
        assertTrue(seqCtx.contains("OrderService"));

        String flowCtx = model.toPromptContext(DiagramType.FLOWCHART, "OrderController#payOrder", 4, 60000);
        assertTrue(flowCtx.contains("OrderService#payOrder"));

        String stateCtx = model.toPromptContext(DiagramType.STATE, null, 4, 60000);
        assertTrue(stateCtx.contains("OrderStatus"));
        assertTrue(stateCtx.contains("PAID"));
    }
}
