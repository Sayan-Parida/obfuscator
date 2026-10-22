package com.obfuscator.engine.jvm;

import static org.assertj.core.api.Assertions.assertThat;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.TransformationContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * Runtime evidence for the name-bound members the audit missed: a class whose
 * only constructor is private must still load and run, and serialization
 * hooks must stay name-callable from pre-obfuscation streams.
 */
class MethodRenamingRuntimeCompatibilityTests {

    private static final String GATE_CLASS = "demo.Gate";
    private static final String COIN_CLASS = "demo.Coin";
    private static final String MAIN_CLASS = "demo.Main";

    private static final String GATE_SOURCE = """
            package demo;

            public final class Gate {
                static final Gate ONLY = new Gate("open");

                private final String state;

                private Gate(String state) {
                    this.state = state;
                }

                public static Gate sole() {
                    return ONLY;
                }

                public String describe() {
                    return "gate-" + state;
                }

                public int toll(int amount) {
                    return charge(amount);
                }

                private int charge(int amount) {
                    return amount + 1;
                }
            }
            """;

    private static final String COIN_SOURCE = """
            package demo;

            import java.io.File;
            import java.io.FileInputStream;
            import java.io.FileOutputStream;
            import java.io.ObjectInputStream;
            import java.io.ObjectOutputStream;
            import java.io.Serializable;

            public class Coin implements Serializable {
                private static final Coin OFFICIAL = new Coin("gold");

                private final String metal;

                private Coin(String metal) {
                    this.metal = metal;
                }

                public static void main(String[] args) throws Exception {
                    File file = new File(args[0]);
                    if ("write".equals(args[1])) {
                        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file))) {
                            out.writeObject(OFFICIAL);
                        }
                        System.out.println("wrote");
                    } else {
                        Coin coin;
                        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
                            coin = (Coin) in.readObject();
                        }
                        System.out.println(coin == OFFICIAL ? "SINGLETON-OK" : "MISMATCH");
                    }
                }

                private Object readResolve() {
                    return OFFICIAL;
                }
            }
            """;

    private static final String MAIN_SOURCE = """
            package demo;

            public class Main {
                public static void main(String[] args) {
                    Gate gate = Gate.sole();
                    System.out.println(gate.describe());
                    System.out.println(gate.toll(4));
                    System.out.println(trim("  pad "));
                }

                private static String trim(String value) {
                    return value.trim();
                }
            }
            """;

    private final MethodRenamingStep step = new MethodRenamingStep();

    @Test
    void aClassWhoseOnlyConstructorIsPrivateStillLoadsAndRuns() throws Exception {
        byte[] original = fixtureJar();

        TransformationContext context = new TransformationContext(
                Artifact.of(ArtifactFormat.JAR, original), Map.of());
        Artifact obfuscated = step.apply(context);

        assertThat(context.functionality().status().name()).isEqualTo("VERIFIED");

        Path originalDir = extract(original, "original");
        Path obfuscatedDir = extract(obfuscated.bytes(), "obfuscated");
        try {
            String expected = run(originalDir, MAIN_CLASS);
            String actual = run(obfuscatedDir, MAIN_CLASS, "");

            assertThat(TestClasses.methodNames(readEntry(obfuscated.bytes(), "demo/Gate.class")))
                    .contains("describe", "<init>")
                    .doesNotContain("charge");
            assertThat(TestClasses.methodNames(readEntry(obfuscated.bytes(), "demo/Coin.class")))
                    .contains("readResolve", "<init>");

            assertThat(actual).isEqualTo(expected);
        } finally {
            deleteRecursively(originalDir);
            deleteRecursively(obfuscatedDir);
        }
    }

    @Test
    void serializationStreamsWrittenBeforeObfuscationStillResolve() throws Exception {
        byte[] original = fixtureJar();

        Artifact obfuscated = step.apply(new TransformationContext(
                Artifact.of(ArtifactFormat.JAR, original), Map.of()));

        Path originalDir = extract(original, "original");
        Path obfuscatedDir = extract(obfuscated.bytes(), "obfuscated");
        Path state = Files.createTempFile("coin-state", ".ser");
        try {
            run(originalDir, COIN_CLASS, state.toString(), "write");
            assertThat(run(originalDir, COIN_CLASS, state.toString(), "read")).isEqualTo("SINGLETON-OK");
            assertThat(run(obfuscatedDir, COIN_CLASS, state.toString(), "read")).isEqualTo("SINGLETON-OK");
        } finally {
            Files.deleteIfExists(state);
            deleteRecursively(originalDir);
            deleteRecursively(obfuscatedDir);
        }
    }

    private static byte[] fixtureJar() {
        byte[] main = TestClasses.compile(MAIN_CLASS, MAIN_SOURCE, GATE_SOURCE, COIN_SOURCE);
        byte[] gate = TestClasses.compile(GATE_CLASS, GATE_SOURCE);
        byte[] coin = TestClasses.compile(COIN_CLASS, COIN_SOURCE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            write(jar, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: demo.Main\r\n\r\n");
            write(jar, "demo/Main.class", main);
            write(jar, "demo/Gate.class", gate);
            write(jar, "demo/Coin.class", coin);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static void write(JarOutputStream jar, String name, String content) throws IOException {
        write(jar, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new ZipEntry(name));
        jar.write(content);
        jar.closeEntry();
    }

    private static byte[] readEntry(byte[] jar, String name) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return in.readAllBytes();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        throw new IllegalStateException("no such entry: " + name);
    }

    private static Path extract(byte[] jar, String prefix) throws IOException {
        Path dir = Files.createTempDirectory(prefix + "-jar-run");
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path target = dir.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                Files.write(target, in.readAllBytes());
            }
        }
        return dir;
    }

    private static String run(Path classpath, String mainClass, String... arguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(classpath.toString());
        command.add(mainClass);
        for (String argument : arguments) {
            if (!argument.isEmpty()) {
                command.add(argument);
            }
        }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int exitCode = process.waitFor();
        assertThat(exitCode)
                .as("JVM exited non-zero running %s%noutput:%n%s", mainClass, output)
                .isZero();
        return output;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup of a temp directory
                }
            });
        }
    }
}
