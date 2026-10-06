package com.example.diagramagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.api.DiagramResponse;
import com.example.diagramagent.api.DiffResponse;
import com.example.diagramagent.cache.ServiceModelCache;
import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.diff.GitService;
import com.example.diagramagent.diff.ModelDiffer;
import com.example.diagramagent.diff.StructuralDiff;
import com.example.diagramagent.render.DiagramFormat;
import com.example.diagramagent.render.DiagramRenderer;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.EndpointInfo;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.security.PathGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiagramMcpToolsTest {

    private PathGuard pathGuard;
    private ServiceModelCache serviceModelCache;
    private DiagramAgent diagramAgent;
    private DiagramRenderer diagramRenderer;
    private GitService gitService;
    private ModelDiffer modelDiffer;
    private DiagramProperties properties;

    private DiagramMcpTools mcpTools;

    @BeforeEach
    void setUp() {
        pathGuard = mock(PathGuard.class);
        serviceModelCache = mock(ServiceModelCache.class);
        diagramAgent = mock(DiagramAgent.class);
        diagramRenderer = mock(DiagramRenderer.class);
        gitService = mock(GitService.class);
        modelDiffer = mock(ModelDiffer.class);
        properties = new DiagramProperties(
                ".", 60000, 2, 4, false, 15, "mmdc", 5000, 1048576L,
                new DiagramProperties.CacheProperties(true, 5, 30, false),
                null
        );

        mcpTools = new DiagramMcpTools(
                pathGuard,
                serviceModelCache,
                diagramAgent,
                diagramRenderer,
                gitService,
                modelDiffer,
                properties
        );
    }

    @Test
    @DisplayName("list_endpoints resolves path and extracts endpoints from cached ServiceModel")
    void testListEndpoints() {
        Path mockPath = Path.of("order-service");
        when(pathGuard.validateAndResolve("order-service")).thenReturn(mockPath);

        ServiceModel model = new ServiceModel(
                List.of(),
                List.of(new EndpointInfo("POST", "/orders", "OrderController", "createOrder")),
                List.of(),
                List.of(),
                List.of()
        );
        when(serviceModelCache.getOrScan(mockPath)).thenReturn(model);

        List<DiagramMcpTools.EndpointDto> endpoints = mcpTools.listEndpoints("order-service");

        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).method()).isEqualTo("POST");
        assertThat(endpoints.get(0).path()).isEqualTo("/orders");
        assertThat(endpoints.get(0).handler()).isEqualTo("OrderController#createOrder");
    }

    @Test
    @DisplayName("generate_diagram validates path and invokes diagramAgent")
    void testGenerateDiagram() {
        Path mockPath = Path.of("order-service");
        when(pathGuard.validateAndResolve("order-service")).thenReturn(mockPath);

        ServiceModel model = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(serviceModelCache.getOrScan(mockPath)).thenReturn(model);

        DiagramAgent.DiagramResult expected = new DiagramAgent.DiagramResult(
                DiagramType.SEQUENCE, "sequenceDiagram\nA->B: msg", true, 1, List.of(), false
        );
        when(diagramAgent.generateDiagram(eq(mockPath), eq(model), eq(DiagramType.SEQUENCE), eq("OrderController#payOrder"), eq(3)))
                .thenReturn(expected);

        DiagramResponse response = mcpTools.generateDiagram("order-service", "SEQUENCE", "OrderController#payOrder", 3);

        assertThat(response.valid()).isTrue();
        assertThat(response.mermaid()).contains("sequenceDiagram");
        verify(diagramAgent).generateDiagram(mockPath, model, DiagramType.SEQUENCE, "OrderController#payOrder", 3);
    }

    @Test
    @DisplayName("diff_diagram validates refs, extracts models, diffs and invokes diagramAgent.generateDiffDiagram")
    void testDiffDiagram() {
        Path mockPath = Path.of("repo");
        when(pathGuard.validateAndResolve("repo")).thenReturn(mockPath);

        ServiceModel fromModel = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        ServiceModel toModel = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(gitService.extractModelAtRef(mockPath, "HEAD~1")).thenReturn(fromModel);
        when(gitService.extractModelAtRef(mockPath, "HEAD")).thenReturn(toModel);

        StructuralDiff diff = new StructuralDiff(List.of(), "1 changed", false);
        when(modelDiffer.diff(fromModel, toModel, DiagramType.FLOWCHART, null)).thenReturn(diff);

        DiagramAgent.DiagramResult expected = new DiagramAgent.DiagramResult(
                DiagramType.FLOWCHART, "flowchart TD\nA-->B", true, 1, List.of(), false
        );
        when(diagramAgent.generateDiffDiagram(mockPath, fromModel, toModel, diff, DiagramType.FLOWCHART, null, 4))
                .thenReturn(expected);

        DiffResponse response = mcpTools.diffDiagram("repo", "HEAD~1", "HEAD", "FLOWCHART", null, null);

        assertThat(response.valid()).isTrue();
        assertThat(response.fromRef()).isEqualTo("HEAD~1");
        assertThat(response.toRef()).isEqualTo("HEAD");
    }

    @Test
    @DisplayName("render_diagram throws exception if mmdc is not available")
    void testRenderDiagramUnavailable() {
        when(diagramRenderer.isMmdcAvailable()).thenReturn(false);

        assertThatThrownBy(() -> mcpTools.renderDiagram("flowchart TD\nA-->B", "SVG"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mmdc (Mermaid CLI) is not available");
    }

    @Test
    @DisplayName("render_diagram returns SVG content and base64 when mmdc is available")
    void testRenderDiagramSvg() {
        when(diagramRenderer.isMmdcAvailable()).thenReturn(true);
        byte[] fakeSvg = "<svg>diagram</svg>".getBytes(StandardCharsets.UTF_8);
        when(diagramRenderer.render("flowchart TD\nA-->B", DiagramFormat.SVG)).thenReturn(fakeSvg);

        DiagramMcpTools.RenderDiagramResponse response = mcpTools.renderDiagram("flowchart TD\nA-->B", "SVG");

        assertThat(response.format()).isEqualTo("SVG");
        assertThat(response.contentType()).isEqualTo("image/svg+xml");
        assertThat(response.content()).isEqualTo("<svg>diagram</svg>");
        assertThat(response.base64()).isNotEmpty();
    }
}
