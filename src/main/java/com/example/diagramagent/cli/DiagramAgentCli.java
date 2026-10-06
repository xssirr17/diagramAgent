package com.example.diagramagent.cli;

import com.example.diagramagent.agent.DiagramAgent;
import com.example.diagramagent.api.InsufficientInformationException;
import com.example.diagramagent.api.InvalidPathException;
import com.example.diagramagent.api.ProviderException;
import com.example.diagramagent.api.RateLimitExceededException;
import com.example.diagramagent.cache.ServiceModelCache;
import com.example.diagramagent.diff.GitService;
import com.example.diagramagent.diff.InvalidGitRefException;
import com.example.diagramagent.diff.ModelDiffer;
import com.example.diagramagent.diff.NotAGitRepositoryException;
import com.example.diagramagent.diff.UnknownGitRefException;
import com.example.diagramagent.render.DiagramRenderer;
import com.example.diagramagent.security.PathGuard;
import com.example.diagramagent.validate.MermaidValidator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

@Component
@Profile("cli")
public class DiagramAgentCli implements CommandLineRunner, ExitCodeGenerator {

    private final PathGuard pathGuard;
    private final ServiceModelCache serviceModelCache;
    private final DiagramAgent diagramAgent;
    private final DiagramRenderer diagramRenderer;
    private final GitService gitService;
    private final ModelDiffer modelDiffer;
    private final MermaidValidator mermaidValidator;

    private int exitCode = 0;

    public DiagramAgentCli(
        PathGuard pathGuard,
        ServiceModelCache serviceModelCache,
        DiagramAgent diagramAgent,
        DiagramRenderer diagramRenderer,
        GitService gitService,
        ModelDiffer modelDiffer,
        MermaidValidator mermaidValidator
    ) {
        this.pathGuard = pathGuard;
        this.serviceModelCache = serviceModelCache;
        this.diagramAgent = diagramAgent;
        this.diagramRenderer = diagramRenderer;
        this.gitService = gitService;
        this.modelDiffer = modelDiffer;
        this.mermaidValidator = mermaidValidator;
    }

    @Override
    public void run(String... args) {
        DiagramAgentCommand root = new DiagramAgentCommand(
            pathGuard,
            serviceModelCache,
            diagramAgent,
            diagramRenderer,
            gitService,
            modelDiffer,
            mermaidValidator
        );

        CommandLine cmd = new CommandLine(root);
        cmd.addSubcommand("generate", new DiagramAgentCommand.GenerateCommand(root));
        cmd.addSubcommand("endpoints", new DiagramAgentCommand.EndpointsCommand(root));
        cmd.addSubcommand("diff", new DiagramAgentCommand.DiffCommand(root));
        cmd.addSubcommand("validate", new DiagramAgentCommand.ValidateCommand(root));

        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            int code = mapExceptionToExitCode(ex);
            System.err.println("Error: " + ex.getMessage());
            return code;
        });

        cmd.setParameterExceptionHandler((ex, args1) -> {
            System.err.println("Argument Error: " + ex.getMessage());
            CommandLine.usage(ex.getCommandLine(), System.err);
            return 2;
        });

        this.exitCode = cmd.execute(args);
    }

    public int runWithArgs(String... args) {
        run(args);
        return this.exitCode;
    }

    public static int mapExceptionToExitCode(Throwable ex) {
        if (ex instanceof InvalidPathException
            || ex instanceof InvalidGitRefException
            || ex instanceof NotAGitRepositoryException
            || ex instanceof UnknownGitRefException
            || ex instanceof IllegalArgumentException
            || ex instanceof CommandLine.ParameterException) {
            return 2;
        }
        if (ex instanceof InsufficientInformationException) {
            return 3;
        }
        if (ex instanceof ProviderException
            || ex instanceof RateLimitExceededException) {
            return 4;
        }
        if (ex instanceof DiagramValidationException) {
            return 5;
        }
        return 1;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
