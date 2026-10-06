package com.example.diagramagent.scan;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CallChainExtractor {

    public List<DependencyInfo> extractDependencies(ClassOrInterfaceDeclaration classDecl) {
        List<DependencyInfo> deps = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        // Constructor parameters (Spring constructor injection)
        for (ConstructorDeclaration constructor : classDecl.getConstructors()) {
            for (Parameter param : constructor.getParameters()) {
                String name = param.getNameAsString();
                String type = param.getTypeAsString();
                if (seenNames.add(name)) {
                    deps.add(new DependencyInfo(name, type));
                }
            }
        }

        // Fields (e.g. @Autowired or final fields)
        for (FieldDeclaration field : classDecl.getFields()) {
            boolean isAutowired = field.getAnnotations().stream().anyMatch(a ->
                a.getNameAsString().equals("Autowired") || a.getNameAsString().endsWith(".Autowired") ||
                a.getNameAsString().equals("Inject") || a.getNameAsString().endsWith(".Inject")
            );
            boolean isFinal = field.isFinal();
            if (isAutowired || isFinal) {
                String type = field.getElementType().asString();
                field.getVariables().forEach(var -> {
                    String name = var.getNameAsString();
                    if (seenNames.add(name)) {
                        deps.add(new DependencyInfo(name, type));
                    }
                });
            }
        }

        return deps;
    }

    public List<MethodInfo> extractMethods(
        ClassOrInterfaceDeclaration classDecl,
        List<DependencyInfo> dependencies,
        Map<String, String> knownClassSimpleToQualified
    ) {
        List<MethodInfo> methods = new ArrayList<>();
        Map<String, String> depMap = dependencies.stream()
            .collect(Collectors.toMap(DependencyInfo::name, DependencyInfo::type, (a, b) -> a));

        for (MethodDeclaration methodDecl : classDecl.getMethods()) {
            String methodName = methodDecl.getNameAsString();
            String returnType = methodDecl.getTypeAsString();
            String signature = methodDecl.getDeclarationAsString(false, false, false);
            boolean isPrivate = methodDecl.isPrivate();
            String methodSource = methodDecl.toString();

            List<String> annotations = methodDecl.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .toList();

            List<CallInfo> outgoingCalls = new ArrayList<>();
            List<ControlFlowInfo> controlFlows = new ArrayList<>();

            methodDecl.getBody().ifPresent(body -> {
                extractBodyCallsAndControlFlow(
                    body,
                    classDecl.getNameAsString(),
                    depMap,
                    knownClassSimpleToQualified,
                    outgoingCalls,
                    controlFlows
                );
            });

            methods.add(new MethodInfo(
                methodName,
                signature,
                returnType,
                annotations,
                outgoingCalls,
                controlFlows,
                methodSource,
                isPrivate
            ));
        }

        return methods;
    }

    private void extractBodyCallsAndControlFlow(
        BlockStmt body,
        String currentClass,
        Map<String, String> depMap,
        Map<String, String> knownClasses,
        List<CallInfo> outgoingCalls,
        List<ControlFlowInfo> controlFlows
    ) {
        for (Statement stmt : body.getStatements()) {
            inspectStatement(stmt, currentClass, depMap, knownClasses, outgoingCalls, controlFlows, null);
        }
    }

    private void inspectStatement(
        Statement stmt,
        String currentClass,
        Map<String, String> depMap,
        Map<String, String> knownClasses,
        List<CallInfo> outgoingCalls,
        List<ControlFlowInfo> controlFlows,
        String currentCondition
    ) {
        if (stmt instanceof BlockStmt blockStmt) {
            for (Statement sub : blockStmt.getStatements()) {
                inspectStatement(sub, currentClass, depMap, knownClasses, outgoingCalls, controlFlows, currentCondition);
            }
            return;
        }

        if (stmt instanceof IfStmt ifStmt) {
            String condText = ifStmt.getCondition().toString();
            List<CallInfo> thenCalls = new ArrayList<>();
            inspectStatement(ifStmt.getThenStmt(), currentClass, depMap, knownClasses, outgoingCalls, controlFlows, condText);
            inspectNodeForCalls(ifStmt.getThenStmt(), currentClass, depMap, knownClasses, thenCalls, condText);
            controlFlows.add(new ControlFlowInfo(ControlFlowKind.IF, condText, thenCalls));

            ifStmt.getElseStmt().ifPresent(elseStmt -> {
                String elseCond = "else of (" + condText + ")";
                List<CallInfo> elseCalls = new ArrayList<>();
                inspectStatement(elseStmt, currentClass, depMap, knownClasses, outgoingCalls, controlFlows, elseCond);
                inspectNodeForCalls(elseStmt, currentClass, depMap, knownClasses, elseCalls, elseCond);
                controlFlows.add(new ControlFlowInfo(ControlFlowKind.ELSE, elseCond, elseCalls));
            });
        } else if (stmt instanceof SwitchStmt switchStmt) {
            String selector = switchStmt.getSelector().toString();
            for (SwitchEntry entry : switchStmt.getEntries()) {
                String label = entry.getLabels().stream().map(Node::toString).collect(Collectors.joining(", "));
                String caseDesc = selector + " == " + (label.isBlank() ? "default" : label);
                List<CallInfo> caseCalls = new ArrayList<>();
                for (Statement subStmt : entry.getStatements()) {
                    inspectStatement(subStmt, currentClass, depMap, knownClasses, outgoingCalls, controlFlows, caseDesc);
                    inspectNodeForCalls(subStmt, currentClass, depMap, knownClasses, caseCalls, caseDesc);
                }
                controlFlows.add(new ControlFlowInfo(ControlFlowKind.SWITCH_CASE, caseDesc, caseCalls));
            }
        } else if (stmt instanceof TryStmt tryStmt) {
            List<CallInfo> tryCalls = new ArrayList<>();
            inspectStatement(tryStmt.getTryBlock(), currentClass, depMap, knownClasses, outgoingCalls, controlFlows, currentCondition);
            inspectNodeForCalls(tryStmt.getTryBlock(), currentClass, depMap, knownClasses, tryCalls, currentCondition);
            controlFlows.add(new ControlFlowInfo(ControlFlowKind.TRY, "try", tryCalls));

            for (CatchClause catchClause : tryStmt.getCatchClauses()) {
                String param = catchClause.getParameter().toString();
                List<CallInfo> catchCalls = new ArrayList<>();
                inspectStatement(catchClause.getBody(), currentClass, depMap, knownClasses, outgoingCalls, controlFlows, "catch (" + param + ")");
                inspectNodeForCalls(catchClause.getBody(), currentClass, depMap, knownClasses, catchCalls, "catch (" + param + ")");
                controlFlows.add(new ControlFlowInfo(ControlFlowKind.CATCH, param, catchCalls));
            }
        } else if (stmt instanceof ForStmt || stmt instanceof ForEachStmt || stmt instanceof WhileStmt || stmt instanceof DoStmt) {
            List<CallInfo> loopCalls = new ArrayList<>();
            String desc = stmt.getClass().getSimpleName();
            inspectNodeForCalls(stmt, currentClass, depMap, knownClasses, loopCalls, currentCondition);
            controlFlows.add(new ControlFlowInfo(ControlFlowKind.LOOP, desc, loopCalls));
            // Also recursively inspect statements inside loop body
            stmt.findAll(Statement.class).forEach(s -> {
                if (s != stmt && !(s instanceof BlockStmt)) {
                    inspectStatement(s, currentClass, depMap, knownClasses, outgoingCalls, controlFlows, currentCondition);
                }
            });
        } else if (stmt instanceof ReturnStmt ret) {
            controlFlows.add(new ControlFlowInfo(
                ControlFlowKind.RETURN,
                ret.getExpression().map(Node::toString).orElse("void"),
                List.of()
            ));
            inspectNodeForCalls(stmt, currentClass, depMap, knownClasses, outgoingCalls, currentCondition);
        } else if (stmt instanceof ThrowStmt thr) {
            controlFlows.add(new ControlFlowInfo(
                ControlFlowKind.THROW,
                thr.getExpression().toString(),
                List.of()
            ));
            inspectNodeForCalls(stmt, currentClass, depMap, knownClasses, outgoingCalls, currentCondition);
        } else {
            // General statement: expressions, assignments, method calls
            inspectNodeForCalls(stmt, currentClass, depMap, knownClasses, outgoingCalls, currentCondition);
        }
    }

    private void inspectNodeForCalls(
        Node node,
        String currentClass,
        Map<String, String> depMap,
        Map<String, String> knownClasses,
        List<CallInfo> callsAccumulator,
        String condition
    ) {
        List<MethodCallExpr> methodCalls = node.findAll(MethodCallExpr.class);
        for (MethodCallExpr call : methodCalls) {
            CallInfo callInfo = resolveCall(call, currentClass, depMap, knownClasses, condition);
            // Avoid duplicates in the same accumulator
            if (!callsAccumulator.contains(callInfo)) {
                callsAccumulator.add(callInfo);
            }
        }
    }

    private CallInfo resolveCall(
        MethodCallExpr call,
        String currentClass,
        Map<String, String> depMap,
        Map<String, String> knownClasses,
        String condition
    ) {
        String methodName = call.getNameAsString();
        String arguments = call.getArguments().stream().map(Node::toString).collect(Collectors.joining(", "));
        Optional<Expression> scopeOpt = call.getScope();

        if (scopeOpt.isEmpty() || scopeOpt.get().toString().equals("this")) {
            return new CallInfo(currentClass, methodName, arguments, CallKind.INTERNAL, condition);
        }

        String scopeStr = scopeOpt.get().toString();
        // E.g. scopeStr could be "orderRepository" or "this.orderRepository"
        if (scopeStr.startsWith("this.")) {
            scopeStr = scopeStr.substring(5);
        }

        // If scopeStr is a dependency of the class
        if (depMap.containsKey(scopeStr)) {
            String depType = depMap.get(scopeStr);
            if (knownClasses.containsKey(depType)) {
                return new CallInfo(depType, methodName, arguments, CallKind.INTERNAL, condition);
            }
            // Dependency is external or not in known classes
            CallKind kind = classifyExternal(depType, scopeStr, methodName);
            return new CallInfo(depType, methodName, arguments, kind, condition);
        }

        // If scopeStr is directly the class name of a known class (static call)
        if (knownClasses.containsKey(scopeStr)) {
            return new CallInfo(scopeStr, methodName, arguments, CallKind.INTERNAL, condition);
        }

        // Fallback: classify based on variable/method name
        CallKind kind = classifyExternal(scopeStr, scopeStr, methodName);
        return new CallInfo(scopeStr, methodName, arguments, kind, condition);
    }

    private CallKind classifyExternal(String typeName, String varName, String methodName) {
        String combined = (typeName + " " + varName + " " + methodName).toLowerCase(Locale.ROOT);

        if (combined.contains("resttemplate") || combined.contains("webclient") ||
            combined.contains("feign") || combined.contains("http") || combined.contains("client")) {
            return CallKind.HTTP;
        }

        if (combined.contains("kafka") || combined.contains("rabbit") ||
            combined.contains("jms") || combined.contains("message") || combined.contains("producer") ||
            combined.contains("streambridge")) {
            return CallKind.MESSAGING;
        }

        if (combined.contains("repository") || combined.contains("repo") ||
            combined.contains("entitymanager") || combined.contains("jdbc") ||
            combined.contains("dao") || combined.contains("db")) {
            return CallKind.DB;
        }

        return CallKind.UNKNOWN;
    }

    public List<CallInfo> traceCallChain(
        String startClass,
        String startMethod,
        ServiceModel model,
        int maxDepth
    ) {
        List<CallInfo> fullTrace = new ArrayList<>();
        Set<String> callStack = new HashSet<>();
        traceRecursive(startClass, startMethod, model, 0, maxDepth, callStack, fullTrace);
        return fullTrace;
    }

    private void traceRecursive(
        String className,
        String methodName,
        ServiceModel model,
        int currentDepth,
        int maxDepth,
        Set<String> callStack,
        List<CallInfo> fullTrace
    ) {
        if (currentDepth >= maxDepth) return;

        String methodKey = className + "#" + methodName;
        if (callStack.contains(methodKey)) {
            // Cycle detected, prevent infinite loop
            return;
        }

        callStack.add(methodKey);

        Optional<MethodInfo> methodOpt = model.findMethod(className, methodName);
        if (methodOpt.isPresent()) {
            MethodInfo m = methodOpt.get();
            for (CallInfo call : m.outgoingCalls()) {
                fullTrace.add(call);
                if (call.kind() == CallKind.INTERNAL && model.findClass(call.targetClass()).isPresent()) {
                    traceRecursive(
                        call.targetClass(),
                        call.methodName(),
                        model,
                        currentDepth + 1,
                        maxDepth,
                        callStack,
                        fullTrace
                    );
                }
            }
        }

        callStack.remove(methodKey);
    }
}
