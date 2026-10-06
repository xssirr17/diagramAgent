package com.example.diagramagent.api;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.cache.ServiceModelCache;
import com.example.diagramagent.render.DiagramRenderer;
import com.example.diagramagent.security.PathGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@SuppressWarnings("removal")
class StaticWebPageTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PathGuard pathGuard;

    @MockBean
    private ServiceModelCache serviceModelCache;

    @MockBean
    private DiagramAgent diagramAgent;

    @MockBean
    private DiagramRenderer diagramRenderer;

    @MockBean
    private com.example.diagramagent.diff.GitService gitService;

    @MockBean
    private com.example.diagramagent.diff.ModelDiffer modelDiffer;

    @Test
    void indexPageIsServed() throws Exception {
        mockMvc.perform(get("/index.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Diagram Agent")));
    }

    @Test
    void mermaidJsIsServed() throws Exception {
        mockMvc.perform(get("/mermaid.min.js"))
            .andExpect(status().isOk());
    }
}
