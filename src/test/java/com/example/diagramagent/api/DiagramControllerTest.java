package com.example.diagramagent.api;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.EndpointInfo;
import com.example.diagramagent.scan.ProjectScanner;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.security.PathGuard;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {DiagramController.class, GlobalExceptionHandler.class})
@SuppressWarnings("removal")
class DiagramControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PathGuard pathGuard;

    @MockBean
    private ProjectScanner projectScanner;

    @MockBean
    private DiagramAgent diagramAgent;

    @Test
    void postDiagramReturns200WithMermaid() throws Exception {
        Path mockPath = Paths.get("/safe/root/project");
        when(pathGuard.validateAndResolve(any())).thenReturn(mockPath);

        ServiceModel mockModel = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(projectScanner.scan(mockPath)).thenReturn(mockModel);

        DiagramAgent.DiagramResult agentResult = new DiagramAgent.DiagramResult(
            DiagramType.SEQUENCE, "sequenceDiagram\nClient->>A: ping", true, 1, List.of()
        );
        when(diagramAgent.generateDiagram(eq(mockPath), eq(mockModel), eq(DiagramType.SEQUENCE), any(), eq(4)))
            .thenReturn(agentResult);

        String json = """
            {
                "path": "sub-dir",
                "type": "SEQUENCE",
                "entryPoint": "OrderController#createOrder",
                "maxDepth": 4
            }
            """;

        mockMvc.perform(post("/api/diagrams")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("SEQUENCE"))
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.attempts").value(1))
            .andExpect(jsonPath("$.mermaid").value("sequenceDiagram\nClient->>A: ping"));
    }

    @Test
    void postDiagramReturns400OnPathEscapingRoot() throws Exception {
        when(pathGuard.validateAndResolve("../outside"))
            .thenThrow(new InvalidPathException("Path escapes allowed root"));

        String json = """
            {
                "path": "../outside",
                "type": "SEQUENCE"
            }
            """;

        mockMvc.perform(post("/api/diagrams")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid Path"))
            .andExpect(jsonPath("$.detail").value("Path escapes allowed root"));
    }

    @Test
    void postDiagramReturns422OnInsufficientInformation() throws Exception {
        Path mockPath = Paths.get("/safe/root/project");
        when(pathGuard.validateAndResolve(any())).thenReturn(mockPath);

        ServiceModel mockModel = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(projectScanner.scan(mockPath)).thenReturn(mockModel);

        when(diagramAgent.generateDiagram(any(), any(), eq(DiagramType.STATE), any(), eq(4)))
            .thenThrow(new InsufficientInformationException("No states found in project"));

        String json = """
            {
                "path": "service",
                "type": "STATE"
            }
            """;

        mockMvc.perform(post("/api/diagrams")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.title").value("Insufficient Information"))
            .andExpect(jsonPath("$.detail").value("No states found in project"));
    }

    @Test
    void listEndpointsReturns200() throws Exception {
        Path mockPath = Paths.get("/safe/root/project");
        when(pathGuard.validateAndResolve(any())).thenReturn(mockPath);

        EndpointInfo ep = new EndpointInfo("POST", "/orders", "OrderController", "create");
        ServiceModel mockModel = new ServiceModel(List.of(), List.of(ep), List.of(), List.of(), List.of());
        when(projectScanner.scan(mockPath)).thenReturn(mockModel);

        mockMvc.perform(get("/api/diagrams/endpoints").param("path", "service"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].httpMethod").value("POST"))
            .andExpect(jsonPath("$[0].path").value("/orders"))
            .andExpect(jsonPath("$[0].handler").value("OrderController#create"));
    }
}
