package com.example.diagramagent.diff;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.agent.ToolRegistry;
import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.CallChainExtractor;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.EndpointExtractor;
import com.example.diagramagent.scan.ProjectScanner;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.scan.StateExtractor;
import com.example.diagramagent.security.PathGuard;
import com.example.diagramagent.validate.DiagramRetryService;
import com.example.diagramagent.validate.MermaidValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitDiffIntegrationTest {

    private DiagramProperties properties;
    private ProjectScanner scanner;
    private GitService gitService;
    private ModelDiffer modelDiffer;

    @BeforeEach
    void setUp() {
        properties = new DiagramProperties(
            null, 60000, 2, 4, false, 15, "mmdc", 500, 1048576L,
            new DiagramProperties.CacheProperties(true, 5, 30, false),
            null
        );
        scanner = new ProjectScanner(
            properties,
            new EndpointExtractor(),
            new CallChainExtractor(),
            new StateExtractor()
        );
        gitService = new GitService(scanner, properties);
        modelDiffer = new ModelDiffer();
    }

    @Test
    void testRefValidatorRejectsUnsafeRefs() {
        assertThrows(InvalidGitRefException.class, () -> GitRefValidator.validate("HEAD; rm -rf /"));
        assertThrows(InvalidGitRefException.class, () -> GitRefValidator.validate("ref..with..dots"));
        assertThrows(InvalidGitRefException.class, () -> GitRefValidator.validate("/leading-slash"));
        assertThrows(InvalidGitRefException.class, () -> GitRefValidator.validate("trailing/"));
        assertThrows(InvalidGitRefException.class, () -> GitRefValidator.validate(""));
        assertEquals("HEAD~1", GitRefValidator.validate("HEAD~1"));
        assertEquals("main", GitRefValidator.validate("main"));
        assertEquals("v1.2.3", GitRefValidator.validate("v1.2.3"));
    }

    @Test
    void testGitDiffBetweenCommitsDetectsChanges(@TempDir Path repoDir) throws IOException, GitAPIException {
        // 1. Initialize git repo
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Path srcDir = Files.createDirectories(repoDir.resolve("src/main/java/com/example"));

            Path enumFile = srcDir.resolve("OrderStatus.java");
            Files.writeString(enumFile, """
                package com.example;
                public enum OrderStatus {
                    NEW,
                    PAID
                }
                """);

            Path serviceFile = srcDir.resolve("OrderService.java");
            Files.writeString(serviceFile, """
                package com.example;
                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    private final PaymentGateway gateway;
                    public OrderService(PaymentGateway gateway) { this.gateway = gateway; }

                    public void payOrder(String id) {
                        gateway.charge(id);
                    }
                }
                """);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Commit 1: initial payment flow").call();

            // Commit 2: change call to notification service and add enum transition/constant
            Files.writeString(enumFile, """
                package com.example;
                public enum OrderStatus {
                    NEW,
                    PAID,
                    SHIPPED
                }
                """);

            Files.writeString(serviceFile, """
                package com.example;
                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    private final NotificationService notifier;
                    public OrderService(NotificationService notifier) { this.notifier = notifier; }

                    public void payOrder(String id) {
                        notifier.notifyCustomer(id);
                    }
                }
                """);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Commit 2: change call and add SHIPPED enum").call();
        }

        // 2. Extract models at HEAD~1 and HEAD
        ServiceModel modelCommit1 = gitService.extractModelAtRef(repoDir, "HEAD~1");
        ServiceModel modelCommit2 = gitService.extractModelAtRef(repoDir, "HEAD");

        assertNotNull(modelCommit1);
        assertNotNull(modelCommit2);

        // 3. Compute structural diff
        StructuralDiff diff = modelDiffer.diff(modelCommit1, modelCommit2, DiagramType.FLOWCHART, "OrderService#payOrder");

        assertNotNull(diff);
        assertFalse(diff.entryPointUnchanged(), "Entry point should be flagged as changed due to modified calls");

        // Verify Enum constant SHIPPED is marked ADDED
        boolean hasAddedShipped = diff.changes().stream().anyMatch(
            c -> c.type() == ChangeType.ENUM_CONSTANT && c.name().contains("SHIPPED") && c.status() == ChangeStatus.ADDED
        );
        assertTrue(hasAddedShipped, "Should detect added SHIPPED enum constant");

        // Verify call changes
        boolean hasCallChanges = diff.changes().stream().anyMatch(
            c -> c.type() == ChangeType.CALL || (c.type() == ChangeType.METHOD && c.status() == ChangeStatus.MODIFIED)
        );
        assertTrue(hasCallChanges, "Should detect call changes in payOrder");
    }

    @Test
    void testDiagramAgentGeneratesDiffDiagramWithMockedChatClient(@TempDir Path repoDir) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        String mockMermaidDiff = """
            flowchart TD
                classDef added fill:#dcfce7,stroke:#16a34a,stroke-width:2px;
                classDef removed fill:#fee2e2,stroke:#dc2626,stroke-width:2px,stroke-dasharray: 5 5;
                start([Start: payOrder]) --> callNew[notifier.notifyCustomer]:::added
                start -.-> callOld[gateway.charge]:::removed
            """;
        when(callSpec.content()).thenReturn(mockMermaidDiff);

        MermaidValidator validator = new MermaidValidator(properties);
        DiagramRetryService retryService = new DiagramRetryService(validator, properties);
        ToolRegistry toolRegistry = new ToolRegistry(mock(PathGuard.class));
        DiagramAgent agent = new DiagramAgent(chatClient, retryService, properties, toolRegistry);

        ServiceModel m1 = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        ServiceModel m2 = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        StructuralDiff diff = new StructuralDiff(
            List.of(new StructuralChange(ChangeType.CALL, "OrderService#payOrder", ChangeStatus.ADDED, "notifier.notifyCustomer")),
            "Detected 1 change",
            false
        );

        DiagramAgent.DiagramResult result = agent.generateDiffDiagram(
            repoDir, m1, m2, diff, DiagramType.FLOWCHART, "OrderService#payOrder", 4
        );

        assertNotNull(result);
        assertTrue(result.valid());
        assertTrue(result.mermaid().contains("classDef added"));
        assertTrue(result.mermaid().contains(":::added"));
        assertTrue(result.mermaid().contains(":::removed"));
    }
}
