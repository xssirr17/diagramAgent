package com.example.diagramagent.validate;

import java.util.List;

public class MermaidSanitizer {

    private static final String INSUFFICIENT_PREFIX = "%% INSUFFICIENT_INFORMATION";

    private static final List<String> DIAGRAM_KEYWORDS = List.of(
        INSUFFICIENT_PREFIX,
        "sequenceDiagram",
        "flowchart TD",
        "flowchart LR",
        "flowchart TB",
        "flowchart RL",
        "flowchart",
        "graph TD",
        "graph LR",
        "graph",
        "stateDiagram-v2",
        "stateDiagram"
    );

    public static String sanitize(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return "";
        }

        String content = rawOutput.trim();

        // 1. Check for INSUFFICIENT_INFORMATION
        int insufficientIdx = content.indexOf(INSUFFICIENT_PREFIX);
        if (insufficientIdx >= 0) {
            String fromInsufficient = content.substring(insufficientIdx).trim();
            int newlineIdx = fromInsufficient.indexOf('\n');
            if (newlineIdx > 0) {
                return fromInsufficient.substring(0, newlineIdx).trim();
            }
            return fromInsufficient;
        }

        // 2. Locate first valid diagram keyword (strip preambles)
        int earliestKeywordIdx = -1;
        for (String kw : DIAGRAM_KEYWORDS) {
            int idx = content.indexOf(kw);
            if (idx >= 0 && (earliestKeywordIdx == -1 || idx < earliestKeywordIdx)) {
                earliestKeywordIdx = idx;
            }
        }

        if (earliestKeywordIdx >= 0) {
            content = content.substring(earliestKeywordIdx).trim();
        }

        // 3. If there is a closing markdown fence after the diagram keyword, strip it and anything following it (postambles)
        int closingFenceIdx = content.indexOf("```");
        if (closingFenceIdx >= 0) {
            content = content.substring(0, closingFenceIdx).trim();
        }

        // 4. Remove any residual markdown fences
        content = content.replaceAll("(?m)^```[a-zA-Z0-9_-]*\\s*$", "").trim();

        // 5. Post-process Mermaid syntax (strip quotes in participants, sanitize angle/curly brackets)
        content = postProcessMermaid(content);

        return content;
    }

    public static String postProcessMermaid(String content) {
        if (content == null || content.isBlank()) return "";

        // Strip quotes from participant / actor declarations:
        // participant C as "OrderController" -> participant C as OrderController
        content = content.replaceAll("(?m)^(\\s*(?:participant|actor)\\s+[A-Za-z0-9_]+\\s+as\\s+)\"([^\"]+)\"\\s*$", "$1$2");
        content = content.replaceAll("(?m)^(\\s*(?:participant|actor)\\s+)\"([^\"]+)\"(\\s+as\\s+[A-Za-z0-9_]+\\s*)$", "$1$2$3");
        content = content.replaceAll("(?m)^(\\s*(?:participant|actor)\\s+)\"([^\"]+)\"\\s*$", "$1$2");

        boolean isSequence = content.startsWith("sequenceDiagram");

        String[] lines = content.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isSequence) {
                line = sanitizeSequenceLine(line);
            } else {
                line = sanitizeGeneralLine(line);
            }
            sb.append(line);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private static String sanitizeSequenceLine(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("%%")) {
            return line;
        }

        // Arrow message: A->>B: message or A-->>B: message
        // Or note: Note over A: message
        boolean hasArrow = trimmed.contains("->") || trimmed.contains("--");
        boolean isNote = trimmed.toLowerCase().startsWith("note ");

        if ((hasArrow || isNote) && trimmed.contains(":")) {
            int colonIdx = line.indexOf(':');
            String prefix = line.substring(0, colonIdx + 1);
            String message = line.substring(colonIdx + 1);

            // Replace <...> with ~...~
            message = message.replace('<', '~').replace('>', '~');
            // Replace {...} with (...)
            message = message.replace('{', '(').replace('}', ')');

            return prefix + message;
        }

        return line;
    }

    private static String sanitizeGeneralLine(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("%%")) {
            return line;
        }

        // For flowchart / state diagrams, avoid < and > in labels which trigger unclosed HTML tag errors in mermaid-cli
        if (line.contains("<") || line.contains(">")) {
            return line.replace('<', '~').replace('>', '~');
        }
        return line;
    }

    public static boolean isInsufficientInformation(String output) {
        return output != null && output.contains(INSUFFICIENT_PREFIX);
    }

    public static String extractInsufficientReason(String output) {
        if (output == null) return "Insufficient information";
        int idx = output.indexOf(INSUFFICIENT_PREFIX);
        if (idx >= 0) {
            String line = output.substring(idx).trim();
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String reason = line.substring(colonIdx + 1).trim();
                int end = reason.indexOf('\n');
                return (end > 0 ? reason.substring(0, end) : reason).trim();
            }
        }
        return "Not enough information to generate the requested diagram";
    }
}
