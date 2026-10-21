package com.obfuscator.engine.jvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.ObfuscationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class JarArchiveTests {

    private final JarArchive archive = new JarArchive();

    @Test
    void preservesNonClassResourcesAndManifest() {
        byte[] manifest = "Manifest-Version: 1.0\r\nMain-Class: demo.Main\r\n\r\n".getBytes();
        byte[] properties = "app.name=obfuscator\n".getBytes();
        byte[] binary = {0, 1, 2, 3, (byte) 0xFF, (byte) 0xFE};

        byte[] result = archive.rewrite(jarOf(Map.of(
                "META-INF/MANIFEST.MF", manifest,
                "demo/app.properties", properties,
                "images/logo.bin", binary))).jarBytes();

        Map<String, byte[]> entries = readJar(result);
        assertThat(entries).containsOnlyKeys("META-INF/MANIFEST.MF", "demo/app.properties", "images/logo.bin");
        assertThat(entries.get("META-INF/MANIFEST.MF")).isEqualTo(manifest);
        assertThat(entries.get("demo/app.properties")).isEqualTo(properties);
        assertThat(entries.get("images/logo.bin")).isEqualTo(binary);
    }

    @Test
    void handlesMultipleClassesInNestedPackages() {
        byte[] alpha = TestClasses.compile("com.example.alpha.Alpha", """
                package com.example.alpha;

                public class Alpha {
                    public int run() {
                        return alphaHelper(21);
                    }

                    private int alphaHelper(int value) {
                        return value * 2;
                    }
                }
                """);
        byte[] beta = TestClasses.compile("com.example.beta.Beta", """
                package com.example.beta;

                public class Beta {
                    public int run() {
                        return betaHelper(5);
                    }

                    private int betaHelper(int value) {
                        return value + 7;
                    }
                }
                """);
        byte[] deep = TestClasses.compile("com.example.alpha.deep.Deep", """
                package com.example.alpha.deep;

                public class Deep {
                    public int run() {
                        return deepHelper(3);
                    }

                    private int deepHelper(int value) {
                        return value * value;
                    }
                }
                """);

        byte[] result = archive.rewrite(jarOf(Map.of(
                "com/example/alpha/Alpha.class", alpha,
                "com/example/beta/Beta.class", beta,
                "com/example/alpha/deep/Deep.class", deep,
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".getBytes(),
                "demo/note.txt", "keep me".getBytes())), renamingTransform()).jarBytes();

        Map<String, byte[]> entries = readJar(result);
        assertThat(entries).containsKeys(
                "com/example/alpha/Alpha.class",
                "com/example/beta/Beta.class",
                "com/example/alpha/deep/Deep.class",
                "demo/note.txt");
        assertThat(entries.get("demo/note.txt")).isEqualTo("keep me".getBytes());

        assertThat(TestClasses.methodNames(entries.get("com/example/alpha/Alpha.class")))
                .doesNotContain("alphaHelper");
        assertThat(TestClasses.methodNames(entries.get("com/example/beta/Beta.class")))
                .doesNotContain("betaHelper");
        assertThat(TestClasses.methodNames(entries.get("com/example/alpha/deep/Deep.class")))
                .doesNotContain("deepHelper");
    }

    @Test
    void keepsJarValidWhenAClassCannotBeTransformed() {
        byte[] good = TestClasses.compile("com.example.Good", """
                package com.example;

                public class Good {
                    public int run() {
                        return hidden(4);
                    }

                    private int hidden(int value) {
                        return value + 1;
                    }
                }
                """);

        byte[] result = archive.rewrite(jarOf(Map.of(
                "com/example/Good.class", good,
                "com/example/Broken.class", new byte[] {1, 2, 3})), renamingTransform()).jarBytes();

        Map<String, byte[]> entries = readJar(result);
        assertThat(entries.get("com/example/Broken.class")).isEqualTo(new byte[] {1, 2, 3});
        assertThat(TestClasses.methodNames(entries.get("com/example/Good.class"))).doesNotContain("hidden");

        List<JarArchive.EntryResult> broken = archive.rewrite(
                        jarOf(Map.of("com/example/Broken.class", new byte[] {1, 2, 3})),
                        renamingTransform())
                .entries();
        assertThat(broken).hasSize(1);
        assertThat(broken.get(0).classFile()).isTrue();
        assertThat(broken.get(0).changed()).isFalse();
        assertThat(broken.get(0).skipped()).isTrue();
    }

    @Test
    void distinguishesLegitimateNoOpFromSkippedClasses() {
        byte[] noPrivateMethods = TestClasses.compile("com.example.Plain", """
                package com.example;

                public class Plain {
                    public int run() {
                        return 42;
                    }
                }
                """);

        JarArchive.Result result = archive.rewrite(
                jarOf(Map.of("com/example/Plain.class", noPrivateMethods)),
                (name, bytes) -> {
                    MethodRenaming.RenamingResult renamed =
                            new MethodRenaming().apply(Artifact.of(ArtifactFormat.JVM_CLASS, bytes), KeepNames.none());
                    boolean changed = !Arrays.equals(bytes, renamed.output().bytes());
                    return new JarArchive.Outcome(renamed.output().bytes(), changed, false,
                            changed ? "renamed" : "no private methods to rename");
                });

        JarArchive.EntryResult entry = result.entries().get(0);
        assertThat(entry.classFile()).isTrue();
        assertThat(entry.changed()).isFalse();
        assertThat(entry.skipped()).isFalse();
        assertThat(entry.note()).isEqualTo("no private methods to rename");
    }

    @Test
    void handlesJarWithoutClasses() {
        byte[] result = archive.rewrite(jarOf(Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".getBytes(),
                "readme.txt", "hello".getBytes()))).jarBytes();

        Map<String, byte[]> entries = readJar(result);
        assertThat(entries).containsOnlyKeys("META-INF/MANIFEST.MF", "readme.txt");
        assertThat(entries.get("readme.txt")).isEqualTo("hello".getBytes());
    }

    @Test
    void rejectsInvalidJar() {
        assertThatThrownBy(() -> archive.rewrite(new byte[] {1, 2, 3, 4, 5}))
                .isInstanceOf(ObfuscationException.class)
                .hasMessage("input is not a valid JAR file");
    }

    @Test
    void rejectsTruncatedJarMissingCentralDirectory() {
        byte[] valid = jarOf(Map.of("a.txt", "x".getBytes()));
        byte[] truncated = Arrays.copyOf(valid, valid.length - 22);

        assertThatThrownBy(() -> archive.rewrite(truncated))
                .isInstanceOf(ObfuscationException.class)
                .hasMessageContaining("missing central directory");
    }

    @Test
    void rejectsSignedJarCaseInsensitively() {
        byte[] signed = jarOf(Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".getBytes(),
                "META-INF/APP.SF", "fake signature".getBytes(),
                "META-INF/APP.RSA", "fake key".getBytes()));

        assertThatThrownBy(() -> archive.rewrite(signed))
                .isInstanceOf(ObfuscationException.class)
                .hasMessageContaining("signed JARs cannot be obfuscated");
    }

    @Test
    void rejectsDuplicateEntryNames() throws IOException {
        byte[] duplicate = rawZipOfDuplicates(List.of(
                Map.entry("dup.txt", "first".getBytes()),
                Map.entry("dup.txt", "second".getBytes())));

        assertThatThrownBy(() -> archive.rewrite(duplicate))
                .isInstanceOf(ObfuscationException.class)
                .hasMessageContaining("duplicate entry");
    }

    @Test
    void preservesStoredEntrySizeAndCrc() throws IOException {
        byte[] content = "stored payload".getBytes();
        CRC32 crc = new CRC32();
        crc.update(content);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            ZipEntry stored = new ZipEntry("data.bin");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(content.length);
            stored.setCompressedSize(content.length);
            stored.setCrc(crc.getValue());
            jar.putNextEntry(stored);
            jar.write(content);
            jar.closeEntry();
        }
        byte[] input = out.toByteArray();

        byte[] result = archive.rewrite(input).jarBytes();

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(result))) {
            ZipEntry entry = in.getNextEntry();
            assertThat(entry.getName()).isEqualTo("data.bin");
            assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(entry.getSize()).isEqualTo(content.length);
            assertThat(entry.getCrc()).isEqualTo(crc.getValue());
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    private static JarArchive.ClassTransform renamingTransform() {
        return (name, bytes) -> {
            MethodRenaming.RenamingResult renamed =
                    new MethodRenaming().apply(Artifact.of(ArtifactFormat.JVM_CLASS, bytes), KeepNames.none());
            boolean changed = !Arrays.equals(bytes, renamed.output().bytes());
            return new JarArchive.Outcome(renamed.output().bytes(), changed, false,
                    changed ? "renamed " + renamed.renamedCount() + " method(s)" : "no private methods to rename");
        };
    }

    private static byte[] jarOf(Map<String, byte[]> entries) {
        return jarOfEntries(new ArrayList<>(entries.entrySet()));
    }

    /**
     * Writes a raw ZIP that permits duplicate entry names, which
     * {@link java.util.zip.ZipOutputStream} refuses to emit.
     */
    private static byte[] rawZipOfDuplicates(List<Map.Entry<String, byte[]>> entries) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        DataOutputStream local = new DataOutputStream(body);
        DataOutputStream directory = new DataOutputStream(central);

        for (Map.Entry<String, byte[]> entry : entries) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] content = entry.getValue();
            CRC32 crc = new CRC32();
            crc.update(content);
            int offset = body.size();

            u32(local, 0x04034b50L);
            u16(local, 20);
            u16(local, 0);
            u16(local, ZipEntry.STORED);
            u16(local, 0);
            u16(local, 0);
            u32(local, crc.getValue());
            u32(local, content.length);
            u32(local, content.length);
            u16(local, name.length);
            u16(local, 0);
            local.write(name);
            local.write(content);

            u32(directory, 0x02014b50L);
            u16(directory, 20);
            u16(directory, 20);
            u16(directory, 0);
            u16(directory, ZipEntry.STORED);
            u16(directory, 0);
            u16(directory, 0);
            u32(directory, crc.getValue());
            u32(directory, content.length);
            u32(directory, content.length);
            u16(directory, name.length);
            u16(directory, 0);
            u16(directory, 0);
            u16(directory, 0);
            u16(directory, 0);
            u32(directory, 0);
            u32(directory, offset);
            directory.write(name);
        }

        int centralOffset = body.size();
        int centralSize = central.size();
        body.write(central.toByteArray());

        DataOutputStream end = new DataOutputStream(body);
        u32(end, 0x06054b50L);
        u16(end, 0);
        u16(end, 0);
        u16(end, entries.size());
        u16(end, entries.size());
        u32(end, centralSize);
        u32(end, centralOffset);
        u16(end, 0);

        return body.toByteArray();
    }

    private static void u16(DataOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void u32(DataOutputStream out, long value) throws IOException {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }

    private static byte[] jarOfEntries(List<Map.Entry<String, byte[]>> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries) {
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