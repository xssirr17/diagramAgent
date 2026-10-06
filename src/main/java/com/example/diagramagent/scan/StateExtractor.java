package com.example.diagramagent.scan;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class StateExtractor {

    private static final Pattern STATE_FIELD_PATTERN =
        Pattern.compile(".*(status|state|phase|stage).*", Pattern.CASE_INSENSITIVE);

    public List<EnumInfo> extractEnums(List<EnumDeclaration> enumDecls, String packageName) {
        List<EnumInfo> list = new ArrayList<>();
        for (EnumDeclaration decl : enumDecls) {
            String name = decl.getNameAsString();
            List<String> constants = decl.getEntries().stream()
                .map(EnumConstantDeclaration::getNameAsString)
                .toList();
            list.add(new EnumInfo(name, packageName, constants));
        }
        return list;
    }

    public List<StateHint> extractStateHints(
        List<ClassOrInterfaceDeclaration> classDecls,
        List<EnumInfo> enums
    ) {
        Map<String, EnumInfo> enumMap = new HashMap<>();
        for (EnumInfo e : enums) {
            enumMap.put(e.name(), e);
        }

        List<StateHint> hints = new ArrayList<>();

        for (ClassOrInterfaceDeclaration cls : classDecls) {
            String className = cls.getNameAsString();
            for (FieldDeclaration field : cls.getFields()) {
                String fieldType = field.getElementType().asString();
                if (enumMap.containsKey(fieldType)) {
                    field.getVariables().forEach(var -> {
                        String fieldName = var.getNameAsString();
                        if (STATE_FIELD_PATTERN.matcher(fieldName).matches()) {
                            hints.add(new StateHint(className, fieldName, fieldType, new ArrayList<>()));
                        }
                    });
                }
            }
        }

        // Now scan all methods across classes to find transitions
        Set<String> allEnumConstants = new HashSet<>();
        enums.forEach(e -> allEnumConstants.addAll(e.constants()));

        for (ClassOrInterfaceDeclaration cls : classDecls) {
            String clsName = cls.getNameAsString();
            for (MethodDeclaration method : cls.getMethods()) {
                String methodName = clsName + "#" + method.getNameAsString();
                method.getBody().ifPresent(body -> {
                    // Check for Spring State Machine
                    extractSpringStateMachine(method, hints);

                    // Check for Setter calls: .setStatus(OrderStatus.PAID)
                    List<MethodCallExpr> calls = body.findAll(MethodCallExpr.class);
                    for (MethodCallExpr call : calls) {
                        String name = call.getNameAsString();
                        if (STATE_FIELD_PATTERN.matcher(name).matches() || name.startsWith("set")) {
                            if (!call.getArguments().isEmpty()) {
                                String arg = call.getArgument(0).toString();
                                String targetState = extractStateConstant(arg, allEnumConstants);
                                if (targetState != null) {
                                    String sourceState = findEnclosingConditionState(call, allEnumConstants);
                                    addTransitionToMatchingHint(hints, targetState, sourceState, methodName, arg);
                                }
                            }
                        }
                    }

                    // Check direct assignments: this.status = OrderStatus.PAID
                    List<AssignExpr> assigns = body.findAll(AssignExpr.class);
                    for (AssignExpr assign : assigns) {
                        String target = assign.getTarget().toString();
                        if (STATE_FIELD_PATTERN.matcher(target).matches()) {
                            String valueStr = assign.getValue().toString();
                            String targetState = extractStateConstant(valueStr, allEnumConstants);
                            if (targetState != null) {
                                String sourceState = findEnclosingConditionState(assign, allEnumConstants);
                                addTransitionToMatchingHint(hints, targetState, sourceState, methodName, valueStr);
                            }
                        }
                    }
                });
            }
        }

        return hints;
    }

    private void extractSpringStateMachine(MethodDeclaration method, List<StateHint> hints) {
        List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
        for (MethodCallExpr call : calls) {
            if (call.getNameAsString().equals("withExternal") || call.getNameAsString().equals("event")) {
                // Check chain for source(), target(), event()
                String chain = call.toString();
                String source = extractArgument(chain, "source");
                String target = extractArgument(chain, "target");
                String event = extractArgument(chain, "event");
                if (target != null) {
                    StateTransitionHint transition = new StateTransitionHint(
                        source, target, method.getNameAsString(), event
                    );
                    if (!hints.isEmpty()) {
                        hints.get(0).transitions().add(transition);
                    } else {
                        hints.add(new StateHint("StateMachine", "state", "State",
                            new ArrayList<>(List.of(transition))));
                    }
                }
            }
        }
    }

    private String extractArgument(String chain, String methodName) {
        int idx = chain.indexOf("." + methodName + "(");
        if (idx >= 0) {
            int start = idx + methodName.length() + 2;
            int end = chain.indexOf(")", start);
            if (end > start) {
                return chain.substring(start, end).replace("\"", "").trim();
            }
        }
        return null;
    }

    private String extractStateConstant(String exprStr, Set<String> allConstants) {
        if (exprStr.contains(".")) {
            String constantPart = exprStr.substring(exprStr.lastIndexOf('.') + 1).trim();
            if (allConstants.contains(constantPart)) {
                return constantPart;
            }
        }
        for (String c : allConstants) {
            if (exprStr.equals(c) || exprStr.endsWith("." + c)) {
                return c;
            }
        }
        return null;
    }

    private String findEnclosingConditionState(Node node, Set<String> allConstants) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof IfStmt ifStmt) {
                String cond = ifStmt.getCondition().toString();
                for (String c : allConstants) {
                    if (cond.contains(c)) {
                        return c;
                    }
                }
            } else if (current instanceof SwitchEntry entry) {
                for (Node label : entry.getLabels()) {
                    for (String c : allConstants) {
                        if (label.toString().contains(c)) {
                            return c;
                        }
                    }
                }
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    private void addTransitionToMatchingHint(
        List<StateHint> hints,
        String targetState,
        String sourceState,
        String triggeringMethod,
        String rawExpr
    ) {
        if (hints.isEmpty()) {
            hints.add(new StateHint("Entity", "status", "StateEnum", new ArrayList<>()));
        }

        StateTransitionHint transition = new StateTransitionHint(
            sourceState,
            targetState,
            triggeringMethod,
            rawExpr
        );

        // Add to all relevant hints (avoid duplicates)
        for (StateHint hint : hints) {
            if (!hint.transitions().contains(transition)) {
                hint.transitions().add(transition);
            }
        }
    }
}
