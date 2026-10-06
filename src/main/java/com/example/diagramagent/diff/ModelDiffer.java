package com.example.diagramagent.diff;

import com.example.diagramagent.scan.CallInfo;
import com.example.diagramagent.scan.ClassInfo;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.EndpointInfo;
import com.example.diagramagent.scan.EnumInfo;
import com.example.diagramagent.scan.MethodInfo;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.scan.StateHint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ModelDiffer {

    public StructuralDiff diff(ServiceModel fromModel, ServiceModel toModel, DiagramType type, String entryPoint) {
        List<StructuralChange> changes = new ArrayList<>();

        // 1. Diff Endpoints
        diffEndpoints(fromModel.endpoints(), toModel.endpoints(), changes);

        // 2. Diff Classes & Methods
        diffClassesAndMethods(fromModel.classes(), toModel.classes(), changes);

        // 3. Diff Enums
        diffEnums(fromModel.enums(), toModel.enums(), changes);

        // 4. Diff States
        diffStates(fromModel.stateHints(), toModel.stateHints(), changes);

        // 5. Evaluate Entry Point
        boolean entryPointUnchanged = evaluateEntryPoint(fromModel, toModel, entryPoint);

        // 6. Generate Summary
        String summary = generateSummary(changes);

        return new StructuralDiff(changes, summary, entryPointUnchanged);
    }

    private void diffEndpoints(List<EndpointInfo> fromEps, List<EndpointInfo> toEps, List<StructuralChange> changes) {
        Map<String, EndpointInfo> fromMap = new HashMap<>();
        for (EndpointInfo e : fromEps) fromMap.put(e.httpMethod() + " " + e.path(), e);

        Map<String, EndpointInfo> toMap = new HashMap<>();
        for (EndpointInfo e : toEps) toMap.put(e.httpMethod() + " " + e.path(), e);

        for (Map.Entry<String, EndpointInfo> entry : toMap.entrySet()) {
            EndpointInfo from = fromMap.get(entry.getKey());
            if (from == null) {
                changes.add(new StructuralChange(ChangeType.ENDPOINT, entry.getKey(), ChangeStatus.ADDED, "Handler: " + entry.getValue().controllerClass() + "#" + entry.getValue().methodName()));
            } else if (!Objects.equals(from.controllerClass(), entry.getValue().controllerClass())
                || !Objects.equals(from.methodName(), entry.getValue().methodName())) {
                changes.add(new StructuralChange(ChangeType.ENDPOINT, entry.getKey(), ChangeStatus.MODIFIED, "Handler changed: " + from.controllerClass() + "#" + from.methodName() + " -> " + entry.getValue().controllerClass() + "#" + entry.getValue().methodName()));
            }
        }

        for (Map.Entry<String, EndpointInfo> entry : fromMap.entrySet()) {
            if (!toMap.containsKey(entry.getKey())) {
                changes.add(new StructuralChange(ChangeType.ENDPOINT, entry.getKey(), ChangeStatus.REMOVED, "Handler was: " + entry.getValue().controllerClass() + "#" + entry.getValue().methodName()));
            }
        }
    }

    private void diffClassesAndMethods(List<ClassInfo> fromClasses, List<ClassInfo> toClasses, List<StructuralChange> changes) {
        Map<String, ClassInfo> fromMap = new HashMap<>();
        for (ClassInfo c : fromClasses) fromMap.put(c.name(), c);

        Map<String, ClassInfo> toMap = new HashMap<>();
        for (ClassInfo c : toClasses) toMap.put(c.name(), c);

        for (Map.Entry<String, ClassInfo> entry : toMap.entrySet()) {
            ClassInfo from = fromMap.get(entry.getKey());
            if (from == null) {
                changes.add(new StructuralChange(ChangeType.CLASS, entry.getKey(), ChangeStatus.ADDED, "Stereotype: " + entry.getValue().stereotype()));
                for (MethodInfo m : entry.getValue().methods()) {
                    changes.add(new StructuralChange(ChangeType.METHOD, entry.getKey() + "#" + m.name(), ChangeStatus.ADDED, m.signature()));
                }
            } else {
                diffMethods(entry.getKey(), from.methods(), entry.getValue().methods(), changes);
            }
        }

        for (Map.Entry<String, ClassInfo> entry : fromMap.entrySet()) {
            if (!toMap.containsKey(entry.getKey())) {
                changes.add(new StructuralChange(ChangeType.CLASS, entry.getKey(), ChangeStatus.REMOVED, "Was stereotype: " + entry.getValue().stereotype()));
                for (MethodInfo m : entry.getValue().methods()) {
                    changes.add(new StructuralChange(ChangeType.METHOD, entry.getKey() + "#" + m.name(), ChangeStatus.REMOVED, m.signature()));
                }
            }
        }
    }

    private void diffMethods(String className, List<MethodInfo> fromMethods, List<MethodInfo> toMethods, List<StructuralChange> changes) {
        Map<String, MethodInfo> fromMap = new HashMap<>();
        for (MethodInfo m : fromMethods) fromMap.put(m.name(), m);

        Map<String, MethodInfo> toMap = new HashMap<>();
        for (MethodInfo m : toMethods) toMap.put(m.name(), m);

        for (Map.Entry<String, MethodInfo> entry : toMap.entrySet()) {
            MethodInfo from = fromMap.get(entry.getKey());
            if (from == null) {
                changes.add(new StructuralChange(ChangeType.METHOD, className + "#" + entry.getKey(), ChangeStatus.ADDED, entry.getValue().signature()));
            } else {
                // Check calls
                List<String> fromCalls = from.outgoingCalls().stream().map(c -> c.targetClass() + "." + c.methodName()).toList();
                List<String> toCalls = entry.getValue().outgoingCalls().stream().map(c -> c.targetClass() + "." + c.methodName()).toList();
                if (!fromCalls.equals(toCalls)) {
                    changes.add(new StructuralChange(ChangeType.METHOD, className + "#" + entry.getKey(), ChangeStatus.MODIFIED, "Calls changed from " + fromCalls + " to " + toCalls));
                    // Added calls
                    for (String c : toCalls) {
                        if (!fromCalls.contains(c)) {
                            changes.add(new StructuralChange(ChangeType.CALL, className + "#" + entry.getKey() + " -> " + c, ChangeStatus.ADDED, "Call added"));
                        }
                    }
                    // Removed calls
                    for (String c : fromCalls) {
                        if (!toCalls.contains(c)) {
                            changes.add(new StructuralChange(ChangeType.CALL, className + "#" + entry.getKey() + " -> " + c, ChangeStatus.REMOVED, "Call removed"));
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, MethodInfo> entry : fromMap.entrySet()) {
            if (!toMap.containsKey(entry.getKey())) {
                changes.add(new StructuralChange(ChangeType.METHOD, className + "#" + entry.getKey(), ChangeStatus.REMOVED, entry.getValue().signature()));
            }
        }
    }

    private void diffEnums(List<EnumInfo> fromEnums, List<EnumInfo> toEnums, List<StructuralChange> changes) {
        Map<String, EnumInfo> fromMap = new HashMap<>();
        for (EnumInfo e : fromEnums) fromMap.put(e.name(), e);

        Map<String, EnumInfo> toMap = new HashMap<>();
        for (EnumInfo e : toEnums) toMap.put(e.name(), e);

        for (Map.Entry<String, EnumInfo> entry : toMap.entrySet()) {
            EnumInfo from = fromMap.get(entry.getKey());
            if (from == null) {
                for (String c : entry.getValue().constants()) {
                    changes.add(new StructuralChange(ChangeType.ENUM_CONSTANT, entry.getKey() + "." + c, ChangeStatus.ADDED, "Enum added"));
                }
            } else {
                Set<String> fromConsts = new HashSet<>(from.constants());
                Set<String> toConsts = new HashSet<>(entry.getValue().constants());
                for (String c : toConsts) {
                    if (!fromConsts.contains(c)) {
                        changes.add(new StructuralChange(ChangeType.ENUM_CONSTANT, entry.getKey() + "." + c, ChangeStatus.ADDED, "Constant added"));
                    }
                }
                for (String c : fromConsts) {
                    if (!toConsts.contains(c)) {
                        changes.add(new StructuralChange(ChangeType.ENUM_CONSTANT, entry.getKey() + "." + c, ChangeStatus.REMOVED, "Constant removed"));
                    }
                }
            }
        }

        for (Map.Entry<String, EnumInfo> entry : fromMap.entrySet()) {
            if (!toMap.containsKey(entry.getKey())) {
                for (String c : entry.getValue().constants()) {
                    changes.add(new StructuralChange(ChangeType.ENUM_CONSTANT, entry.getKey() + "." + c, ChangeStatus.REMOVED, "Enum removed"));
                }
            }
        }
    }

    private void diffStates(List<StateHint> fromStates, List<StateHint> toStates, List<StructuralChange> changes) {
        Map<String, StateHint> fromMap = new HashMap<>();
        for (StateHint s : fromStates) fromMap.put(s.targetClass() + "." + s.fieldName(), s);

        Map<String, StateHint> toMap = new HashMap<>();
        for (StateHint s : toStates) toMap.put(s.targetClass() + "." + s.fieldName(), s);

        for (Map.Entry<String, StateHint> entry : toMap.entrySet()) {
            StateHint from = fromMap.get(entry.getKey());
            if (from == null) {
                for (var t : entry.getValue().transitions()) {
                    String transStr = t.sourceState() + " -> " + t.targetState();
                    changes.add(new StructuralChange(ChangeType.STATE_TRANSITION, entry.getKey() + ": " + transStr, ChangeStatus.ADDED, "Transition added via " + t.triggeringMethod()));
                }
            } else {
                Set<String> fromTrans = new HashSet<>(from.transitions().stream().map(t -> t.sourceState() + " -> " + t.targetState()).toList());
                Set<String> toTrans = new HashSet<>(entry.getValue().transitions().stream().map(t -> t.sourceState() + " -> " + t.targetState()).toList());
                for (String t : toTrans) {
                    if (!fromTrans.contains(t)) {
                        changes.add(new StructuralChange(ChangeType.STATE_TRANSITION, entry.getKey() + ": " + t, ChangeStatus.ADDED, "Transition added"));
                    }
                }
                for (String t : fromTrans) {
                    if (!toTrans.contains(t)) {
                        changes.add(new StructuralChange(ChangeType.STATE_TRANSITION, entry.getKey() + ": " + t, ChangeStatus.REMOVED, "Transition removed"));
                    }
                }
            }
        }

        for (Map.Entry<String, StateHint> entry : fromMap.entrySet()) {
            if (!toMap.containsKey(entry.getKey())) {
                for (var t : entry.getValue().transitions()) {
                    String transStr = t.sourceState() + " -> " + t.targetState();
                    changes.add(new StructuralChange(ChangeType.STATE_TRANSITION, entry.getKey() + ": " + transStr, ChangeStatus.REMOVED, "Transition removed"));
                }
            }
        }
    }

    private boolean evaluateEntryPoint(ServiceModel fromModel, ServiceModel toModel, String entryPoint) {
        if (entryPoint == null || entryPoint.isBlank()) {
            return false;
        }

        String handler = resolveHandler(entryPoint, toModel);
        if (handler == null) {
            handler = resolveHandler(entryPoint, fromModel);
        }
        if (handler == null) {
            return false;
        }

        MethodInfo fromMethod = findMethodByHandler(fromModel, handler);
        MethodInfo toMethod = findMethodByHandler(toModel, handler);

        if (fromMethod == null && toMethod != null) return false; // added
        if (fromMethod != null && toMethod == null) return false; // removed
        if (fromMethod == null && toMethod == null) return true; // neither has it

        // Both have it, compare calls
        List<String> fromCalls = fromMethod.outgoingCalls().stream().map(c -> c.targetClass() + "." + c.methodName()).toList();
        List<String> toCalls = toMethod.outgoingCalls().stream().map(c -> c.targetClass() + "." + c.methodName()).toList();

        return fromCalls.equals(toCalls) && fromMethod.controlFlows().equals(toMethod.controlFlows());
    }

    private String resolveHandler(String entryPoint, ServiceModel model) {
        String ep = entryPoint.trim();
        if (ep.contains("#")) {
            return ep;
        }
        for (EndpointInfo info : model.endpoints()) {
            String route = info.httpMethod() + " " + info.path();
            if (route.equalsIgnoreCase(ep) || info.path().equalsIgnoreCase(ep)) {
                return info.controllerClass() + "#" + info.methodName();
            }
        }
        return null;
    }

    private MethodInfo findMethodByHandler(ServiceModel model, String handler) {
        int idx = handler.indexOf('#');
        if (idx <= 0) return null;
        String className = handler.substring(0, idx);
        String methodName = handler.substring(idx + 1);

        for (ClassInfo c : model.classes()) {
            if (c.name().equalsIgnoreCase(className) || (c.packageName() + "." + c.name()).equalsIgnoreCase(className)) {
                for (MethodInfo m : c.methods()) {
                    if (m.name().equalsIgnoreCase(methodName)) {
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private String generateSummary(List<StructuralChange> changes) {
        long added = changes.stream().filter(c -> c.status() == ChangeStatus.ADDED).count();
        long removed = changes.stream().filter(c -> c.status() == ChangeStatus.REMOVED).count();
        long modified = changes.stream().filter(c -> c.status() == ChangeStatus.MODIFIED).count();

        if (changes.isEmpty()) {
            return "No structural differences detected between the revisions.";
        }
        return String.format("Detected %d changes: %d added, %d removed, %d modified.",
            changes.size(), added, removed, modified);
    }
}
