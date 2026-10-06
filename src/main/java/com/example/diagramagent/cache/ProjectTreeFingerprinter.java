package com.example.diagramagent.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

public class ProjectTreeFingerprinter {

    public static String computeFingerprint(Path rootPath, int maxFiles) {
        Path scanRoot = rootPath.resolve("src/main/java");
        if (!Files.exists(scanRoot) || !Files.isDirectory(scanRoot)) {
            scanRoot = rootPath;
        }

        List<String> fileEntries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(scanRoot)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .sorted()
                .limit(maxFiles)
                .forEach(file -> {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                        Path rel = rootPath.relativize(file);
                        String normalizedPath = rel.toString().replace('\\', '/');
                        long size = attrs.size();
                        long mtime = attrs.lastModifiedTime().toMillis();
                        fileEntries.add(normalizedPath + ":" + size + ":" + mtime);
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException e) {
            return "error-" + System.currentTimeMillis();
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String entry : fileEntries) {
                digest.update(entry.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
