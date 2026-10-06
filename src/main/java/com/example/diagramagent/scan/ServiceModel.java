package com.example.diagramagent.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record ServiceModel(
    List<ClassInfo> classes,
    List<EndpointInfo> endpoints,
    List<EnumInfo> enums,
    List<StateHint> stateHints,
    List<String> warnings
) {
    public ServiceModel {
        classes = classes != null ? List.copyOf(classes) : List.of();
        endpoints = endpoints != null ? List.copyOf(endpoints) : List.of();
        enums = enums != null ? List.copyOf(enums) : List.of();
        stateHints = stateHints != null ? List.copyOf(stateHints) : List.of();
        warnings = warnings != null ? new ArrayList<>(warnings) : new ArrayList<>();
    }

    public Optional<ClassInfo> findClass(String className) {
        if (className == null || className.isBlank()) return Optional.empty();
        return classes.stream()
            .filter(c -> c.name().equalsIgnoreCase(className) ||
                (c.packageName() + "." + c.name()).equalsIgnoreCase(className))
            .findFirst();
    }

    public Optional<EndpointInfo> findEndpoint(String entryPoint) {
        if (entryPoint == null || entryPoint.isBlank()) {
            return endpoints.isEmpty() ? Optional.empty() : Optional.of(endpoints.get(0));
        }

        String trimmed = entryPoint.trim();

        // Check if format is Controller#method
        if (trimmed.contains("#")) {
            String[] parts = trimmed.split("#", 2);
            String ctrl = parts[0].trim();
            String method = parts[1].trim();
            Optional<EndpointInfo> match = endpoints.stream()
                .filter(e -> (e.controllerClass().equalsIgnoreCase(ctrl) ||
                    e.controllerClass().endsWith("." + ctrl)) &&
                    e.methodName().equalsIgnoreCase(method))
                .findFirst();
            if (match.isPresent()) return match;
        }

        // Check if format is "METHOD /path" or "/path"
        String methodPrefix = "";
        String pathMatch = trimmed;
        if (trimmed.contains(" ") && trimmed.indexOf(' ') < trimmed.indexOf('/')) {
            int spaceIdx = trimmed.indexOf(' ');
            methodPrefix = trimmed.substring(0, spaceIdx).trim();
            pathMatch = trimmed.substring(spaceIdx + 1).trim();
        }

        final String finalMethod = methodPrefix;
        final String finalPath = pathMatch;
        Optional<EndpointInfo> match = endpoints.stream()
            .filter(e -> {
                boolean mMatches = finalMethod.isBlank() || e.httpMethod().equalsIgnoreCase(finalMethod);
                boolean pMatches = e.path().equalsIgnoreCase(finalPath) ||
                    e.path().replaceAll("/+", "/").equalsIgnoreCase(finalPath.replaceAll("/+", "/"));
                return mMatches && pMatches;
            })
            .findFirst();
        if (match.isPresent()) return match;

        // Fallback: match by methodName or controller
        return endpoints.stream()
            .filter(e -> e.methodName().equalsIgnoreCase(trimmed) ||
                e.controllerClass().equalsIgnoreCase(trimmed))
            .findFirst();
    }

    public Optional<MethodInfo> findMethod(String className, String methodName) {
        return findClass(className)
            .flatMap(c -> c.methods().stream()
                .filter(m -> m.name().equalsIgnoreCase(methodName))
                .findFirst());
    }

    public String toPromptContext(DiagramType type, String entryPoint, int maxDepth, int maxContextChars) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== EXTRACTED SERVICE STRUCTURE ===\n\n");
        sb.append("Diagram Type Requested: ").append(type).append("\n");

        if (entryPoint != null && !entryPoint.isBlank()) {
            sb.append("Entry Point: ").append(entryPoint).append("\n");
        }
        sb.append("Max Depth: ").append(maxDepth).append("\n\n");

        switch (type) {
            case SEQUENCE, FLOWCHART -> buildSequenceOrFlowchartContext(sb, entryPoint, maxDepth);
            case STATE -> buildStateContext(sb);
        }

        String fullContext = sb.toString();
        if (fullContext.length() > maxContextChars) {
            return pruneContext(type, entryPoint, maxDepth, maxContextChars);
        }

        return fullContext;
    }

    private void buildSequenceOrFlowchartContext(StringBuilder sb, String entryPoint, int maxDepth) {
        sb.append("Endpoints:\n");
        for (EndpointInfo ep : endpoints) {
            sb.append("  - ").append(ep.toDisplayString()).append("\n");
        }
        sb.append("\n");

        sb.append("Components & Call Structure:\n");
        for (ClassInfo cls : classes) {
            sb.append("Class: ").append(cls.name())
                .append(" (").append(cls.stereotype()).append(")\n");
            if (!cls.dependencies().isEmpty()) {
                sb.append("  Dependencies:\n");
                for (DependencyInfo dep : cls.dependencies()) {
                    sb.append("    - ").append(dep.name()).append(": ").append(dep.type()).append("\n");
                }
            }
            if (!cls.methods().isEmpty()) {
                sb.append("  Methods:\n");
                for (MethodInfo m : cls.methods()) {
                    sb.append("    - ").append(m.signature())
                        .append(" -> returns ").append(m.returnType()).append("\n");
                    if (!m.outgoingCalls().isEmpty()) {
                        sb.append("      Calls:\n");
                        for (CallInfo c : m.outgoingCalls()) {
                            sb.append("        -> ").append(c.targetClass()).append("#").append(c.methodName())
                                .append("(").append(c.arguments()).append(")")
                                .append(" [kind=").append(c.kind()).append("]");
                            if (c.condition() != null && !c.condition().isBlank()) {
                                sb.append(" when ").append(c.condition());
                            }
                            sb.append("\n");
                        }
                    }
                    if (!m.controlFlows().isEmpty()) {
                        sb.append("      Control Flow:\n");
                        for (ControlFlowInfo cf : m.controlFlows()) {
                            sb.append("        * ").append(cf.kind())
                                .append(" (").append(cf.conditionOrDetail()).append(")\n");
                            for (CallInfo c : cf.calls()) {
                                sb.append("            -> ").append(c.targetClass()).append("#").append(c.methodName());
                                if (c.arguments() != null && !c.arguments().isBlank()) {
                                    sb.append("(").append(c.arguments()).append(")");
                                }
                                sb.append(" [").append(c.kind()).append("]\n");
                            }
                        }
                    }
                }
            }
            sb.append("\n");
        }
    }

    private void buildStateContext(StringBuilder sb) {
        sb.append("Enums:\n");
        for (EnumInfo e : enums) {
            sb.append("  Enum: ").append(e.name())
                .append(" Constants: ").append(e.constants()).append("\n");
        }
        sb.append("\n");

        sb.append("State Hints & Transitions:\n");
        if (stateHints.isEmpty()) {
            sb.append("  (No state hints or transitions detected)\n");
        } else {
            for (StateHint hint : stateHints) {
                sb.append("  Entity: ").append(hint.targetClass())
                    .append(", State Field: ").append(hint.fieldName())
                    .append(" (type=").append(hint.enumType()).append(")\n");
                for (StateTransitionHint tr : hint.transitions()) {
                    sb.append("    Transition: [")
                        .append(tr.sourceState() != null ? tr.sourceState() : "?")
                        .append("] -> [").append(tr.targetState()).append("]")
                        .append(" triggered by ").append(tr.triggeringMethod());
                    if (tr.eventOrCondition() != null && !tr.eventOrCondition().isBlank()) {
                        sb.append(" condition: ").append(tr.eventOrCondition());
                    }
                    sb.append("\n");
                }
            }
        }
        sb.append("\n");

        sb.append("Classes with State Operations:\n");
        for (ClassInfo cls : classes) {
            sb.append("Class: ").append(cls.name()).append(" (").append(cls.stereotype()).append(")\n");
            for (MethodInfo m : cls.methods()) {
                if (!m.controlFlows().isEmpty() || !m.outgoingCalls().isEmpty()) {
                    sb.append("  Method: ").append(m.signature()).append("\n");
                    for (ControlFlowInfo cf : m.controlFlows()) {
                        sb.append("    * ").append(cf.kind()).append(": ").append(cf.conditionOrDetail()).append("\n");
                    }
                }
            }
        }
    }

    private String pruneContext(DiagramType type, String entryPoint, int maxDepth, int maxContextChars) {
        warnings.add("Context exceeded limit of " + maxContextChars + " chars; pruned details.");
        // Pruning pass: omit private methods, full control flow, shorten to fit limit
        StringBuilder sb = new StringBuilder();
        sb.append("=== EXTRACTED SERVICE STRUCTURE (PRUNED) ===\n\n");
        sb.append("Diagram Type Requested: ").append(type).append("\n");
        if (entryPoint != null && !entryPoint.isBlank()) {
            sb.append("Entry Point: ").append(entryPoint).append("\n");
        }

        if (type == DiagramType.STATE) {
            buildStateContext(sb);
        } else {
            sb.append("Endpoints:\n");
            for (EndpointInfo ep : endpoints) {
                sb.append("  - ").append(ep.toDisplayString()).append("\n");
            }
            sb.append("\nCall Structure:\n");
            for (ClassInfo cls : classes) {
                sb.append("Class: ").append(cls.name()).append(" (").append(cls.stereotype()).append(")\n");
                for (MethodInfo m : cls.methods()) {
                    if (m.isPrivate()) continue; // Prune private helpers
                    sb.append("  ").append(m.signature()).append("\n");
                    for (CallInfo c : m.outgoingCalls()) {
                        sb.append("    -> ").append(c.targetClass()).append("#").append(c.methodName());
                        if (c.arguments() != null && !c.arguments().isBlank()) {
                            sb.append("(").append(c.arguments()).append(")");
                        }
                        sb.append(" [").append(c.kind()).append("]\n");
                    }
                }
            }
        }

        String pruned = sb.toString();
        if (pruned.length() > maxContextChars) {
            return pruned.substring(0, maxContextChars - 50) + "\n... [TRUNCATED DUE TO SIZE]";
        }
        return pruned;
    }
}
