package com.example.diagramagent.security;

import com.example.diagramagent.api.InvalidPathException;
import com.example.diagramagent.config.DiagramProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Component;

@Component
public class PathGuard {

    private final DiagramProperties properties;

    public PathGuard(DiagramProperties properties) {
        this.properties = properties;
    }

    public Path validateAndResolve(String requestedPath) {
        String allowedRootStr = properties.allowedRoot();
        if (allowedRootStr == null || allowedRootStr.isBlank()) {
            throw new IllegalStateException("diagram.allowed-root is not configured");
        }

        Path rootPath = Paths.get(allowedRootStr).toAbsolutePath().normalize();
        if (!Files.exists(rootPath)) {
            throw new InvalidPathException("Allowed root path does not exist: " + allowedRootStr);
        }

        Path realRoot;
        try {
            realRoot = rootPath.toRealPath();
        } catch (IOException e) {
            throw new InvalidPathException("Failed to resolve real path for allowed root: " + allowedRootStr, e);
        }

        if (requestedPath == null || requestedPath.isBlank()) {
            return realRoot;
        }

        Path requested = Paths.get(requestedPath);
        Path candidate;
        if (requested.isAbsolute()) {
            candidate = requested.normalize();
        } else {
            candidate = realRoot.resolve(requested).normalize();
        }

        // Fast check before existence check for obvious parent directory traversal escapes
        if (!candidate.startsWith(rootPath) && !candidate.startsWith(realRoot)) {
            throw new InvalidPathException("Path escapes allowed root: " + requestedPath);
        }

        if (!Files.exists(candidate)) {
            throw new com.example.diagramagent.api.PathNotFoundException("Requested path does not exist: " + requestedPath);
        }

        try {
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw new InvalidPathException("Path escapes allowed root: " + requestedPath);
            }
            return realCandidate;
        } catch (IOException e) {
            throw new InvalidPathException("Failed to resolve real path for target: " + requestedPath, e);
        }
    }
}
