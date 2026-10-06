package com.example.diagramagent.security;

import com.example.diagramagent.api.InvalidPathException;
import com.example.diagramagent.config.DiagramProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathGuardTest {

    @TempDir
    Path tempDir;

    private Path rootDir;
    private Path outsideDir;

    @BeforeEach
    void setUp() throws IOException {
        rootDir = tempDir.resolve("allowed-root");
        Files.createDirectories(rootDir);

        outsideDir = tempDir.resolve("outside-dir");
        Files.createDirectories(outsideDir);
    }

    private PathGuard createGuard(Path root) {
        DiagramProperties props = new DiagramProperties(
            root.toString(),
            60000,
            2,
            4,
            false,
            15,
            "mmdc",
            500,
            1048576L,
            new DiagramProperties.CacheProperties(true, 5, 30, false),
            null
        );
        return new PathGuard(props);
    }

    @Test
    void resolvesValidSubdirectory() throws IOException {
        Path sub = rootDir.resolve("service-a");
        Files.createDirectories(sub);

        PathGuard guard = createGuard(rootDir);
        Path resolved = guard.validateAndResolve("service-a");

        assertEquals(sub.toRealPath(), resolved);
    }

    @Test
    void resolvesEmptyPathToRoot() throws IOException {
        PathGuard guard = createGuard(rootDir);
        Path resolved = guard.validateAndResolve("");

        assertEquals(rootDir.toRealPath(), resolved);
    }

    @Test
    void rejectsParentTraversalEscape() {
        PathGuard guard = createGuard(rootDir);

        assertThrows(InvalidPathException.class, () ->
            guard.validateAndResolve("../outside-dir")
        );
    }

    @Test
    void rejectsMultipleTraversalEscape() {
        PathGuard guard = createGuard(rootDir);

        assertThrows(InvalidPathException.class, () ->
            guard.validateAndResolve("sub/../../outside-dir")
        );
    }

    @Test
    void rejectsAbsolutePathOutsideRoot() {
        PathGuard guard = createGuard(rootDir);

        assertThrows(InvalidPathException.class, () ->
            guard.validateAndResolve(outsideDir.toAbsolutePath().toString())
        );
    }

    @Test
    void allowsAbsolutePathInsideRoot() throws IOException {
        Path sub = rootDir.resolve("nested");
        Files.createDirectories(sub);

        PathGuard guard = createGuard(rootDir);
        Path resolved = guard.validateAndResolve(sub.toAbsolutePath().toString());

        assertEquals(sub.toRealPath(), resolved);
    }

    @Test
    void rejectsNonExistentPath() {
        PathGuard guard = createGuard(rootDir);

        assertThrows(InvalidPathException.class, () ->
            guard.validateAndResolve("does-not-exist")
        );
    }

    @Test
    void rejectsSymlinkEscapingRoot() throws IOException {
        Path linkTarget = outsideDir.resolve("secret.txt");
        Files.writeString(linkTarget, "secret");

        Path link = rootDir.resolve("symlink-to-outside");
        try {
            Files.createSymbolicLink(link, linkTarget);
        } catch (UnsupportedOperationException | IOException e) {
            // Symlink creation might require privileges on Windows developer mode; skip if not supported
            return;
        }

        PathGuard guard = createGuard(rootDir);
        assertThrows(InvalidPathException.class, () ->
            guard.validateAndResolve("symlink-to-outside")
        );
    }

    @Test
    void throwsWhenAllowedRootNotConfigured() {
        DiagramProperties props = new DiagramProperties(
            null,
            60000,
            2,
            4,
            false,
            15,
            "mmdc",
            500,
            1048576L,
            new DiagramProperties.CacheProperties(true, 5, 30, false),
            null
        );
        PathGuard guard = new PathGuard(props);

        assertThrows(IllegalStateException.class, () ->
            guard.validateAndResolve("some-path")
        );
    }
}
