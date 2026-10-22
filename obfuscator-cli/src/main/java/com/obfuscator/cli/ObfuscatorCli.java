package com.obfuscator.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Entry point for {@code java -jar obfuscator-cli.jar}.
 *
 * <p>Only JVM class files and JAR archives are supported. There is no native
 * binary or Android DEX/APK support.
 */
@Command(
        name = "obfuscator",
        mixinStandardHelpOptions = true,
        versionProvider = BuildVersionProvider.class,
        description = "Obfuscates JVM class files and JAR archives while preserving behaviour.",
        subcommands = ObfuscateCommand.class)
public final class ObfuscatorCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        System.exit(createCommandLine().execute(args));
    }

    /** Builds the fully wired command line; tests use this too. */
    public static CommandLine createCommandLine() {
        CommandLine commandLine = new CommandLine(new ObfuscatorCli());
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            command.getErr().println("error: " + message);
            return ExitCodes.FAILURE;
        });
        return commandLine;
    }

    @Override
    public Integer call() {
        spec.commandLine().getErr().println("error: missing required subcommand");
        spec.commandLine().usage(spec.commandLine().getErr());
        return ExitCodes.USAGE;
    }
}
