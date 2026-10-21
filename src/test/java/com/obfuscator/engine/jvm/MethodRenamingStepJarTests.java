package com.obfuscator.engine.jvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.FunctionalityReport;
import com.obfuscator.domain.ObfuscationException;
import com.obfuscator.domain.TransformationContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class MethodRenamingStepJarTests {

    private final MethodRenamingStep step = new MethodRenamingStep();

    @Test
    void supportsJarAndClassFormats() {
        assertThat(step.supports()).containsExactlyInAnyOrder(ArtifactFormat.JVM_CLASS, ArtifactFormat.JAR);
    }

    @Test
    void reportsVerifiedWhenEveryClassTransformed() {
        byte[] jar = jarOf(Map.of(
                "com/example/Alpha.class", classWith("com.example.Alpha", "alphaHelper"),
                "com/example/beta/Beta.class", classWith("com.example.beta.Beta", "betaHelper"),
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".getBytes(),
                "demo/note.txt", "keep me".getBytes()));

        TransformationContext context = context(jar, Map.of());
        Artifact output = step.apply(context);

        assertThat(output.format()).isEqualTo(ArtifactFormat.JAR);
        assertThat(context.functionality().status()).isEqualTo(FunctionalityReport.Status.VERIFIED);

        Map<String, byte[]> entries = readJar(output.bytes());
        assertThat(TestClasses.methodNames(entries.get("com/example/Alpha.class"))).doesNotContain("alphaHelper");
        assertThat(TestClasses.methodNames(entries.get("com/example/beta/Beta.class"))).doesNotContain("betaHelper");
        assertThat(entries.get("demo/note.txt")).isEqualTo("keep me".getBytes());
        assertThat(entries.get("META-INF/MANIFEST.MF")).isEqualTo("Manifest-Version: 1.0\r\n".getBytes());
    }

    @Test
    void honoursKeepMethodsAcrossEveryClass() {
        byte[] jar = jarOf(Map.of(
                "com/example/Alpha.class", classWith("com.example.Alpha", "alphaHelper"),
                "com/example/Beta.class", classWith("com.example.Beta", "betaHelper")));

        TransformationContext context = context(jar, Map.of("keepMethods", "betaHelper"));
        Artifact output = step.apply(context);

        Map<String, byte[]> entries = readJar(output.bytes());
        assertThat(TestClasses.methodNames(entries.get("com/example/Beta.class"))).contains("betaHelper");
        assertThat(TestClasses.methodNames(entries.get("com/example/Alpha.class"))).doesNotContain("alphaHelper");
    }

    @Test
    void reportsUnverifiedAndNamesSkippedClasses() {
        byte[] jar = jarOf(Map.of(
                "com/example/Good.class", classWith("com.example.Good", "hidden"),
                "com/example/Broken.class", new byte[] {1, 2, 3}));

        TransformationContext context = context(jar, Map.of());
        Artifact output = step.apply(context);

        assertThat(context.functionality().status()).isEqualTo(FunctionalityReport.Status.UNVERIFIED);
        assertThat(context.functionality().detail()).contains("Broken.class");

        Map<String, byte[]> entries = readJar(output.bytes());
        assertThat(entries.get("com/example/Broken.class")).isEqualTo(new byte[] {1, 2, 3});
        assertThat(TestClasses.methodNames(entries.get("com/example/Good.class"))).doesNotContain("hidden");
    }

    @Test
    void reportsUnverifiedWhenNothingWasTransformed() {
        byte[] jar = jarOf(Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".getBytes(),
                "readme.txt", "no classes here".getBytes()));

        TransformationContext context = context(jar, Map.of());
        step.apply(context);

        assertThat(context.functionality().status()).isEqualTo(FunctionalityReport.Status.UNVERIFIED);
        assertThat(context.functionality().detail()).contains("no class files were transformed");
    }

    @Test
    void reportsUnverifiedWhenClassHasNothingToRename() {
        byte[] jar = jarOf(Map.of("com/example/Plain.class", classWith("com.example.Plain", null)));

        TransformationContext context = context(jar, Map.of());
        step.apply(context);

        assertThat(context.functionality().status()).isEqualTo(FunctionalityReport.Status.UNVERIFIED);
        assertThat(context.functionality().detail()).contains("no class files were transformed");
    }

    @Test
    void rejectsSignedJar() {
        byte[] jar = jarOf(Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".getBytes(),
                "META-INF/APP.SF", "signature".getBytes()));

        TransformationContext context = context(jar, Map.of());

        assertThatThrownBy(() -> step.apply(context))
                .isInstanceOf(ObfuscationException.class)
                .hasMessageContaining("signed JARs cannot be obfuscated");
    }

    @Test
    void rejectsInvalidJar() {
        TransformationContext context = context(new byte[] {1, 2, 3, 4}, Map.of());

        assertThatThrownBy(() -> step.apply(context))
                .isInstanceOf(ObfuscationException.class)
                .hasMessage("input is not a valid JAR file");
    }

    private static TransformationContext context(byte[] jar, Map<String, String> options) {
        return new TransformationContext(Artifact.of(ArtifactFormat.JAR, jar), options);
    }

    private static byte[] classWith(String qualifiedName, String privateMethodName) {
        String helper = privateMethodName == null
                ? ""
                : """

                            private int %s(int value) {
                                return value + 1;
                            }
                        """.formatted(privateMethodName);
        String call = privateMethodName == null
                ? "return 42;"
                : "return " + privateMethodName + "(1);";

        return TestClasses.compile(qualifiedName, """
                package %s;

                public class %s {
                    public int run() {
                        %s
                    }%s
                }
                """.formatted(
                qualifiedName.substring(0, qualifiedName.lastIndexOf('.')),
                qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
                call,
                helper));
    }

    private static byte[] jarOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new ZipEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> readJar(byte[] jar) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }
}