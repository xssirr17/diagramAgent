package com.example.diagramagent.scan;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallChainExtractorTest {

    private final CallChainExtractor extractor = new CallChainExtractor();

    @Test
    void extractsDependenciesAndExternalCalls() {
        String code = """
            package com.example;
            import org.springframework.stereotype.Service;
            import org.springframework.web.client.RestTemplate;

            @Service
            public class PaymentService {
                private final RestTemplate restTemplate;
                private final OrderRepository orderRepository;

                public PaymentService(RestTemplate restTemplate, OrderRepository orderRepository) {
                    this.restTemplate = restTemplate;
                    this.orderRepository = orderRepository;
                }

                public void pay(String orderId) {
                    if (orderId == null) {
                        throw new IllegalArgumentException("Invalid ID");
                    }
                    orderRepository.findById(orderId);
                    restTemplate.postForObject("/pay", orderId, Void.class);
                }
            }
            """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration cls = cu.getClassByName("PaymentService").orElseThrow();

        List<DependencyInfo> deps = extractor.extractDependencies(cls);
        assertEquals(2, deps.size());

        List<MethodInfo> methods = extractor.extractMethods(
            cls,
            deps,
            Map.of("PaymentService", "com.example.PaymentService", "OrderRepository", "com.example.OrderRepository")
        );

        assertEquals(1, methods.size());
        MethodInfo payMethod = methods.get(0);
        assertEquals("pay", payMethod.name());

        // Check external calls classified
        assertTrue(payMethod.outgoingCalls().stream().anyMatch(c ->
            c.kind() == CallKind.INTERNAL && c.targetClass().equals("OrderRepository")));

        assertTrue(payMethod.outgoingCalls().stream().anyMatch(c ->
            c.kind() == CallKind.HTTP && c.targetClass().equals("RestTemplate")));

        // Check control flows recorded (IF, THROW)
        assertTrue(payMethod.controlFlows().stream().anyMatch(cf -> cf.kind() == ControlFlowKind.IF));
        assertTrue(payMethod.controlFlows().stream().anyMatch(cf -> cf.kind() == ControlFlowKind.THROW));
    }

    @Test
    void protectsAgainstCycles() {
        // Model with ServiceA calling ServiceB, and ServiceB calling ServiceA
        CallInfo callToB = new CallInfo("ServiceB", "stepB", "", CallKind.INTERNAL, null);
        CallInfo callToA = new CallInfo("ServiceA", "stepA", "", CallKind.INTERNAL, null);

        MethodInfo methodA = new MethodInfo(
            "stepA", "stepA()", "void", List.of(), List.of(callToB), List.of(), "", false
        );
        MethodInfo methodB = new MethodInfo(
            "stepB", "stepB()", "void", List.of(), List.of(callToA), List.of(), "", false
        );

        ClassInfo classA = new ClassInfo(
            "ServiceA", "com.example", Stereotype.SERVICE, List.of(), List.of(), List.of(methodA), ""
        );
        ClassInfo classB = new ClassInfo(
            "ServiceB", "com.example", Stereotype.SERVICE, List.of(), List.of(), List.of(methodB), ""
        );

        ServiceModel model = new ServiceModel(
            List.of(classA, classB), List.of(), List.of(), List.of(), List.of()
        );

        // Tracing should not stack overflow
        List<CallInfo> trace = extractor.traceCallChain("ServiceA", "stepA", model, 10);
        // It will call B, then B calls A (cycle stopped)
        assertEquals(2, trace.size());
        assertEquals("ServiceB", trace.get(0).targetClass());
        assertEquals("ServiceA", trace.get(1).targetClass());
    }

    @Test
    void respectsDepthLimit() {
        // A -> B -> C -> D -> E
        MethodInfo methodA = new MethodInfo("m", "m()", "void", List.of(),
            List.of(new CallInfo("B", "m", "", CallKind.INTERNAL, null)), List.of(), "", false);
        MethodInfo methodB = new MethodInfo("m", "m()", "void", List.of(),
            List.of(new CallInfo("C", "m", "", CallKind.INTERNAL, null)), List.of(), "", false);
        MethodInfo methodC = new MethodInfo("m", "m()", "void", List.of(),
            List.of(new CallInfo("D", "m", "", CallKind.INTERNAL, null)), List.of(), "", false);

        ClassInfo clsA = new ClassInfo("A", "p", Stereotype.SERVICE, List.of(), List.of(), List.of(methodA), "");
        ClassInfo clsB = new ClassInfo("B", "p", Stereotype.SERVICE, List.of(), List.of(), List.of(methodB), "");
        ClassInfo clsC = new ClassInfo("C", "p", Stereotype.SERVICE, List.of(), List.of(), List.of(methodC), "");

        ServiceModel model = new ServiceModel(List.of(clsA, clsB, clsC), List.of(), List.of(), List.of(), List.of());

        // Max depth 1: only calls from A
        List<CallInfo> traceDepth1 = extractor.traceCallChain("A", "m", model, 1);
        assertEquals(1, traceDepth1.size());
        assertEquals("B", traceDepth1.get(0).targetClass());

        // Max depth 2: calls from A and B
        List<CallInfo> traceDepth2 = extractor.traceCallChain("A", "m", model, 2);
        assertEquals(2, traceDepth2.size());
        assertEquals("C", traceDepth2.get(1).targetClass());
    }
}
