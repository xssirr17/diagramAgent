package com.example.diagramagent.render;

import com.example.diagramagent.config.DiagramProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DiagramRenderer {

    private static final Logger log = LoggerFactory.getLogger(DiagramRenderer.class);

    private final DiagramProperties properties;
    private final ProcessExecutor processExecutor;

    public interface ProcessExecutor {
        ProcessResult execute(List<String> command, long timeoutSeconds) throws IOException, InterruptedException;
    }

    public record ProcessResult(int exitCode, String stdout, String stderr) {}

    public DiagramRenderer(DiagramProperties properties) {
        this(properties, new DefaultProcessExecutor());
    }

    public DiagramRenderer(DiagramProperties properties, ProcessExecutor processExecutor) {
        this.properties = properties;
        this.processExecutor = processExecutor;
    }

    public boolean isMmdcAvailable() {
        try {
            String cli = resolveMmdc();
            ProcessResult res = processExecutor.execute(List.of(cli, "--version"), 5);
            return res.exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public byte[] render(String mermaid, DiagramFormat format) {
        if (format == null || format == DiagramFormat.MERMAID) {
            throw new IllegalArgumentException("Cannot render image for format MERMAID");
        }
        if (mermaid == null || mermaid.isBlank()) {
            throw new IllegalArgumentException("Mermaid diagram content cannot be blank");
        }

        String cli = resolveMmdc();
        Path tempIn = null;
        Path tempOut = null;
        String ext = format == DiagramFormat.PNG ? ".png" : ".svg";

        try {
            tempIn = Files.createTempFile("render-in-", ".mmd");
            tempOut = Files.createTempFile("render-out-", ext);
            Files.writeString(tempIn, mermaid);

            List<String> command = new ArrayList<>();
            command.add(cli);
            command.add("-i");
            command.add(tempIn.toAbsolutePath().toString());
            command.add("-o");
            command.add(tempOut.toAbsolutePath().toString());

            String puppeteerConfig = properties.puppeteerConfigFile();
            if (puppeteerConfig != null && !puppeteerConfig.isBlank()) {
                Path p = Path.of(puppeteerConfig);
                if (Files.exists(p)) {
                    command.add("-p");
                    command.add(p.toAbsolutePath().toString());
                }
            }

            ProcessResult result;
            try {
                result = processExecutor.execute(command, 30);
            } catch (IOException e) {
                throw new MmdcNotAvailableException("Mermaid CLI (mmdc) is not available: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Diagram rendering interrupted", e);
            }

            if (result.exitCode() != 0) {
                String err = result.stderr();
                if (err == null || err.isBlank()) {
                    err = result.stdout();
                }
                throw new MmdcNotAvailableException("Mermaid CLI failed with exit code " + result.exitCode() + ": " + err);
            }

            if (!Files.exists(tempOut) || Files.size(tempOut) == 0) {
                throw new MmdcNotAvailableException("Mermaid CLI did not produce output image file.");
            }

            return Files.readAllBytes(tempOut);
        } catch (IOException e) {
            throw new RuntimeException("IO error during diagram rendering: " + e.getMessage(), e);
        } finally {
            if (tempIn != null) {
                try { Files.deleteIfExists(tempIn); } catch (IOException ignored) {}
            }
            if (tempOut != null) {
                try { Files.deleteIfExists(tempOut); } catch (IOException ignored) {}
            }
        }
    }

    public String resolveMmdc() {
        String cli = properties.mermaidCliPath();
        if (cli == null || cli.isBlank() || "mmdc".equals(cli)) {
            Path localBinWin = Path.of("node_modules", ".bin", "mmdc.cmd");
            Path localBin = Path.of("node_modules", ".bin", "mmdc");
            if (Files.exists(localBinWin)) {
                return localBinWin.toAbsolutePath().toString();
            } else if (Files.exists(localBin)) {
                return localBin.toAbsolutePath().toString();
            } else if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                return "mmdc.cmd";
            } else {
                return "mmdc";
            }
        }
        return cli;
    }

    public static class DefaultProcessExecutor implements ProcessExecutor {
        @Override
        public ProcessResult execute(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Process timed out after " + timeoutSeconds + " seconds: " + command.get(0));
            }

            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append("\n");
            }

            StringBuilder err = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) err.append(line).append("\n");
            }

            return new ProcessResult(process.exitValue(), out.toString().trim(), err.toString().trim());
        }
    }
}
