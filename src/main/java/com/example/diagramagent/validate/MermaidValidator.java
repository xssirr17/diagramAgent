package com.example.diagramagent.validate;

import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.DiagramType;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MermaidValidator {

    private static final Logger log = LoggerFactory.getLogger(MermaidValidator.class);

    private final DiagramProperties properties;

    public MermaidValidator(DiagramProperties properties) {
        this.properties = properties;
    }

    public ValidationResult validate(String mermaid, DiagramType expectedType) {
        if (mermaid == null || mermaid.isBlank()) {
            return new ValidationResult(false, "Diagram content is empty", false);
        }

        // Try primary validation via mermaid-cli (mmdc)
        String cliPath = properties.mermaidCliPath();
        try {
            ValidationResult cliResult = validateWithMmdc(mermaid, cliPath);
            if (cliResult != null) {
                return cliResult;
            }
        } catch (Exception e) {
            log.debug("mmdc execution unavailable or failed: {}", e.getMessage());
        }

        // Fallback structural check
        return validateStructurally(mermaid, expectedType);
    }

    private ValidationResult validateWithMmdc(String mermaid, String cliPath) {
        Path tempIn = null;
        Path tempOut = null;
        try {
            String resolvedCli = cliPath;
            if (resolvedCli == null || resolvedCli.isBlank() || "mmdc".equals(resolvedCli)) {
                Path localBinWin = Path.of("node_modules", ".bin", "mmdc.cmd");
                Path localBin = Path.of("node_modules", ".bin", "mmdc");
                if (Files.exists(localBinWin)) {
                    resolvedCli = localBinWin.toAbsolutePath().toString();
                } else if (Files.exists(localBin)) {
                    resolvedCli = localBin.toAbsolutePath().toString();
                } else if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    resolvedCli = "mmdc.cmd";
                } else {
                    resolvedCli = "mmdc";
                }
            }

            tempIn = Files.createTempFile("diagram-", ".mmd");
            tempOut = Files.createTempFile("diagram-", ".svg");
            Files.writeString(tempIn, mermaid);

            ProcessBuilder pb = new ProcessBuilder(
                resolvedCli,
                "-i", tempIn.toAbsolutePath().toString(),
                "-o", tempOut.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(false);
            Process process = pb.start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null; // Fall back if mmdc hangs
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return new ValidationResult(true, null, false);
            } else {
                StringBuilder err = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        err.append(line).append("\n");
                    }
                }
                String errMsg = err.toString().trim();
                return new ValidationResult(false, errMsg.isBlank() ? "mmdc exited with code " + exitCode : errMsg, false);
            }
        } catch (IOException e) {
            // mmdc not found on system / cannot execute
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (tempIn != null) try { Files.deleteIfExists(tempIn); } catch (IOException ignored) {}
            if (tempOut != null) try { Files.deleteIfExists(tempOut); } catch (IOException ignored) {}
        }
    }

    public ValidationResult validateStructurally(String mermaid, DiagramType expectedType) {
        // 1. Check for markdown fences
        if (mermaid.contains("```")) {
            return new ValidationResult(false, "Mermaid output contains markdown fences", true);
        }

        List<String> lines = mermaid.lines()
            .map(String::trim)
            .filter(l -> !l.isBlank() && !l.startsWith("%%"))
            .toList();

        if (lines.isEmpty()) {
            return new ValidationResult(false, "Diagram body is empty", true);
        }

        String firstLine = lines.get(0);

        // 2. First line keyword check
        boolean headerMatches = switch (expectedType) {
            case SEQUENCE -> firstLine.startsWith("sequenceDiagram");
            case FLOWCHART -> firstLine.startsWith("flowchart") || firstLine.startsWith("graph");
            case STATE -> firstLine.startsWith("stateDiagram") || firstLine.startsWith("stateDiagram-v2");
        };

        if (!headerMatches) {
            return new ValidationResult(
                false,
                "First line does not match expected " + expectedType + " header. Found: '" + firstLine + "'",
                true
            );
        }

        // 3. Body check
        if (lines.size() < 2) {
            return new ValidationResult(false, "Diagram contains only header with no content", true);
        }

        // 4. Block balancing check
        if (expectedType == DiagramType.SEQUENCE) {
            int blockDepth = 0;
            for (String line : lines) {
                // Match block openers: alt, opt, loop, par, critical, break, rect
                if (matchesBlockOpener(line)) {
                    blockDepth++;
                } else if (line.equals("end") || line.startsWith("end ")) {
                    blockDepth--;
                }
            }
            if (blockDepth != 0) {
                return new ValidationResult(
                    false,
                    "Unbalanced sequence diagram blocks: " + (blockDepth > 0 ? "missing 'end'" : "extra 'end'"),
                    true
                );
            }
        } else if (expectedType == DiagramType.STATE) {
            int braceDepth = 0;
            for (String line : lines) {
                for (char ch : line.toCharArray()) {
                    if (ch == '{') braceDepth++;
                    if (ch == '}') braceDepth--;
                }
            }
            if (braceDepth != 0) {
                return new ValidationResult(false, "Unbalanced braces '{' and '}' in state diagram", true);
            }
        }

        return new ValidationResult(true, null, true);
    }

    private boolean matchesBlockOpener(String line) {
        return line.startsWith("alt ") || line.equals("alt") ||
            line.startsWith("opt ") || line.equals("opt") ||
            line.startsWith("loop ") || line.equals("loop") ||
            line.startsWith("par ") || line.equals("par") ||
            line.startsWith("critical ") || line.equals("critical") ||
            line.startsWith("break ") || line.equals("break") ||
            line.startsWith("rect ") || line.equals("rect");
    }
}
