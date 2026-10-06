package com.example.diagramagent.diff;

import com.example.diagramagent.config.DiagramProperties;
import com.example.diagramagent.scan.ProjectScanner;
import com.example.diagramagent.scan.ServiceModel;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    private final ProjectScanner projectScanner;
    private final DiagramProperties properties;

    public GitService(ProjectScanner projectScanner, DiagramProperties properties) {
        this.projectScanner = projectScanner;
        this.properties = properties;
    }

    public ServiceModel extractModelAtRef(Path projectPath, String rawRef) {
        String safeRef = GitRefValidator.validate(rawRef);

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repo;
        try {
            repo = builder.findGitDir(projectPath.toFile()).build();
        } catch (IOException e) {
            throw new NotAGitRepositoryException("Failed to access git repo at: " + projectPath);
        }

        if (repo == null || repo.getDirectory() == null || !repo.getDirectory().exists()) {
            throw new NotAGitRepositoryException("Directory is not inside a git repository: " + projectPath);
        }

        try (repo) {
            ObjectId commitId = repo.resolve(safeRef);
            if (commitId == null) {
                throw new UnknownGitRefException("Git ref could not be resolved: " + safeRef);
            }

            Path repoRoot;
            try {
                File workTree = repo.getWorkTree();
                repoRoot = workTree != null ? workTree.toPath().toRealPath() : projectPath.toRealPath();
            } catch (IOException e) {
                repoRoot = projectPath;
            }

            String filterPrefix = "";
            try {
                Path realProject = projectPath.toRealPath();
                if (!realProject.equals(repoRoot)) {
                    Path rel = repoRoot.relativize(realProject);
                    String s = rel.toString().replace('\\', '/');
                    if (!s.isEmpty() && !s.equals(".")) {
                        filterPrefix = s.endsWith("/") ? s : s + "/";
                    }
                }
            } catch (IOException ignored) {}

            Path tempDir = Files.createTempDirectory("git-diff-" + safeRef.replaceAll("[^a-zA-Z0-9]", "_") + "-");
            try {
                extractJavaBlobs(repo, commitId, filterPrefix, tempDir);
                return projectScanner.scan(tempDir);
            } finally {
                deleteDirectoryRecursively(tempDir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading git repository at ref '" + safeRef + "': " + e.getMessage(), e);
        }
    }

    private void extractJavaBlobs(Repository repo, ObjectId commitId, String filterPrefix, Path tempDir) throws IOException {
        try (RevWalk revWalk = new RevWalk(repo)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            RevTree tree = commit.getTree();
            try (TreeWalk treeWalk = new TreeWalk(repo)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);

                int count = 0;
                while (treeWalk.next()) {
                    String pathString = treeWalk.getPathString();
                    if (!pathString.endsWith(".java")) {
                        continue;
                    }
                    if (!filterPrefix.isEmpty() && !pathString.startsWith(filterPrefix)) {
                        continue;
                    }

                    count++;
                    if (count > properties.maxFilesScanned()) {
                        log.warn("Max files limit ({}) reached when extracting ref blobs", properties.maxFilesScanned());
                        break;
                    }

                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repo.open(objectId);
                    if (loader.getSize() > properties.maxFileSizeBytes()) {
                        continue;
                    }

                    String subPath = filterPrefix.isEmpty() ? pathString : pathString.substring(filterPrefix.length());
                    Path targetFile = tempDir.resolve(subPath);
                    if (targetFile.getParent() != null) {
                        Files.createDirectories(targetFile.getParent());
                    }
                    try (OutputStream out = Files.newOutputStream(targetFile)) {
                        loader.copyTo(out);
                    }
                }
            }
        }
    }

    private void deleteDirectoryRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }
}
