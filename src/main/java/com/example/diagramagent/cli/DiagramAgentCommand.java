package com.example.diagramagent.cli;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.cache.ServiceModelCache;
import com.example.diagramagent.diff.GitService;
import com.example.diagramagent.diff.ModelDiffer;
import com.example.diagramagent.diff.StructuralDiff;
import com.example.diagramagent.render.DiagramFormat;
import com.example.diagramagent.render.DiagramRenderer;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.security.PathGuard;
import com.example.diagramagent.validate.MermaidValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "diagram-agent",
    description = "Diagram Agent CLI - Generate and validate Mermaid architecture diagrams from Java source code",
    mixinStandardHelpOptions = true,
    version = "diagram-agent 1.0.0"
)
public class DiagramAgentCommand implements Callable<Integer> {

    @Option(names = "--validate-only", description = "Validate an existing Mermaid (.mmd) diagram file")
    private Path validateOnlyFile;

    private final PathGuard pathGuard;
    private final ServiceModelCache serviceModelCache;
    private final DiagramAgent diagramAgent;
    private final DiagramRenderer diagramRenderer;
    private final GitService gitService;
    private final ModelDiffer modelDiffer;
    private final MermaidValidator mermaidValidator;

    public DiagramAgentCommand(
        PathGuard pathGuard,
        ServiceModelCache serviceModelCache,
        DiagramAgent diagramAgent,
        DiagramRenderer diagramRenderer,
        GitService gitService,
        ModelDiffer modelDiffer,
        MermaidValidator mermaidValidator
    ) {
        this.pathGuard = pathGuard;
        this.serviceModelCache = serviceModelCache;
        this.diagramAgent = diagramAgent;
        this.diagramRenderer = diagramRenderer;
        this.gitService = gitService;
        this.modelDiffer = modelDiffer;
        this.mermaidValidator = mermaidValidator;
    }

    @Override
    public Integer call() throws Exception {
        if (validateOnlyFile != null) {
            return validateFile(validateOnlyFile, null);
        }
        CommandLine.usage(this, System.err);
        return 2;
    }

    public int validateFile(Path file, DiagramType type) throws IOException {
        if (!Files.exists(file)) {
            System.err.println("File does not exist: " + file);
            return 2;
        }
        String content = Files.readString(file);
        DiagramType expectedType = type != null ? type : detectDiagramType(content);
        var result = mermaidValidator.validate(content, expectedType);
        if (result.valid()) {
            System.err.println("Validation SUCCESS: Diagram is valid.");
            return 0;
        } else {
            System.err.println("Validation FAILED: " + result.errorMessage());
            return 5;
        }
    }

