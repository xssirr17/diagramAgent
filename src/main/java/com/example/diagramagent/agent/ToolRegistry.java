package com.example.diagramagent.agent;

import com.example.diagramagent.scan.ClassInfo;
import com.example.diagramagent.scan.MethodInfo;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.security.PathGuard;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final PathGuard pathGuard;
    private final ThreadLocal<ServiceModel> activeModel = new ThreadLocal<>();
    private final ThreadLocal<Path> activePath = new ThreadLocal<>();

    public ToolRegistry(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    public void setActiveContext(Path path, ServiceModel model) {
        this.activePath.set(path);
        this.activeModel.set(model);
    }

    public void clearActiveContext() {
        this.activePath.remove();
        this.activeModel.remove();
    }

    @Tool(description = "List all detected Java classes and interfaces along with their Spring stereotypes")
    public String listClasses() {
        ServiceModel model = activeModel.get();
        if (model == null || model.classes().isEmpty()) {
            return "No classes available in the active service model.";
        }

        return model.classes().stream()
            .map(c -> c.name() + " (" + c.stereotype() + ")")
            .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Get structural summary for a class including its package, dependencies, and method signatures")
    public String getClassSummary(String className) {
        ServiceModel model = activeModel.get();
        if (model == null) return "No active service model.";

        Optional<ClassInfo> opt = model.findClass(className);
        if (opt.isEmpty()) {
            return "Class not found: " + className;
        }

        ClassInfo cls = opt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("Class: ").append(cls.name()).append("\n");
        sb.append("Package: ").append(cls.packageName()).append("\n");
        sb.append("Stereotype: ").append(cls.stereotype()).append("\n");

        sb.append("Dependencies:\n");
        for (var dep : cls.dependencies()) {
            sb.append("  - ").append(dep.name()).append(": ").append(dep.type()).append("\n");
        }

        sb.append("Methods:\n");
        for (var m : cls.methods()) {
            sb.append("  - ").append(m.signature()).append(" -> returns ").append(m.returnType()).append("\n");
        }

        return sb.toString();
    }

    @Tool(description = "Read the source code of a specific method in a class, truncated to maximum length")
    public String readMethodSource(String className, String methodName) {
        ServiceModel model = activeModel.get();
        if (model == null) return "No active service model.";

        Optional<MethodInfo> opt = model.findMethod(className, methodName);
        if (opt.isEmpty()) {
            return "Method not found: " + className + "#" + methodName;
        }

        String source = opt.get().methodSource();
        int maxLen = 2000;
        if (source != null && source.length() > maxLen) {
            return source.substring(0, maxLen) + "\n... [TRUNCATED]";
        }
        return source != null ? source : "(No source body)";
    }

    @Tool(description = "Find all methods in the service that call a specific method of a given class")
    public String findCallers(String className, String methodName) {
        ServiceModel model = activeModel.get();
        if (model == null) return "No active service model.";

        List<String> callers = new ArrayList<>();
        for (ClassInfo cls : model.classes()) {
            for (MethodInfo m : cls.methods()) {
                boolean calls = m.outgoingCalls().stream().anyMatch(c ->
                    c.targetClass().equalsIgnoreCase(className) &&
                    c.methodName().equalsIgnoreCase(methodName)
                );
                if (calls) {
                    callers.add(cls.name() + "#" + m.name());
                }
            }
        }

        if (callers.isEmpty()) {
            return "No callers found for " + className + "#" + methodName;
        }
        return "Callers of " + className + "#" + methodName + ":\n" + String.join("\n", callers);
    }
}
