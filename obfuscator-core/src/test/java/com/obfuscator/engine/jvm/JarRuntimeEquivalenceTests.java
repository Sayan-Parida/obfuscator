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
 * Executes the original and the obfuscated JAR contents with a real JVM and
 * compares their stdout. Structural checks prove nothing moved except names;
 * only this proves the program still behaves the same.
 */
class JarRuntimeEquivalenceTests {

    private static final String MAIN_CLASS = "demo.Main";
    private static final String FORMATTER_CLASS = "demo.internal.Formatter";
    private static final String MANIFEST = "Manifest-Version: 1.0\r\nMain-Class: demo.Main\r\n\r\n";
    private static final String PROPERTIES = "app.name=obfuscator\n";

    private static final String MAIN_SOURCE = """
            package demo;

            public class Main {
                public static void main(String[] args) {
                    System.out.println(compute(19.99, 5.0, 42.5));
                    System.out.println(describe("  gold  "));
                    System.out.println(demo.internal.Formatter.wrap("cross-class"));
                }

                private static double compute(double first, double second, double third) {
                    return first + second + third;
                }

                private static String describe(String label) {
                    return "[" + label.trim().toUpperCase() + "]";
                }
            }
            """;

    private static final String FORMATTER_SOURCE = """
            package demo.internal;

            public class Formatter {
                public static String wrap(String value) {
                    return wrapInner(value);
                }

                private static String wrapInner(String value) {
                    return "<<" + value + ">>";
                }
            }
            """;

    private final MethodRenamingStep step = new MethodRenamingStep();

    @Test
    void obfuscatedJarRunsAndProducesIdenticalOutput() throws Exception {
        byte[] original = fixtureJar();

        TransformationContext context = new TransformationContext(
                Artifact.of(ArtifactFormat.JAR, original), Map.of());
        Artifact obfuscated = step.apply(context);

        assertThat(context.functionality().status().name()).isEqualTo("VERIFIED");

        Path originalDir = extract(original, "original");
        Path obfuscatedDir = extract(obfuscated.bytes(), "obfuscated");
        try {
            String expected = runMain(originalDir, MAIN_CLASS);
            String actual = runMain(obfuscatedDir, MAIN_CLASS);

            assertThat(TestClasses.methodNames(readEntry(obfuscated.bytes(), "demo/Main.class")))
                    .doesNotContain("compute", "describe");
            assertThat(TestClasses.methodNames(readEntry(obfuscated.bytes(), "demo/internal/Formatter.class")))
                    .doesNotContain("wrapInner");

            assertThat(actual).isEqualTo(expected);
        } finally {
            deleteRecursively(originalDir);
            deleteRecursively(obfuscatedDir);
        }
    }

    @Test
    void obfuscatedJarStillShipsItsResources() {
        byte[] obfuscated = step.apply(new TransformationContext(
                Artifact.of(ArtifactFormat.JAR, fixtureJar()), Map.of())).bytes();

        assertThat(readEntry(obfuscated, "demo/app.properties"))
                .isEqualTo(PROPERTIES.getBytes(StandardCharsets.UTF_8));
        assertThat(readEntry(obfuscated, "META-INF/MANIFEST.MF"))
                .isEqualTo(MANIFEST.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code compile} returns only the primary class, so the second fixture is
     * compiled on its own; it does not reference {@code Main}, so the two
     * compilations are interchangeable.
     */
    private static byte[] fixtureJar() {
        byte[] main = TestClasses.compile(MAIN_CLASS, MAIN_SOURCE, FORMATTER_SOURCE);
        byte[] formatter = TestClasses.compile(FORMATTER_CLASS, FORMATTER_SOURCE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            write(jar, "META-INF/MANIFEST.MF", MANIFEST);
            write(jar, "demo/Main.class", main);
            write(jar, "demo/internal/Formatter.class", formatter);
            write(jar, "demo/app.properties", PROPERTIES);
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

    private static String runMain(Path classpath, String mainClass) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath.toString(), mainClass)
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