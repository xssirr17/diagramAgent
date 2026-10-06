package com.example.diagramagent.agent;

import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.ProjectScanner;
import com.example.diagramagent.scan.ServiceModel;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("live")
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
class DiagramAgentLiveTest {

    @Autowired
    private DiagramAgent diagramAgent;

    @Autowired
    private ProjectScanner projectScanner;

    @Test
    void generatesLiveDiagramAgainstFixture() {
        Path fixturePath = Paths.get("src/test/resources/fixtures/order-service").toAbsolutePath();
        ServiceModel model = projectScanner.scan(fixturePath);

        DiagramAgent.DiagramResult result = diagramAgent.generateDiagram(
            fixturePath,
            model,
            DiagramType.SEQUENCE,
            "POST /orders",
            4
        );

        assertNotNull(result);
        assertNotNull(result.mermaid());
        assertTrue(result.mermaid().contains("sequenceDiagram"));
    }
}
