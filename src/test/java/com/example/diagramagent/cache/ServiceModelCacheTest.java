package com.example.diagramagent.cache;

import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.ProjectScanner;
import com.example.diagramagent.scan.ServiceModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceModelCacheTest {

    @Test
    void cacheHitReturnsCachedModelWithoutRescanning(@TempDir Path tempDir) throws IOException {
        Path javaDir = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Path file = javaDir.resolve("TestService.java");
        Files.writeString(file, "package com.example; public class TestService {}");

        ProjectScanner scanner = Mockito.mock(ProjectScanner.class);
        ServiceModel mockModel = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(scanner.scan(tempDir)).thenReturn(mockModel);

        DiagramProperties props = new DiagramProperties(
            null, 60000, 2, 4, false, 15, "mmdc", 500, 1048576L,
            new DiagramProperties.CacheProperties(true, 5, 30, false),
            null
        );

        ServiceModelCache cache = new ServiceModelCache(props, scanner, new MockEnvironment());

        // First call -> scanner called
        ServiceModel res1 = cache.getOrScan(tempDir);
        assertNotNull(res1);
        verify(scanner, times(1)).scan(tempDir);

        // Second call -> cache hit, scanner NOT called again
        ServiceModel res2 = cache.getOrScan(tempDir);
        assertEquals(res1, res2);
        verify(scanner, times(1)).scan(tempDir);
    }

    @Test
    void invalidatesCacheWhenFileModified(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path javaDir = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Path file = javaDir.resolve("TestService.java");
        Files.writeString(file, "package com.example; public class TestService {}");

        ProjectScanner scanner = Mockito.mock(ProjectScanner.class);
        ServiceModel model1 = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        ServiceModel model2 = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(scanner.scan(tempDir)).thenReturn(model1).thenReturn(model2);

        DiagramProperties props = new DiagramProperties(
            null, 60000, 2, 4, false, 15, "mmdc", 500, 1048576L,
            new DiagramProperties.CacheProperties(true, 5, 30, false),
            null
        );

        ServiceModelCache cache = new ServiceModelCache(props, scanner, new MockEnvironment());

        // First call -> misses cache
        ServiceModel res1 = cache.getOrScan(tempDir);
        assertEquals(model1, res1);
        verify(scanner, times(1)).scan(tempDir);

        // Sleep briefly to ensure mtime changes, then modify file
        Thread.sleep(50);
        Files.writeString(file, "package com.example; public class TestService { void extra() {} }");

        // Next call -> detects fingerprint difference, re-scans
        ServiceModel res2 = cache.getOrScan(tempDir);
        assertEquals(model2, res2);
        verify(scanner, times(2)).scan(tempDir);
    }

    @Test
    void respectsBoundedSize(@TempDir Path tempDir) throws IOException {
        Path dir1 = Files.createDirectories(tempDir.resolve("dir1"));
        Path dir2 = Files.createDirectories(tempDir.resolve("dir2"));
        Path dir3 = Files.createDirectories(tempDir.resolve("dir3"));

        Files.writeString(dir1.resolve("A.java"), "class A {}");
        Files.writeString(dir2.resolve("B.java"), "class B {}");
        Files.writeString(dir3.resolve("C.java"), "class C {}");

        ProjectScanner scanner = Mockito.mock(ProjectScanner.class);
        ServiceModel mockModel = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(scanner.scan(any())).thenReturn(mockModel);

        // Max 2 projects
        DiagramProperties props = new DiagramProperties(
            null, 60000, 2, 4, false, 15, "mmdc", 500, 1048576L,
            new DiagramProperties.CacheProperties(true, 2, 30, false),
            null
        );

        ServiceModelCache cache = new ServiceModelCache(props, scanner, new MockEnvironment());

        cache.getOrScan(dir1);
        cache.getOrScan(dir2);
        cache.getOrScan(dir3);

        cache.cleanUp();
        // Caffeine bounded size
        assertEquals(2, cache.modelCacheSize());
    }

    @Test
    void disabledModeAlwaysScansDirectly(@TempDir Path tempDir) throws IOException {
        Path javaDir = Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(javaDir.resolve("Test.java"), "class Test {}");

        ProjectScanner scanner = Mockito.mock(ProjectScanner.class);
        ServiceModel mockModel = new ServiceModel(List.of(), List.of(), List.of(), List.of(), List.of());
        when(scanner.scan(tempDir)).thenReturn(mockModel);

        DiagramProperties props = new DiagramProperties(
            null, 60000, 2, 4, false, 15, "mmdc", 500, 1048576L,
            new DiagramProperties.CacheProperties(false, 5, 30, false),
            null
        );

        ServiceModelCache cache = new ServiceModelCache(props, scanner, new MockEnvironment());

        cache.getOrScan(tempDir);
        cache.getOrScan(tempDir);

        verify(scanner, times(2)).scan(tempDir);
        assertEquals(0, cache.modelCacheSize());
    }
}
