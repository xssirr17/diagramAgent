package com.example.diagramagent.agent;

import com.example.diagramagent.api.InsufficientInformationException;
import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.ClassInfo;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.EndpointInfo;
import com.example.diagramagent.scan.EnumInfo;
import com.example.diagramagent.scan.MethodInfo;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.scan.StateHint;
import com.example.diagramagent.scan.Stereotype;
import com.example.diagramagent.security.PathGuard;
import com.example.diagramagent.validate.DiagramRetryService;
import com.example.diagramagent.validate.MermaidValidator;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagramAgentTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private DiagramProperties properties;
    private MermaidValidator validator;
    private DiagramRetryService retryService;
    private ToolRegistry toolRegistry;
    private DiagramAgent agent;

    private ServiceModel testModel;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        properties = new DiagramProperties(
            null, 60000, 2, 4, false, 15, "mmdc", 500, 1048576L,
            new DiagramProperties.CacheProperties(true, 5, 30, false)
        );
        validator = new MermaidValidator(properties);
        retryService = new DiagramRetryService(validator, properties);
        toolRegistry = new ToolRegistry(mock(PathGuard.class));
        agent = new DiagramAgent(chatClient, retryService, properties, toolRegistry);

        MethodInfo m = new MethodInfo("create", "create()", "void", List.of(), List.of(), List.of(), "", false);
        ClassInfo c = new ClassInfo("OrderController", "com.example", Stereotype.REST_CONTROLLER, List.of(), List.of(), List.of(m), "");
        EndpointInfo ep = new EndpointInfo("POST", "/orders", "OrderController", "create");
        EnumInfo en = new EnumInfo("OrderStatus", "com.example", List.of("NEW", "PAID"));
        StateHint sh = new StateHint("Order", "status", "OrderStatus", List.of());

        testModel = new ServiceModel(List.of(c), List.of(ep), List.of(en), List.of(sh), new ArrayList<>());
    }

    @Test
    void succeedsOnFirstAttempt() {
        String validMermaid = """
            sequenceDiagram
                Client->>OrderController: POST /orders
                OrderController-->>Client: 200 OK
            """;
        when(callSpec.content()).thenReturn(validMermaid);

        DiagramAgent.DiagramResult result = agent.generateDiagram(
            Paths.get("."), testModel, DiagramType.SEQUENCE, "POST /orders", 4
        );

        assertTrue(result.valid());
        assertEquals(1, result.attempts());
        assertEquals(DiagramType.SEQUENCE, result.type());
        assertTrue(result.mermaid().contains("sequenceDiagram"));
    }

    @Test
    void triggersRetryLoopAndSucceedsOnSecondAttempt() {
        String invalidFirst = """
            sequenceDiagram
                Client->>OrderController: POST /orders
                alt invalid block
                    OrderController-->>Client: 400
            """; // Missing 'end'

        String validSecond = """
            sequenceDiagram
                Client->>OrderController: POST /orders
                alt invalid block
                    OrderController-->>Client: 400
                end
            """;

        when(callSpec.content())
            .thenReturn(invalidFirst)
            .thenReturn(validSecond);

        DiagramAgent.DiagramResult result = agent.generateDiagram(
            Paths.get("."), testModel, DiagramType.SEQUENCE, "POST /orders", 4
        );

        assertTrue(result.valid());
        assertEquals(2, result.attempts());
        assertTrue(result.mermaid().contains("end"));
    }

    @Test
    void convertsInsufficientInformationTo422Exception() {
        when(callSpec.content()).thenReturn("%% INSUFFICIENT_INFORMATION: No state machine detected");

        InsufficientInformationException ex = assertThrows(
            InsufficientInformationException.class,
            () -> agent.generateDiagram(Paths.get("."), testModel, DiagramType.STATE, null, 4)
        );

        assertEquals("No state machine detected", ex.getMessage());
    }
}
