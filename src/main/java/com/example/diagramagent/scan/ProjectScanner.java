package com.example.diagramagent.scan;

import com.example.diagramagent.api.NoJavaSourcesException;
import com.example.diagramagent.config.DiagramProperties;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private static final Set<String> EXCLUDED_DIRS = Set.of(
        "build", "target", "out", ".gradle", "test", ".git", ".idea", ".svn"
    );

    private final DiagramProperties properties;
    private final EndpointExtractor endpointExtractor;
    private final CallChainExtractor callChainExtractor;
    private final StateExtractor stateExtractor;

    public ProjectScanner(
        DiagramProperties properties,
        EndpointExtractor endpointExtractor,
        CallChainExtractor callChainExtractor,
        StateExtractor stateExtractor
    ) {
        this.properties = properties;
        this.endpointExtractor = endpointExtractor;
        this.callChainExtractor = callChainExtractor;
        this.stateExtractor = stateExtractor;
    }

    public ServiceModel scan(Path projectRoot) {
        List<String> warnings = new ArrayList<>();
        List<Path> javaFiles = findJavaFiles(projectRoot, warnings);

        if (javaFiles.isEmpty()) {
            throw new NoJavaSourcesException("No Java sources found under src/main/java in: " + projectRoot);
        }

        ParserConfiguration parserConfig = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        JavaParser javaParser = new JavaParser(parserConfig);

        List<CompilationUnit> cus = new ArrayList<>();
        Map<CompilationUnit, Path> cuToPath = new HashMap<>();

        for (Path file : javaFiles) {
            try {
                long size = Files.size(file);
                if (size > properties.maxFileSizeBytes()) {
                    warnings.add("File skipped due to size (" + size + " bytes > " + properties.maxFileSizeBytes() + "): " + file.getFileName());
                    continue;
                }

                ParseResult<CompilationUnit> result = javaParser.parse(file);
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    CompilationUnit cu = result.getResult().get();
                    cus.add(cu);
                    cuToPath.put(cu, file);
                } else {
                    String msg = "Parse failure in " + file.getFileName() + ": " + result.getProblems();
                    log.warn(msg);
                    warnings.add(msg);
                }
            } catch (Exception e) {
                String msg = "Error reading " + file.getFileName() + ": " + e.getMessage();
                log.warn(msg, e);
                warnings.add(msg);
            }
        }

        // Pass 1: Collect class declarations and enums
        List<ClassOrInterfaceDeclaration> classDecls = new ArrayList<>();
        Map<ClassOrInterfaceDeclaration, Path> classToPath = new HashMap<>();
        List<EnumDeclaration> enumDecls = new ArrayList<>();
        Map<String, String> knownClasses = new HashMap<>(); // simpleName -> qualifiedName

        for (CompilationUnit cu : cus) {
            String pkg = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");

            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                classDecls.add(cls);
                classToPath.put(cls, cuToPath.get(cu));
                String simpleName = cls.getNameAsString();
                String qualifiedName = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
                knownClasses.put(simpleName, qualifiedName);
            }

            for (EnumDeclaration en : cu.findAll(EnumDeclaration.class)) {
                enumDecls.add(en);
            }
        }

        // Pass 2: Extract classes, endpoints, enums, state hints
        List<ClassInfo> classes = new ArrayList<>();
        List<EndpointInfo> allEndpoints = new ArrayList<>();

        for (ClassOrInterfaceDeclaration cls : classDecls) {
            String pkg = cls.findCompilationUnit()
                .flatMap(cu -> cu.getPackageDeclaration())
                .map(p -> p.getNameAsString())
                .orElse("");

            Stereotype stereotype = extractStereotype(cls);
            List<String> annotations = cls.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .toList();

            List<DependencyInfo> dependencies = callChainExtractor.extractDependencies(cls);
            List<MethodInfo> methods = callChainExtractor.extractMethods(cls, dependencies, knownClasses);

            Path sourcePath = classToPath.get(cls);
            String filePathStr = sourcePath != null ? sourcePath.toString() : "";

            classes.add(new ClassInfo(
                cls.getNameAsString(),
                pkg,
                stereotype,
                annotations,
                dependencies,
                methods,
                filePathStr
            ));

            allEndpoints.addAll(endpointExtractor.extractEndpoints(cls));
        }

        List<EnumInfo> enums = stateExtractor.extractEnums(enumDecls, "");
        List<StateHint> stateHints = stateExtractor.extractStateHints(classDecls, enums);

        log.info("Scan completed for {}: filesScanned={}, classesInModel={}, enumsInModel={}, endpointsInModel={}",
            projectRoot, javaFiles.size(), classes.size(), enums.size(), allEndpoints.size());

        return new ServiceModel(classes, allEndpoints, enums, stateHints, warnings);
    }

    private Stereotype extractStereotype(ClassOrInterfaceDeclaration cls) {
        for (var a : cls.getAnnotations()) {
            Stereotype st = Stereotype.fromAnnotation(a.getNameAsString());
            if (st != Stereotype.UNKNOWN) {
                return st;
            }
        }
        return Stereotype.UNKNOWN;
    }

    private List<Path> findJavaFiles(Path root, List<String> warnings) {
        List<Path> allJavaFiles = new ArrayList<>();
        int maxFiles = properties.maxFilesScanned();
        boolean hasSrcMainJava = Files.exists(root.resolve("src/main/java")) ||
            root.toString().replace('\\', '/').contains("/src/main/java");

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (EXCLUDED_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        String normalized = file.toString().replace('\\', '/');
                        if (!hasSrcMainJava || normalized.contains("/src/main/java/") || normalized.contains("src/main/java")) {
                            allJavaFiles.add(file);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Error walking directory {}: {}", root, e.getMessage());
            warnings.add("Error walking directory: " + e.getMessage());
        }

        int totalFound = allJavaFiles.size();
        log.info("File search for {}: totalJavaFilesFound={}, maxFilesAllowed={}", root, totalFound, maxFiles);

        if (totalFound <= maxFiles) {
            return allJavaFiles;
        }

        warnings.add("Max files limit (" + maxFiles + ") reached; prioritized and scanned " + maxFiles + " of " + totalFound + " files.");

        // Prioritize: Controllers first, then Services, then Repositories/Clients, then others
        allJavaFiles.sort((p1, p2) -> {
            int score1 = priorityScore(p1.getFileName().toString());
            int score2 = priorityScore(p2.getFileName().toString());
            return Integer.compare(score1, score2);
        });

        return allJavaFiles.subList(0, maxFiles);
    }

    private static int priorityScore(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("controller.java") || lower.contains("endpoint")) return 1;
        if (lower.endsWith("service.java") || lower.endsWith("serviceimpl.java")) return 2;
        if (lower.endsWith("repository.java") || lower.endsWith("dao.java") || lower.endsWith("client.java")) return 3;
        if (lower.endsWith("config.java") || lower.endsWith("configuration.java")) return 4;
        return 5;
    }
}
