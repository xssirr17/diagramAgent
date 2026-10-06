package com.example.diagramagent.scan;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointExtractorTest {

    private final EndpointExtractor extractor = new EndpointExtractor();

    @Test
    void extractsCombinedEndpoints() {
        String code = """
            package com.example;
            import org.springframework.web.bind.annotation.*;

            @RestController
            @RequestMapping("/api/v1/orders")
            public class OrderController {
                @PostMapping
                public String create() { return "ok"; }

                @GetMapping("/{id}")
                public String get(@PathVariable String id) { return "ok"; }

                @DeleteMapping("/{id}/cancel")
                public void cancel(@PathVariable String id) {}
            }
            """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration cls = cu.getClassByName("OrderController").orElseThrow();
        List<EndpointInfo> endpoints = extractor.extractEndpoints(cls);

        assertEquals(3, endpoints.size());

        assertTrue(endpoints.stream().anyMatch(e ->
            e.httpMethod().equals("POST") && e.path().equals("/api/v1/orders") && e.methodName().equals("create")));

        assertTrue(endpoints.stream().anyMatch(e ->
            e.httpMethod().equals("GET") && e.path().equals("/api/v1/orders/{id}") && e.methodName().equals("get")));

        assertTrue(endpoints.stream().anyMatch(e ->
            e.httpMethod().equals("DELETE") && e.path().equals("/api/v1/orders/{id}/cancel") && e.methodName().equals("cancel")));
    }

    @Test
    void ignoresNonControllerClasses() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;

            @Service
            public class OrderService {
                public void doSomething() {}
            }
            """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration cls = cu.getClassByName("OrderService").orElseThrow();
        List<EndpointInfo> endpoints = extractor.extractEndpoints(cls);

        assertTrue(endpoints.isEmpty());
    }
}
