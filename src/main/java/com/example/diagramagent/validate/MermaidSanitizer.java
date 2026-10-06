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

        return content;
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