    private DiagramType detectDiagramType(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("sequenceDiagram")) return DiagramType.SEQUENCE;
        if (trimmed.startsWith("stateDiagram")) return DiagramType.STATE;
        return DiagramType.FLOWCHART;
    }

    @Command(name = "generate", description = "Generate a Mermaid diagram for a service or endpoint")
    public static class GenerateCommand implements Callable<Integer> {
        @Option(names = "--path", required = true, description = "Path to Java service")
        String path;

        @Option(names = "--type", required = true, description = "Diagram type: SEQUENCE, FLOWCHART, STATE")
        DiagramType type;

        @Option(names = "--entry", description = "Entry point (controller#method or HTTP route)")
        String entryPoint;

        @Option(names = "--depth", defaultValue = "4", description = "Max call depth")
        int depth;

        @Option(names = "--format", defaultValue = "MERMAID", description = "Output format: MERMAID, SVG, PNG")
        DiagramFormat format;

        @Option(names = "--out", description = "Output file path (default: stdout)")
        Path outFile;

        private final DiagramAgentCommand parent;

        public GenerateCommand(DiagramAgentCommand parent) {
            this.parent = parent;
        }

        @Override
        public Integer call() throws Exception {
            Path resolved = parent.pathGuard.validateAndResolve(path);
            ServiceModel model = parent.serviceModelCache.getOrScan(resolved);
            var result = parent.diagramAgent.generateDiagram(resolved, model, type, entryPoint, depth);

            if (!result.valid()) {
                System.err.println("Warning: Diagram validation reported issues: " + result.warnings());
            }

            parent.outputResult(result.mermaid(), format, outFile);
            return result.valid() ? 0 : 5;
        }
    }

    @Command(name = "endpoints", description = "List detected Spring HTTP endpoints in a project")
    public static class EndpointsCommand implements Callable<Integer> {
        @Option(names = "--path", required = true, description = "Path to Java service")
        String path;

        @Option(names = "--out", description = "Output file path (default: stdout)")
        Path outFile;

        private final DiagramAgentCommand parent;

        public EndpointsCommand(DiagramAgentCommand parent) {
            this.parent = parent;
        }

        @Override
        public Integer call() throws Exception {
            Path resolved = parent.pathGuard.validateAndResolve(path);
            ServiceModel model = parent.serviceModelCache.getOrScan(resolved);

            StringBuilder sb = new StringBuilder();
            for (var ep : model.endpoints()) {
                sb.append(String.format("%-7s %-35s -> %s#%s%n",
                    ep.httpMethod(), ep.path(), ep.controllerClass(), ep.methodName()));
            }

            if (outFile != null) {
                Files.writeString(outFile, sb.toString());
                System.err.println("Wrote " + model.endpoints().size() + " endpoints to " + outFile);
            } else {
                System.out.print(sb);
            }
            return 0;
        }
    }

    @Command(name = "diff", description = "Generate a Git diff diagram between two revisions")
    public static class DiffCommand implements Callable<Integer> {
        @Option(names = "--path", required = true, description = "Path to Git project")
        String path;

        @Option(names = "--from", defaultValue = "HEAD~1", description = "Base Git revision")
        String fromRef;

        @Option(names = "--to", defaultValue = "HEAD", description = "Target Git revision")
        String toRef;

        @Option(names = "--type", required = true, description = "Diagram type: SEQUENCE, FLOWCHART, STATE")
        DiagramType type;

        @Option(names = "--entry", description = "Entry point")
        String entryPoint;

        @Option(names = "--depth", defaultValue = "4", description = "Max call depth")
        int depth;

        @Option(names = "--format", defaultValue = "MERMAID", description = "Output format: MERMAID, SVG, PNG")
        DiagramFormat format;

        @Option(names = "--out", description = "Output file path (default: stdout)")
        Path outFile;

        private final DiagramAgentCommand parent;

        public DiffCommand(DiagramAgentCommand parent) {
            this.parent = parent;
        }

        @Override
        public Integer call() throws Exception {
            Path resolved = parent.pathGuard.validateAndResolve(path);
            ServiceModel fromModel = parent.gitService.extractModelAtRef(resolved, fromRef);
            ServiceModel toModel = parent.gitService.extractModelAtRef(resolved, toRef);

            StructuralDiff diff = parent.modelDiffer.diff(fromModel, toModel, type, entryPoint);
            System.err.println("Diff: " + diff.summary());

            var result = parent.diagramAgent.generateDiffDiagram(resolved, fromModel, toModel, diff, type, entryPoint, depth);

            parent.outputResult(result.mermaid(), format, outFile);
            return result.valid() ? 0 : 5;
        }
    }

    @Command(name = "validate", description = "Validate an existing Mermaid diagram file")
    public static class ValidateCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "Path to Mermaid file")
        Path paramFile;

        @Option(names = {"--file", "-f"}, description = "Path to Mermaid file")
        Path optFile;

        @Option(names = "--type", description = "Diagram type")
        DiagramType type;

        private final DiagramAgentCommand parent;

        public ValidateCommand(DiagramAgentCommand parent) {
            this.parent = parent;
        }

        @Override
        public Integer call() throws Exception {
            Path target = paramFile != null ? paramFile : optFile;
            if (target == null) {
                System.err.println("Please specify a diagram file to validate.");
                return 2;
            }
            return parent.validateFile(target, type);
        }
    }

    private void outputResult(String mermaid, DiagramFormat format, Path outFile) throws Exception {
        if (format == DiagramFormat.MERMAID) {
            if (outFile != null) {
                Files.writeString(outFile, mermaid);
                System.err.println("Wrote diagram to " + outFile);
            } else {
                System.out.println(mermaid);
            }
        } else {
            byte[] bytes = diagramRenderer.render(mermaid, format);
            if (outFile != null) {
                Files.write(outFile, bytes);
                System.err.println("Wrote " + format + " image to " + outFile);
            } else {
                System.out.write(bytes);
                System.out.flush();
            }
        }
    }
}
