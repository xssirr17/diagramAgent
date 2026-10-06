package com.example.diagramagent.diff;

import java.util.regex.Pattern;

public final class GitRefValidator {

    private static final Pattern SAFE_REF_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-./~^@{}]+$");

    private GitRefValidator() {}

    public static String validate(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new InvalidGitRefException("Git ref cannot be blank");
        }
        String trimmed = ref.trim();
        if (!SAFE_REF_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidGitRefException("Git ref contains forbidden characters: " + trimmed);
        }
        if (trimmed.contains("..")) {
            throw new InvalidGitRefException("Git ref cannot contain '..': " + trimmed);
        }
        if (trimmed.startsWith("/") || trimmed.endsWith("/") || trimmed.startsWith(".") || trimmed.endsWith(".")) {
            throw new InvalidGitRefException("Git ref cannot start or end with '.' or '/': " + trimmed);
        }
        return trimmed;
    }
}
