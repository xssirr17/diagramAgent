package com.example.diagramagent.validate;

import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.DiagramType;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DiagramRetryService {

    private static final Logger log = LoggerFactory.getLogger(DiagramRetryService.class);

    private final MermaidValidator validator;
    private final DiagramProperties properties;

    public DiagramRetryService(MermaidValidator validator, DiagramProperties properties) {
        this.validator = validator;
        this.properties = properties;
    }

    public record RetryResult(
        String mermaid,
        boolean valid,
        int attempts,
        List<String> warnings
    ) {}

    public RetryResult executeWithRetry(
        String initialOutput,
        DiagramType type,
        List<String> warnings,
        Function<String, String> retryModelCaller
    ) {
        int maxRetries = properties.maxRetries();
        int attempts = 1;
        String currentMermaid = initialOutput;

        ValidationResult valResult = validator.validate(currentMermaid, type);
        if (valResult.usedFallback()) {
            warnings.add("Full mermaid-cli (mmdc) validation was skipped; structural check applied.");
        }

        while (!valResult.valid() && attempts <= maxRetries) {
            attempts++;
            log.warn("Mermaid validation failed on attempt {} (error: {}). Retrying with model...",
                attempts - 1, valResult.errorMessage());

            try {
                String retryPrompt = "Validation error: " + valResult.errorMessage() + "\nPrevious output:\n" + currentMermaid;
                currentMermaid = retryModelCaller.apply(retryPrompt);
                currentMermaid = MermaidSanitizer.sanitize(currentMermaid);

                valResult = validator.validate(currentMermaid, type);
                if (valResult.usedFallback() && !warnings.contains("Full mermaid-cli (mmdc) validation was skipped; structural check applied.")) {
                    warnings.add("Full mermaid-cli (mmdc) validation was skipped; structural check applied.");
                }
            } catch (Exception e) {
                log.error("Error during retry attempt {}: {}", attempts, e.getMessage());
                warnings.add("Retry attempt " + attempts + " failed: " + e.getMessage());
                break;
            }
        }

        if (!valResult.valid()) {
            warnings.add("Mermaid validation error after " + attempts + " attempt(s): " + valResult.errorMessage());
        }

        return new RetryResult(currentMermaid, valResult.valid(), attempts, warnings);
    }
}
