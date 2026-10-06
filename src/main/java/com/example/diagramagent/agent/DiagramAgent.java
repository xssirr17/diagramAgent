package com.example.diagramagent.agent;

import com.example.diagramagent.api.InsufficientInformationException;
import com.example.diagramagent.api.ProviderException;
import com.example.diagramagent.api.RateLimitExceededException;
import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.EndpointInfo;
import com.example.diagramagent.scan.ServiceModel;
import com.example.diagramagent.validate.DiagramRetryService;
import com.example.diagramagent.validate.MermaidSanitizer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class DiagramAgent {

    private static final Logger log = LoggerFactory.getLogger(DiagramAgent.class);

    private final ChatClient chatClient;
    private final DiagramRetryService retryService;
    private final DiagramProperties properties;
    private final ToolRegistry toolRegistry;

    public DiagramAgent(
        ChatClient chatClient,
        DiagramRetryService retryService,
        DiagramProperties properties,
        ToolRegistry toolRegistry
    ) {
        this.chatClient = chatClient;
        this.retryService = retryService;
        this.properties = properties;
        this.toolRegistry = toolRegistry;
    }

    public record DiagramResult(
        DiagramType type,
        String mermaid,
        boolean valid,
        int attempts,
        List<String> warnings
    ) {}

    public DiagramResult generateDiagram(
        Path projectPath,
        ServiceModel model,
        DiagramType type,
        String entryPoint,
        int maxDepth
    ) {
        List<String> warnings = new ArrayList<>(model.warnings());

        // Fast-path checks
        if (type == DiagramType.STATE) {
            if (model.enums().isEmpty() && model.stateHints().isEmpty()) {
                throw new InsufficientInformationException(
                    "No enums or state fields found in project to construct a STATE diagram."
                );
            }
        } else {
            if (model.classes().isEmpty()) {
                throw new InsufficientInformationException(
                    "No classes found in project to construct a " + type + " diagram."
                );
            }
        }

        // Entry point resolution
        String resolvedEntryPoint = entryPoint;
        if (type != DiagramType.STATE) {
            if (resolvedEntryPoint == null || resolvedEntryPoint.isBlank()) {
                Optional<EndpointInfo> defaultEp = model.findEndpoint(null);
                if (defaultEp.isPresent()) {
                    resolvedEntryPoint = defaultEp.get().toDisplayString();
                    warnings.add("entryPoint was omitted; defaulted to first detected endpoint: " + resolvedEntryPoint);
                } else if (!model.classes().isEmpty()) {
                    warnings.add("No endpoints detected; scanned structure directly.");
                }
            } else {
                String targetClass = extractTargetClass(resolvedEntryPoint, model);
                if (targetClass != null && !targetClass.isBlank()) {
                    boolean found = model.findClass(targetClass).isPresent();
                    log.info("Entry class check: targetClass='{}', foundInModel={}, totalClassesInModel={}",
                        targetClass, found, model.classes().size());
                    if (!found) {
                        throw new InsufficientInformationException(
                            "Entry class '" + targetClass + "' not found in scanned model (scanned " +
                            model.classes().size() + " classes). Ensure projectPath is correct or raise diagram.max-files-scanned."
                        );
                    }
                }
            }
        }

        // Build prompt context
        int depth = maxDepth > 0 ? maxDepth : properties.maxDepth();
        String context = model.toPromptContext(type, resolvedEntryPoint, depth, properties.maxContextChars());
        log.info("Prompt context generated: chars={}, maxAllowedChars={}", context.length(), properties.maxContextChars());

        // Set tool registry active context if agentic mode is enabled
        boolean agentic = properties.agentic();
        if (agentic) {
            toolRegistry.setActiveContext(projectPath, model);
        }

        try {
            String userPrompt = PromptTemplates.buildUserPrompt(type, context);
            String rawOutput = callModel(PromptTemplates.SYSTEM_PROMPT, userPrompt, agentic);

            String sanitized = MermaidSanitizer.sanitize(rawOutput);
            if (MermaidSanitizer.isInsufficientInformation(sanitized)) {
                String reason = MermaidSanitizer.extractInsufficientReason(sanitized);
                throw new InsufficientInformationException(reason);
            }

            // Validation & Retry loop
            var retryResult = retryService.executeWithRetry(
                sanitized,
                type,
                warnings,
                retryPrompt -> {
                    String corrected = callModel(PromptTemplates.SYSTEM_PROMPT, retryPrompt, false);
                    return corrected;
                }
            );

            return new DiagramResult(
                type,
                retryResult.mermaid(),
                retryResult.valid(),
                retryResult.attempts(),
                retryResult.warnings()
            );
        } finally {
            if (agentic) {
                toolRegistry.clearActiveContext();
            }
        }
    }

    private String callModel(String systemPrompt, String userPrompt, boolean agentic) {
        int maxRetries = 2;
        int rateLimitAttempt = 0;

        while (true) {
            try {
                var promptSpec = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt);

                if (agentic) {
                    promptSpec = promptSpec.tools(toolRegistry);
                }

                String content = promptSpec.call().content();

                // Safety filter check (empty or blocked content)
                if (content == null || content.isBlank()) {
                    log.warn("Model returned empty or safety-filtered response. Retrying once...");
                    content = promptSpec.call().content();
                    if (content == null || content.isBlank()) {
                        throw new ProviderException("Gemini response was blocked by safety filters or returned empty content");
                    }
                }

                return content;
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean isRateLimit = errorMsg.contains("429") ||
                    errorMsg.contains("resource_exhausted") ||
                    errorMsg.contains("rate limit");

                if (isRateLimit && rateLimitAttempt < maxRetries) {
                    rateLimitAttempt++;
                    long backoffMs = 1000L * (1L << rateLimitAttempt);
                    log.warn("Rate limit hit (attempt {}). Backing off for {} ms...", rateLimitAttempt, backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ProviderException("Interrupted during rate limit backoff", ie);
                    }
                    continue;
                } else if (isRateLimit) {
                    throw new RateLimitExceededException("Rate limit exhausted (HTTP 429 / RESOURCE_EXHAUSTED): " + e.getMessage(), e);
                }

                if (e instanceof InsufficientInformationException iie) {
                    throw iie;
                }
                if (e instanceof ProviderException pe) {
                    throw pe;
                }
                if (e instanceof RateLimitExceededException rle) {
                    throw rle;
                }

                throw new ProviderException("LLM provider call failed: " + e.getMessage(), e);
            }
        }
    }

    private String extractTargetClass(String ep, ServiceModel model) {
        if (ep == null || ep.isBlank()) return null;
        String trimmed = ep.trim();
        if (trimmed.contains("#")) {
            return trimmed.split("#")[0].trim();
        }
        Optional<EndpointInfo> matched = model.findEndpoint(trimmed);
        if (matched.isPresent()) {
            return matched.get().controllerClass();
        }
        if (!trimmed.contains(" ") && !trimmed.contains("/")) {
            return trimmed;
        }
        return null;
    }
}

