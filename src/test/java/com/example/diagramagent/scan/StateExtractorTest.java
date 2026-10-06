package com.example.diagramagent.scan;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateExtractorTest {

    private final StateExtractor extractor = new StateExtractor();

    @Test
    void extractsEnumsAndStateTransitions() {
        String enumCode = """
            package com.example;
            public enum OrderStatus {
                NEW, PAID, SHIPPED, CANCELLED
            }
            """;

        String entityCode = """
            package com.example;
            public class Order {
                private String id;
                private OrderStatus status;

                public void setStatus(OrderStatus status) {
                    this.status = status;
                }
            }
            """;

        String serviceCode = """
            package com.example;
            public class OrderService {
                public void pay(Order order) {
                    if (order.getStatus() == OrderStatus.NEW) {
                        order.setStatus(OrderStatus.PAID);
                    } else {
                        order.setStatus(OrderStatus.CANCELLED);
                    }
                }
            }
            """;

        CompilationUnit enumCu = StaticJavaParser.parse(enumCode);
        EnumDeclaration enumDecl = enumCu.getEnumByName("OrderStatus").orElseThrow();
        List<EnumInfo> enums = extractor.extractEnums(List.of(enumDecl), "com.example");

        assertEquals(1, enums.size());
        assertEquals("OrderStatus", enums.get(0).name());
        assertEquals(List.of("NEW", "PAID", "SHIPPED", "CANCELLED"), enums.get(0).constants());

        CompilationUnit entityCu = StaticJavaParser.parse(entityCode);
        ClassOrInterfaceDeclaration entityDecl = entityCu.getClassByName("Order").orElseThrow();

        CompilationUnit serviceCu = StaticJavaParser.parse(serviceCode);
        ClassOrInterfaceDeclaration serviceDecl = serviceCu.getClassByName("OrderService").orElseThrow();

        List<StateHint> hints = extractor.extractStateHints(
            List.of(entityDecl, serviceDecl),
            enums
        );

        assertFalse(hints.isEmpty());
        StateHint orderHint = hints.stream()
            .filter(h -> h.targetClass().equals("Order"))
            .findFirst()
            .orElseThrow();

        assertEquals("status", orderHint.fieldName());
        assertEquals("OrderStatus", orderHint.enumType());

        // Verify transitions detected (PAID, CANCELLED)
        assertTrue(orderHint.transitions().stream().anyMatch(t ->
            t.targetState().equals("PAID") && t.triggeringMethod().contains("pay")));
        assertTrue(orderHint.transitions().stream().anyMatch(t ->
            t.targetState().equals("CANCELLED") && t.triggeringMethod().contains("pay")));
    }
}
