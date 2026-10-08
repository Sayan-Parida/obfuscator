package com.obfuscator.engine.jvm;

import static org.assertj.core.api.Assertions.assertThat;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.FunctionalityReport;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ClassStructureVerifierTests {

    private static final String SUBJECT = "com.example.Shape";
    private static final String SOURCE = """
            package com.example;

            public class Shape {
                public int area(int width, int height) {
                    return privateScale(width, height);
                }

                private int privateScale(int width, int height) {
                    return width * height;
                }
            }
            """;

    private final MethodRenaming renaming = new MethodRenaming();
    private final ClassStructureVerifier verifier = new ClassStructureVerifier();

    @Test
    void acceptsAValidRenameOnlyRewrite() {
        byte[] original = TestClasses.compile(SUBJECT, SOURCE);
        byte[] obfuscated = renaming.apply(
                        Artifact.of(ArtifactFormat.JVM_CLASS, original), KeepNames.none())
                .output()
                .bytes();

        FunctionalityReport report = verifier.verify(original, obfuscated);

        assertThat(report.status()).isEqualTo(FunctionalityReport.Status.VERIFIED);
    }

    @Test
    void rejectsOutputThatIsNotValidBytecode() {
        byte[] original = TestClasses.compile(SUBJECT, SOURCE);
        byte[] corrupt = TestClasses.compile(SUBJECT, SOURCE);
        corrupt[corrupt.length - 1] = (byte) 0xFF;

        FunctionalityReport report = verifier.verify(original, corrupt);

        assertThat(report.status()).isEqualTo(FunctionalityReport.Status.FAILED);
        assertThat(report.detail()).isNotBlank();
    }

    @Test
    void rejectsBytecodeWithAnInconsistentStack() {
        byte[] original = TestClasses.compile(SUBJECT, SOURCE);
        byte[] broken = classWithStackUnderflow();

        FunctionalityReport report = verifier.verify(original, broken);

        assertThat(report.status()).isEqualTo(FunctionalityReport.Status.FAILED);
        assertThat(report.detail()).contains("invalid bytecode");
    }

    private static byte[] classWithStackUnderflow() {
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

    @Test
    void rejectsAChangeBeyondMethodNames() {
        byte[] original = TestClasses.compile(SUBJECT, SOURCE);
        byte[] different = TestClasses.compile("com.example.Other", """
                package com.example;

                public class Other {
                    public int area(int width, int height) {
                        return width * height * 2;
                    }

                    private int privateScale(int width, int height) {
                        return width * height;
                    }
                }
                """);

        FunctionalityReport report = verifier.verify(original, different);

        assertThat(report.status()).isEqualTo(FunctionalityReport.Status.FAILED);
        assertThat(report.detail()).contains("structure changed");
    }

    @Test
    void structureIgnoresMethodNames() {
        byte[] original = TestClasses.compile(SUBJECT, SOURCE);
        byte[] obfuscated = renaming.apply(
                        Artifact.of(ArtifactFormat.JVM_CLASS, original), KeepNames.none())
                .output()
                .bytes();

        assertThat(verifier.structureOf(original))
                .containsExactlyElementsOf(verifier.structureOf(obfuscated));
    }
}