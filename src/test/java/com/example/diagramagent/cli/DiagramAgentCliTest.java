package com.example.diagramagent.cli;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.api.InsufficientInformationException;
import com.example.diagramagent.api.InvalidPathException;
import com.example.diagramagent.api.ProviderException;
import com.example.diagramagent.cache.ServiceModelCache;
import com.example.diagramagent.diff.GitService;
import com.example.diagramagent.diff.ModelDiffer;
import com.example.diagramagent.render.DiagramRenderer;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.EndpointInfo;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.security.PathGuard;
import com.example.diagramagent.validate.MermaidValidator;
import com.example.diagramagent.validate.ValidationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagramAgentCliTest {

    private PathGuard pathGuard;
    private ServiceModelCache serviceModelCache;
    private DiagramAgent diagramAgent;
    private DiagramRenderer diagramRenderer;
    private GitService gitService;
    private ModelDiffer modelDiffer;
    private MermaidValidator mermaidValidator;
    private DiagramAgentCli cli;

    @BeforeEach
    void setUp() {
        pathGuard = mock(PathGuard.class);
        serviceModelCache = mock(ServiceModelCache.class);
        diagramAgent = mock(DiagramAgent.class);
        diagramRenderer = mock(DiagramRenderer.class);
        gitService = mock(GitService.class);
        modelDiffer = mock(ModelDiffer.class);
        mermaidValidator = mock(MermaidValidator.class);

        cli = new DiagramAgentCli(
            pathGuard,
            serviceModelCache,
            diagramAgent,
            diagramRenderer,
            gitService,
            modelDiffer,
            mermaidValidator
        );
    }

    @Test
    void exitCode2OnMissingRequiredArguments() {
        int code = cli.runWithArgs("generate");
        assertEquals(2, code, "Should return 2 for missing required --path and --type");
    }

    @Test
    void exitCode2OnInvalidPath() {
        when(pathGuard.validateAndResolve("../outside"))
            .thenThrow(new InvalidPathException("Path escapes root"));

        int code = cli.runWithArgs("generate", "--path", "../outside", "--type", "SEQUENCE");
        assertEquals(2, code);
    }

    @Test
    void exitCode3OnInsufficientInformation(@TempDir Path tempDir) {
        when(pathGuard.validateAndResolve("my-service")).thenReturn(tempDir);
        ServiceModel model = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(serviceModelCache.getOrScan(tempDir)).thenReturn(model);
        when(diagramAgent.generateDiagram(any(), any(), eq(DiagramType.STATE), any(), eq(4)))
            .thenThrow(new InsufficientInformationException("No states found"));

        int code = cli.runWithArgs("generate", "--path", "my-service", "--type", "STATE");
        assertEquals(3, code);
    }

    @Test
    void exitCode4OnProviderFailure(@TempDir Path tempDir) {
        when(pathGuard.validateAndResolve("my-service")).thenReturn(tempDir);
        ServiceModel model = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(serviceModelCache.getOrScan(tempDir)).thenReturn(model);
        when(diagramAgent.generateDiagram(any(), any(), eq(DiagramType.SEQUENCE), any(), eq(4)))
            .thenThrow(new ProviderException("Google GenAI quota exceeded"));

        int code = cli.runWithArgs("generate", "--path", "my-service", "--type", "SEQUENCE");
        assertEquals(4, code);
    }

    @Test
    void exitCode5OnValidationFailed(@TempDir Path tempDir) {
        when(pathGuard.validateAndResolve("my-service")).thenReturn(tempDir);
        ServiceModel model = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(serviceModelCache.getOrScan(tempDir)).thenReturn(model);

        DiagramAgent.DiagramResult invalidResult = new DiagramAgent.DiagramResult(
            DiagramType.SEQUENCE, "sequenceDiagram\n broken syntax", false, 3, List.of("syntax error")
        );
        when(diagramAgent.generateDiagram(any(), any(), eq(DiagramType.SEQUENCE), any(), eq(4)))
            .thenReturn(invalidResult);

        int code = cli.runWithArgs("generate", "--path", "my-service", "--type", "SEQUENCE");
        assertEquals(5, code);
    }

    @Test
    void exitCode0OnSuccessfulGenerateToFile(@TempDir Path tempDir) throws IOException {
        Path mockProject = tempDir.resolve("proj");
        Files.createDirectories(mockProject);
        Path outFile = tempDir.resolve("output.mmd");

        when(pathGuard.validateAndResolve("proj")).thenReturn(mockProject);
        ServiceModel model = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(serviceModelCache.getOrScan(mockProject)).thenReturn(model);

        DiagramAgent.DiagramResult okResult = new DiagramAgent.DiagramResult(
            DiagramType.FLOWCHART, "flowchart TD\n  A-->B", true, 1, List.of()
        );
        when(diagramAgent.generateDiagram(any(), any(), eq(DiagramType.FLOWCHART), any(), eq(4)))
            .thenReturn(okResult);

        int code = cli.runWithArgs(
            "generate",
            "--path", "proj",
            "--type", "FLOWCHART",
            "--out", outFile.toString()
        );

        assertEquals(0, code);
        assertTrue(Files.exists(outFile));
        assertEquals("flowchart TD\n  A-->B", Files.readString(outFile).trim());
    }

    @Test
    void exitCode0OnEndpointsCommand(@TempDir Path tempDir) throws IOException {
        Path mockProject = tempDir.resolve("proj");
        Files.createDirectories(mockProject);
        Path outFile = tempDir.resolve("endpoints.txt");

        when(pathGuard.validateAndResolve("proj")).thenReturn(mockProject);
        EndpointInfo ep = new EndpointInfo("POST", "/orders", "OrderController", "create");
        ServiceModel model = new ServiceModel(List.of(), List.of(ep), List.of(), List.of(), List.of());
        when(serviceModelCache.getOrScan(mockProject)).thenReturn(model);

        int code = cli.runWithArgs("endpoints", "--path", "proj", "--out", outFile.toString());
        assertEquals(0, code);
        assertTrue(Files.exists(outFile));
        assertTrue(Files.readString(outFile).contains("POST"));
        assertTrue(Files.readString(outFile).contains("/orders"));
    }

    @Test
    void exitCode0OnValidateOnlyFile(@TempDir Path tempDir) throws IOException {
        Path mmdFile = tempDir.resolve("diagram.mmd");
        Files.writeString(mmdFile, "sequenceDiagram\n  A->>B: hi");

        when(mermaidValidator.validate(anyString(), any()))
            .thenReturn(new ValidationResult(true, null, true));

        int code = cli.runWithArgs("--validate-only", mmdFile.toString());
        assertEquals(0, code);
    }
}
