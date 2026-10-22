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

    /**
     * The shape a rename of {@code <init>} produces: a fresh object whose
     * constructor is invoked under a different name. A loading JVM rejects
     * this, which is exactly what the audit caught.
     */
    @Test
    void rejectsAConstructorReferenceThatIsNotNamedInit() {
        byte[] original = TestClasses.compile(SUBJECT, SOURCE);
        byte[] broken = classWithConstructorCalledUnderAnotherName();

        FunctionalityReport report = verifier.verify(original, broken);

        assertThat(report.status()).isEqualTo(FunctionalityReport.Status.FAILED);
        assertThat(report.detail()).containsAnyOf("invalid bytecode", "structure changed beyond method names");
    }

    private static byte[] classWithConstructorCalledUnderAnotherName() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Broken", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "Broken");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("value");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "Broken", "misplaced",
                "(Ljava/lang/String;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(3, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * The data-flow verifier must not become a false-failure machine for
     * legitimate class shapes.
     */
    @Test
    void dataFlowPassesRealisticClassShapes() {
        String[] fixtures = { SUBCLASS_SOURCE, INTERFACE_SOURCE, SWITCH_SOURCE, CATCH_SOURCE, GENERIC_SOURCE };
        for (String source : fixtures) {
            byte[] original = TestClasses.compile("com.example.Rich", source);
            byte[] renamed = renaming.apply(
                            Artifact.of(ArtifactFormat.JVM_CLASS, original), KeepNames.none())
                    .output()
                    .bytes();

            assertThat(verifier.verify(original, renamed).status())
                    .as("fixture version: %s", source.lines().findFirst())
                    .isEqualTo(FunctionalityReport.Status.VERIFIED);
        }
    }

    private static final String SUBCLASS_SOURCE = """
            package com.example;

            public class Rich extends java.util.AbstractList<Integer> {
                private final int seed;

                Rich(int seed) {
                    this.seed = seed;
                }

                @Override
                public Integer get(int index) {
                    return seed + index;
                }

                @Override
                public int size() {
                    return 2;
                }

                private int choose(int value) {
                    return value > 0 ? seed : -seed;
                }
            }
            """;

    private static final String INTERFACE_SOURCE = """
            package com.example;

            public class Rich {
                public String render(java.util.List<String> parts) {
                    return shaping(parts);
                }

                private String shaping(java.util.List<String> parts) {
                    return String.join("-", parts);
                }
            }
            """;

    private static final String SWITCH_SOURCE = """
            package com.example;

            public class Rich {
                public String label(int day) {
                    return describe(day);
                }

                private String describe(int day) {
                    return switch (day) {
                        case 1 -> "mon";
                        case 2 -> "tue";
                        default -> "day" + day;
                    };
                }
            }
            """;

    private static final String CATCH_SOURCE = """
            package com.example;

            public class Rich {
                public String safeParse(String raw) {
                    return guarded(raw);
                }

                private String guarded(String raw) {
                    try {
                        return String.valueOf(Integer.parseInt(raw) * 2);
                    } catch (NumberFormatException e) {
                        return "bad";
                    }
                }
            }
            """;

    private static final String GENERIC_SOURCE = """
            package com.example;

            public class Rich {
                public String first(java.util.function.Supplier<String> supplier) {
                    return supply(supplier);
                }

                private String supply(java.util.function.Supplier<String> supplier) {
                    return supplier.get();
                }
            }
            """;

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