package com.obfuscator.cli;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.obfuscator.cli.CliTestSupport.Invocation;
import com.obfuscator.engine.jvm.TestClasses;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ObfuscateCommandTests {

    private static final byte[] CLASS_BYTES = CliTestSupport.compiledClass();

    @TempDir
    Path tempDir;

    @Test
    void obfuscatesAClassAndPrintsTheSummary() throws Exception {
        Path input = CliTestSupport.write(tempDir, "input/App.class", CLASS_BYTES);
        Path output = tempDir.resolve("out/App.class");

        Invocation result = CliTestSupport.run(
                "obfuscate", input.toString(), "-o", output.toString(), "--steps", "method-renaming");

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(output).exists();

        List<String> names = TestClasses.methodNames(Files.readAllBytes(output));
        assertThat(names).contains("<init>", "call").doesNotContain("secret");

        assertThat(result.out())
                .contains("Input:", "JVM_CLASS")
                .contains("Steps:  method-renaming")
                .contains("renamed 1 method(s)")
                .contains("Status: VERIFIED")
                .contains("runtime not executed");
    }

    @Test
    void obfuscatesAJarAndCopiesNonClassEntriesUnchanged() throws Exception {
        byte[] note = "keep me".getBytes(US_ASCII);
        Path input = CliTestSupport.jar(
                tempDir,
                "app.jar",
                Map.of("demo/App.class", CLASS_BYTES, "resources/note.txt", note),
                null);
        Path output = tempDir.resolve("out/app.jar");

        Invocation result = CliTestSupport.run("obfuscate", input.toString(), "-o", output.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).contains("obfuscated 1 class(es)", "Status: VERIFIED");

        Map<String, byte[]> entries = CliTestSupport.entries(output);
        assertThat(entries).containsKeys("demo/App.class", "resources/note.txt");
        assertThat(entries.get("resources/note.txt")).isEqualTo(note);
        assertThat(TestClasses.methodNames(entries.get("demo/App.class"))).doesNotContain("secret");
    }

    @Test
    void honoursKeepRules() throws Exception {
        Path input = CliTestSupport.write(tempDir, "App.class", CLASS_BYTES);
        Path output = tempDir.resolve("kept.class");

        Invocation result = CliTestSupport.run(
                "obfuscate", input.toString(), "-o", output.toString(), "--keep", "demo.App#secret");

        assertThat(result.exitCode()).isZero();
        assertThat(TestClasses.methodNames(Files.readAllBytes(output))).contains("secret");
    }

    @Test
    void rejectsUnsupportedInputFormats() throws Exception {
        Path input = CliTestSupport.write(tempDir, "notes.txt", "hello".getBytes(US_ASCII));
        Path output = tempDir.resolve("out.class");

        Invocation result = CliTestSupport.run("obfuscate", input.toString(), "-o", output.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCodes.UNSUPPORTED_INPUT);
        assertThat(result.err()).contains("unsupported input format", ".class and .jar");
        assertThat(output).doesNotExist();
    }

    @Test
    void rejectsMissingInput() {
        Path output = tempDir.resolve("out.class");

        Invocation result = CliTestSupport.run(
                "obfuscate", tempDir.resolve("absent.class").toString(), "-o", output.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCodes.FAILURE);
        assertThat(result.err()).contains("input does not exist");
        assertThat(output).doesNotExist();
    }

    @Test
    void refusesToOverwriteTheInput() throws Exception {
        Path input = CliTestSupport.write(tempDir, "App.class", CLASS_BYTES);
        byte[] before = Files.readAllBytes(input);

        Invocation result = CliTestSupport.run("obfuscate", input.toString(), "-o", input.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCodes.OUTPUT_REFUSED);
        assertThat(result.err()).contains("must not be the input file");
        assertThat(Files.readAllBytes(input)).isEqualTo(before);
    }

    @Test
    void refusesAnExistingOutputWithoutForceThenOverwritesWithForce() throws Exception {
        Path input = CliTestSupport.write(tempDir, "App.class", CLASS_BYTES);
        Path output = CliTestSupport.write(tempDir, "existing.class", new byte[] {1, 2, 3});

        Invocation refused = CliTestSupport.run(
                "obfuscate", input.toString(), "-o", output.toString());

        assertThat(refused.exitCode()).isEqualTo(ExitCodes.OUTPUT_REFUSED);
        assertThat(refused.err()).contains("use --force");
        assertThat(Files.readAllBytes(output)).isEqualTo(new byte[] {1, 2, 3});

        Invocation forced = CliTestSupport.run(
                "obfuscate", input.toString(), "-o", output.toString(), "--force");

        assertThat(forced.exitCode()).isZero();
        assertThat(TestClasses.methodNames(Files.readAllBytes(output))).doesNotContain("secret");
    }

    @Test
    void rejectsUnknownTransformationSteps() throws Exception {
        Path input = CliTestSupport.write(tempDir, "App.class", CLASS_BYTES);
        Path output = tempDir.resolve("out.class");

        Invocation result = CliTestSupport.run(
                "obfuscate", input.toString(), "-o", output.toString(), "--steps", "does-not-exist");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.FAILURE);
        assertThat(result.err()).contains("unknown transformation step: does-not-exist");
        assertThat(output).doesNotExist();
    }

    @Test
    void rejectsMalformedKeepRules() throws Exception {
        Path input = CliTestSupport.write(tempDir, "App.class", CLASS_BYTES);
        Path output = tempDir.resolve("out.class");

        Invocation result = CliTestSupport.run(
                "obfuscate", input.toString(), "-o", output.toString(), "--keep", "two#hashes#here");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.FAILURE);
        assertThat(result.err()).contains("invalid keepMethods rule");
        assertThat(output).doesNotExist();
    }

    @Test
    void enforcesTheInputSizeCap() throws Exception {
        Path input = CliTestSupport.write(tempDir, "App.class", CLASS_BYTES);
        Path output = tempDir.resolve("out.class");

        Invocation result = CliTestSupport.run(
                "obfuscate", input.toString(), "-o", output.toString(), "--max-input-bytes", "16");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.FAILURE);
        assertThat(result.err()).contains("max size");
        assertThat(output).doesNotExist();
    }

    @Test
    void enforcesTheArchiveEntryExpansionCap() throws Exception {
        Path input = CliTestSupport.jar(
                tempDir,
                "expanding.jar",
                Map.of("demo/App.class", CLASS_BYTES, "resources/big.bin", new byte[16 * 1024]),
                null);
        Path output = tempDir.resolve("out.jar");

        Invocation result = CliTestSupport.run(
                "obfuscate", input.toString(), "-o", output.toString(),
                "--max-archive-entry-bytes", "4096");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.FAILURE);
        assertThat(result.err()).contains("exceeds the maximum uncompressed size");
        assertThat(output).doesNotExist();
    }

    @Test
    void writesNoOutputWhenVerificationFails() throws Exception {
        Path input = CliTestSupport.write(tempDir, "Broken.class", brokenClass());
        Path output = tempDir.resolve("out.class");

        Invocation result = CliTestSupport.run("obfuscate", input.toString(), "-o", output.toString());

        assertThat(result.exitCode()).isEqualTo(ExitCodes.FAILURE);
        assertThat(result.err()).contains("failed verification");
        assertThat(output).doesNotExist();
    }

    @Test
    void warnsWhenTheOutputExtensionDoesNotMatchTheInput() throws Exception {
        Path input = CliTestSupport.write(tempDir, "App.class", CLASS_BYTES);
        Path output = tempDir.resolve("result.bin");

        Invocation result = CliTestSupport.run("obfuscate", input.toString(), "-o", output.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Warning: output does not end with .class");
    }

    @Test
    void reportsUnverifiedArchivesWithoutFailing() throws Exception {
        Path input = CliTestSupport.jar(
                tempDir, "resources.jar", Map.of("resources/note.txt", "hello".getBytes(UTF_8)), null);
        Path output = tempDir.resolve("out.jar");

        Invocation result = CliTestSupport.run("obfuscate", input.toString(), "-o", output.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Status: UNVERIFIED", "not verified");
        assertThat(output).exists();
    }

    private static byte[] brokenClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Broken", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "boom", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
