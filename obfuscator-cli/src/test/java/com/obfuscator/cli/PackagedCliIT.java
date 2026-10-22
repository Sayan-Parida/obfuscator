package com.obfuscator.cli;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import com.obfuscator.engine.jvm.TestClasses;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the packaged artifact the way a user runs it: a fresh JVM, the
 * shaded JAR, real files. These run in failsafe ({@code mvn verify}) because
 * the JAR only exists after {@code package}.
 */
class PackagedCliIT {

    private static final Path CLI_JAR = Path.of(System.getProperty("basedir", "."))
            .toAbsolutePath()
            .resolve("target")
            .resolve("obfuscator-cli.jar");

    @TempDir
    Path tempDir;

    @Test
    void printsTheVersionFromThePackagedJar() throws Exception {
        ProcessResult result = runJar("--version");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).startsWith("obfuscator ");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void packagedJarContainsTheEngineButNoSpringOrWebClasses() throws Exception {
        assertThat(CLI_JAR).isRegularFile();

        try (JarFile jar = new JarFile(CLI_JAR.toFile())) {
            List<String> names = jar.stream().map(JarEntry::getName).toList();

            assertThat(names)
                    .contains(
                            "com/obfuscator/engine/jvm/MethodRenamingStep.class",
                            "com/obfuscator/domain/Artifact.class",
                            "picocli/CommandLine.class")
                    .noneMatch(name -> name.startsWith("org/springframework/"))
                    .noneMatch(name -> name.startsWith("com/obfuscator/api/"))
                    .noneMatch(name -> name.startsWith("com/obfuscator/ObfuscatorApplication"));

            assertThat(jar.getManifest().getMainAttributes().getValue("Main-Class"))
                    .isEqualTo("com.obfuscator.cli.ObfuscatorCli");
        }
    }

    @Test
    void obfuscatesAJarAndPreservesRuntimeBehaviour() throws Exception {
        byte[] classBytes = TestClasses.compile("demo.Tool", """
                package demo;

                public class Tool {
                    public static void main(String[] args) {
                        System.out.println(new Tool().message());
                    }

                    private String message() {
                        return "hello-from-the-packaged-cli";
                    }
                }
                """);
        Path input = CliTestSupport.jar(
                tempDir, "tool.jar", Map.of("demo/Tool.class", classBytes), "demo.Tool");
        Path output = tempDir.resolve("out/tool-obfuscated.jar");

        ProcessResult original = runJava("-jar", input.toString());
        assertThat(original.exitCode()).isZero();
        assertThat(original.out()).isEqualTo("hello-from-the-packaged-cli" + System.lineSeparator());

        ProcessResult cli = runJar("obfuscate", input.toString(), "-o", output.toString());
        assertThat(cli.exitCode()).isZero();
        assertThat(cli.err()).isEmpty();
        assertThat(cli.out()).contains("Status: VERIFIED", "obfuscated 1 class(es)");

        assertThat(output).exists();
        assertThat(TestClasses.methodNames(CliTestSupport.entries(output).get("demo/Tool.class")))
                .doesNotContain("message");

        ProcessResult obfuscated = runJava("-jar", output.toString());
        assertThat(obfuscated.exitCode()).isZero();
        assertThat(obfuscated.out()).isEqualTo(original.out());
    }

    @Test
    void rejectsUnsupportedInputFromThePackagedJar() throws Exception {
        Path input = CliTestSupport.write(tempDir, "notes.txt", "hello".getBytes(US_ASCII));
        Path output = tempDir.resolve("out.class");

        ProcessResult result = runJar("obfuscate", input.toString(), "-o", output.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCodes.UNSUPPORTED_INPUT);
        assertThat(result.err()).contains("unsupported input format");
    }

    @Test
    void refusesToOverwriteTheInputFromThePackagedJar() throws Exception {
        Path input = CliTestSupport.jar(
                tempDir, "tool.jar", Map.of("demo/App.class", TestClasses.compile("demo.App", """
                        package demo;

                        public class App {
                            private String value() {
                                return "value";
                            }
                        }
                        """)), null);
        byte[] before = Files.readAllBytes(input);

        ProcessResult result = runJar("obfuscate", input.toString(), "-o", input.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OUTPUT_REFUSED);
        assertThat(result.err()).contains("must not be the input file");
        assertThat(Files.readAllBytes(input)).isEqualTo(before);
    }

    private ProcessResult runJar(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-jar");
        command.add(CLI_JAR.toString());
        command.addAll(List.of(args));
        return run(command);
    }

    private ProcessResult runJava(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(List.of(args));
        return run(command);
    }

    private ProcessResult run(List<String> command) throws Exception {
        Path out = Files.createTempFile(tempDir, "stdout-", ".txt");
        Path err = Files.createTempFile(tempDir, "stderr-", ".txt");
        try {
            Process process = new ProcessBuilder(command)
                    .directory(tempDir.toFile())
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new AssertionError("timed out: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private record ProcessResult(int exitCode, String out, String err) {
    }
}
