package com.obfuscator.cli;

import com.obfuscator.application.ObfuscationService;
import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.FunctionalityReport;
import com.obfuscator.domain.ObfuscationException;
import com.obfuscator.domain.ObfuscationRequest;
import com.obfuscator.domain.ObfuscationResult;
import com.obfuscator.domain.TransformationLog;
import com.obfuscator.engine.StepRegistry;
import com.obfuscator.engine.jvm.JarArchive;
import com.obfuscator.engine.jvm.MethodRenamingStep;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Obfuscates one input file with the Spring-free core and writes the result
 * atomically. The input is never overwritten, and an existing output file is
 * only replaced with {@code --force}.
 */
@Command(
        name = "obfuscate",
        mixinStandardHelpOptions = true,
        versionProvider = BuildVersionProvider.class,
        description = "Obfuscate a JVM .class file or .jar archive.")
public final class ObfuscateCommand implements Callable<Integer> {

    private static final String KEEP_OPTION = "keepMethods";

    @Parameters(index = "0", paramLabel = "<input>", description = "JVM .class file or .jar archive to obfuscate.")
    private Path input;

    @Option(
            names = {"-o", "--output"},
            required = true,
            paramLabel = "<path>",
            description = "Where to write the obfuscated artifact.")
    private Path output;

    @Option(
            names = "--steps",
            split = ",",
            paramLabel = "<id>",
            description = "Transformation steps to run, comma-separated (default: all applicable).")
    private List<String> steps = new ArrayList<>();

    @Option(
            names = {"-k", "--keep"},
            paramLabel = "<Name|Owner#method>",
            description = "Keep a method name; repeat for several rules.")
    private List<String> keepMethods = new ArrayList<>();

    @Option(names = {"-f", "--force"}, description = "Overwrite an existing output file.")
    private boolean force;

    @Option(
            names = "--max-input-bytes",
            paramLabel = "<bytes>",
            defaultValue = "" + ObfuscationService.DEFAULT_MAX_INPUT_BYTES,
            description = "Reject inputs larger than this.")
    private long maxInputBytes;

    @Option(
            names = "--max-archive-entry-bytes",
            paramLabel = "<bytes>",
            defaultValue = "" + JarArchive.DEFAULT_MAX_ENTRY_BYTES,
            description = "Maximum inflated size of one JAR entry.")
    private long maxArchiveEntryBytes;

    @Option(
            names = "--max-archive-expanded-bytes",
            paramLabel = "<bytes>",
            defaultValue = "" + JarArchive.DEFAULT_MAX_EXPANDED_BYTES,
            description = "Maximum total inflated size of a JAR.")
    private long maxArchiveExpandedBytes;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            Path source = input.toAbsolutePath().normalize();
            Path target = output.toAbsolutePath().normalize();

            requireReadableInput(source);
            ArtifactFormat format = formatOf(source);
            requireSafeTarget(source, target);
            requirePositiveLimits();

            byte[] bytes = readCapped(source, maxInputBytes);
            List<String> requestedSteps = requestedSteps();
            List<String> keepRules = keepMethods.stream().filter(rule -> !rule.isBlank()).toList();

            MethodRenamingStep step = new MethodRenamingStep(maxArchiveEntryBytes, maxArchiveExpandedBytes);
            StepRegistry registry = new StepRegistry(List.of(step));
            registry.requireKnown(requestedSteps);
            if (registry.resolve(requestedSteps, format).isEmpty()) {
                throw new ObfuscationException("no transformation supports format " + format
                        + (requestedSteps.isEmpty() ? "" : " for steps " + requestedSteps));
            }

            ObfuscationService service = new ObfuscationService(registry, maxInputBytes);
            Map<String, String> options =
                    keepRules.isEmpty() ? Map.of() : Map.of(KEEP_OPTION, String.join(",", keepRules));
            ObfuscationResult result = service.obfuscate(
                    new ObfuscationRequest(Artifact.of(format, bytes), requestedSteps, options));

