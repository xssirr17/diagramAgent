package com.example.diagramagent.cache;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.DiagramType;
import com.example.diagramagent.scan.ProjectScanner;
import com.example.diagramagent.scan.ServiceModel;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ServiceModelCache {

    private static final Logger log = LoggerFactory.getLogger(ServiceModelCache.class);
    private static final String PROMPT_VERSION = "v1";

    private final DiagramProperties properties;
    private final ProjectScanner projectScanner;
    private final Environment environment;
    private final Cache<String, CachedServiceModel> modelCache;
    private final Cache<DiagramResultCacheKey, DiagramAgent.DiagramResult> resultCache;

    public record CachedServiceModel(
        String treeFingerprint,
        ServiceModel model,
        long createdAtMs
    ) {}

    public ServiceModelCache(DiagramProperties properties, ProjectScanner projectScanner, Environment environment) {
        this.properties = properties;
        this.projectScanner = projectScanner;
        this.environment = environment;

        var cacheProps = properties.cache();
        int maxProjects = cacheProps != null ? cacheProps.maxProjects() : 5;
        int ttlMinutes = cacheProps != null ? cacheProps.ttlMinutes() : 30;

        this.modelCache = Caffeine.newBuilder()
            .maximumSize(maxProjects)
            .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
            .build();

        this.resultCache = Caffeine.newBuilder()
            .maximumSize(maxProjects * 10L)
            .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
            .build();
    }

    public ServiceModel getOrScan(Path projectPath) {
        var cacheProps = properties.cache();
        boolean enabled = cacheProps != null && cacheProps.enabled();

        if (!enabled) {
            log.debug("ServiceModel cache is disabled. Scanning directly.");
            return projectScanner.scan(projectPath);
        }

        String canonicalPath = projectPath.toAbsolutePath().normalize().toString();
        String scanConfigHash = properties.maxFilesScanned() + ":" + properties.maxFileSizeBytes();
        String cacheKey = canonicalPath + "@" + scanConfigHash;

        long startFp = System.currentTimeMillis();
        String currentFingerprint = ProjectTreeFingerprinter.computeFingerprint(projectPath, properties.maxFilesScanned());
        long fpDuration = System.currentTimeMillis() - startFp;

        CachedServiceModel cached = modelCache.getIfPresent(cacheKey);
        if (cached != null && cached.treeFingerprint().equals(currentFingerprint)) {
            String shortFp = currentFingerprint.substring(0, Math.min(8, currentFingerprint.length()));
            log.info("ServiceModel cache HIT for project '{}' (fp={}, fpDuration={}ms)",
                projectPath.getFileName(), shortFp, fpDuration);
            return cached.model();
        }

        long startScan = System.currentTimeMillis();
        log.info("ServiceModel cache MISS for project '{}' (fpDuration={}ms). Scanning project...",
            projectPath.getFileName(), fpDuration);
        ServiceModel scannedModel = projectScanner.scan(projectPath);
        long scanDuration = System.currentTimeMillis() - startScan;

        modelCache.put(cacheKey, new CachedServiceModel(currentFingerprint, scannedModel, System.currentTimeMillis()));
        log.info("ServiceModel cached for project '{}' (classes={}, scanDuration={}ms)",
            projectPath.getFileName(), scannedModel.classes().size(), scanDuration);

        return scannedModel;
    }

    public DiagramAgent.DiagramResult getCachedResult(
        Path projectPath,
        DiagramType type,
        String entryPoint,
        int maxDepth
    ) {
        var cacheProps = properties.cache();
        if (cacheProps == null || !cacheProps.enabled() || !cacheProps.results()) {
            return null;
        }

        String fingerprint = ProjectTreeFingerprinter.computeFingerprint(projectPath, properties.maxFilesScanned());
        String modelName = environment.getProperty("diagram.model",
            environment.getProperty("spring.ai.google.genai.chat.options.model", "gemini-default"));

        DiagramResultCacheKey key = new DiagramResultCacheKey(
            fingerprint,
            type,
            entryPoint != null ? entryPoint.trim() : "",
            maxDepth,
            modelName,
            PROMPT_VERSION
        );

        DiagramAgent.DiagramResult cached = resultCache.getIfPresent(key);
        if (cached != null) {
            log.info("Diagram result cache HIT for type={} entryPoint='{}'", type, entryPoint);
            return new DiagramAgent.DiagramResult(
                cached.type(),
                cached.mermaid(),
                cached.valid(),
                cached.attempts(),
                cached.warnings(),
                true
            );
        }
        return null;
    }

    public void putResult(
        Path projectPath,
        DiagramType type,
        String entryPoint,
        int maxDepth,
        DiagramAgent.DiagramResult result
    ) {
        var cacheProps = properties.cache();
        if (cacheProps == null || !cacheProps.enabled() || !cacheProps.results()) {
            return;
        }

        String fingerprint = ProjectTreeFingerprinter.computeFingerprint(projectPath, properties.maxFilesScanned());
        String modelName = environment.getProperty("diagram.model",
            environment.getProperty("spring.ai.google.genai.chat.options.model", "gemini-default"));

        DiagramResultCacheKey key = new DiagramResultCacheKey(
            fingerprint,
            type,
            entryPoint != null ? entryPoint.trim() : "",
            maxDepth,
            modelName,
            PROMPT_VERSION
        );

        resultCache.put(key, result);
        log.info("Diagram result cached for type={} entryPoint='{}'", type, entryPoint);
    }

    public void clear() {
        modelCache.invalidateAll();
        resultCache.invalidateAll();
        log.info("ServiceModel and DiagramResult caches cleared.");
    }

    public long modelCacheSize() {
        return modelCache.estimatedSize();
    }

    public long resultCacheSize() {
        return resultCache.estimatedSize();
    }

    public void cleanUp() {
        modelCache.cleanUp();
        resultCache.cleanUp();
    }
}
