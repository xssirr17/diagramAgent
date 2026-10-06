package com.example.diagramagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "diagram.allowed-root=.",
        "spring.main.web-application-type=none",
        "spring.ai.mcp.server.enabled=true",
        "spring.ai.mcp.server.stdio=true"
})
@ActiveProfiles({"mcp", "test"})
class DiagramMcpIntegrationTest {

    @MockBean
    private ChatModel chatModel;

    @Autowired(required = false)
    private DiagramMcpTools mcpTools;

    @Autowired(required = false)
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    @DisplayName("MCP tools and ToolCallbackProvider beans are properly loaded under mcp profile with all 4 tools")
    void testMcpBeansLoaded() {
        assertThat(mcpTools).isNotNull();
        assertThat(toolCallbackProvider).isNotNull();
        assertThat(toolCallbackProvider.getToolCallbacks()).isNotEmpty();

        List<String> toolNames = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(cb -> cb.getToolDefinition().name())
                .toList();

        assertThat(toolNames).contains(
                "list_endpoints",
                "generate_diagram",
                "diff_diagram",
                "render_diagram"
        );
    }
}