            writeAtomically(target, result.output().bytes(), force);
            printSummary(out, source, target, format, bytes.length, result);
            return ExitCodes.OK;
        } catch (UnsupportedInputException e) {
            err.println("error: " + e.getMessage());
            return ExitCodes.UNSUPPORTED_INPUT;
        } catch (OutputRefusedException e) {
            err.println("error: " + e.getMessage());
            return ExitCodes.OUTPUT_REFUSED;
        } catch (ObfuscationException e) {
            err.println("error: " + e.reason());
            return ExitCodes.FAILURE;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return ExitCodes.FAILURE;
        } catch (IOException e) {
            err.println("error: I/O failure: " + e.getMessage());
            return ExitCodes.FAILURE;
        }
    }

    private static void requireReadableInput(Path source) {
        if (!Files.exists(source)) {
            throw new ObfuscationException("input does not exist: " + source);
        }
        if (!Files.isRegularFile(source)) {
            throw new ObfuscationException("input is not a regular file: " + source);
        }
        if (!Files.isReadable(source)) {
            throw new ObfuscationException("input is not readable: " + source);
        }
    }

    private static ArtifactFormat formatOf(Path source) {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".class")) {
            return ArtifactFormat.JVM_CLASS;
        }
        if (name.endsWith(".jar")) {
            return ArtifactFormat.JAR;
        }
        throw new UnsupportedInputException("unsupported input format: " + source.getFileName()
                + " (supported: .class and .jar; native binaries and Android DEX/APK archives are not supported)");
    }

    private void requireSafeTarget(Path source, Path target) throws IOException {
        if (source.equals(target)) {
            throw new OutputRefusedException("output must not be the input file: " + target);
        }
        if (!Files.exists(target)) {
            return;
        }
        if (Files.isSameFile(source, target)) {
            throw new OutputRefusedException("output must not be the input file: " + target);
        }
        if (Files.isDirectory(target)) {
            throw new OutputRefusedException("output is a directory: " + target);
        }
        if (!force) {
            throw new OutputRefusedException("output already exists (use --force to overwrite): " + target);
        }
    }

    private void requirePositiveLimits() {
        if (maxInputBytes <= 0) {
            throw new ObfuscationException("--max-input-bytes must be positive");
        }
        if (maxInputBytes > Integer.MAX_VALUE - 8L) {
            throw new ObfuscationException(
                    "--max-input-bytes must not exceed " + (Integer.MAX_VALUE - 8L) + " bytes");
        }
    }

    private List<String> requestedSteps() {
        return steps.stream()
                .filter(step -> !step.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static byte[] readCapped(Path source, long maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new ObfuscationException("input exceeds max size of " + maxBytes + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static void writeAtomically(Path target, byte[] bytes, boolean force) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, ".obfuscator-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temp, bytes);
            move(temp, target, force);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void move(Path temp, Path target, boolean force) throws IOException {
        try {
            if (force) {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException e) {
            if (force) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, target);
            }
        } catch (FileAlreadyExistsException e) {
            throw new OutputRefusedException("output already exists (use --force to overwrite): " + target);
        }
    }

    private static void printSummary(
            PrintWriter out, Path source, Path target, ArtifactFormat format, int inputSize,
            ObfuscationResult result) {
        out.printf("Input:  %s (%s, %d bytes)%n", source, format, inputSize);
        out.printf("Output: %s (%s, %d bytes)%n", target, result.output().format(), result.output().size());
        out.printf("Steps:  %s%n",
                result.appliedSteps().isEmpty() ? "none" : String.join(", ", result.appliedSteps()));
        for (TransformationLog.Entry entry : result.logEntries()) {
            out.printf("  - %s: %s%n", entry.stepId(), entry.detail());
        }
        out.printf("Status: %s%n", result.functionality().status());
        out.printf("Detail: %s%n", result.functionality().detail());

        String expectedExtension = format == ArtifactFormat.JAR ? ".jar" : ".class";
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(expectedExtension)) {
            out.printf("Warning: output does not end with %s; the format comes from the input.%n",
                    expectedExtension);
        }
        if (result.functionality().status() == FunctionalityReport.Status.UNVERIFIED) {
            out.println("Warning: the result is not verified; inspect the detail above before using it.");
        }
    }
}
