package com.obfuscator.engine.jvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.FunctionalityReport;
import com.obfuscator.domain.ObfuscationException;
import com.obfuscator.domain.TransformationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MethodRenamingStepTests {

    private static final String SUBJECT = "com.example.Orders";
    private static final String SOURCE = """
            package com.example;

            public class Orders {
                public int checkout(int[] items) {
                    return summarise(items);
                }

                private int summarise(int[] items) {
                    return items.length;
                }
            }
            """;

    private final MethodRenamingStep step = new MethodRenamingStep();

    @Test
    void isDiscoverableByTheRegistryContract() {
        assertThat(step.id()).isEqualTo("method-renaming");
        assertThat(step.supports()).containsExactlyInAnyOrder(ArtifactFormat.JVM_CLASS, ArtifactFormat.JAR);
        assertThat(step.order()).isPositive();
    }

    @Test
    void reportsVerifiedFunctionality() {
        TransformationContext context = context(Map.of());

        step.apply(context);

        assertThat(context.functionality().status()).isEqualTo(FunctionalityReport.Status.VERIFIED);
        assertThat(context.log().stepIds()).containsExactly("method-renaming");
        assertThat(context.log().entries().get(0).detail()).contains("renamed 1 method");
    }

    @Test
    void passesKeepListThroughOptions() {
        TransformationContext context = context(Map.of("keepMethods", "summarise"));

        step.apply(context);

        assertThat(TestClasses.methodNames(context.artifact().bytes())).contains("summarise");
        assertThat(context.functionality().status()).isEqualTo(FunctionalityReport.Status.VERIFIED);
    }

    @Test
    void doesNotApplyToNativeFormat() {
        assertThat(step.supports()).doesNotContain(ArtifactFormat.NATIVE);
    }

    @Test
    void refusesToApplyWhenVerificationFails() {
        TransformationContext context = new TransformationContext(
                com.obfuscator.domain.Artifact.of(ArtifactFormat.JVM_CLASS, classWithStackUnderflow()),
                Map.of());

        assertThatThrownBy(() -> step.apply(context))
                .isInstanceOf(ObfuscationException.class)
                .hasMessageContaining("failed verification");

        assertThat(context.functionality().status()).isEqualTo(FunctionalityReport.Status.FAILED);
    }

    private static byte[] classWithStackUnderflow() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "Broken", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor method = writer.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "boom", "()V", null, null);
        method.visitCode();
        method.visitInsn(org.objectweb.asm.Opcodes.POP);
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private TransformationContext context(Map<String, String> options) {
        return new TransformationContext(
                com.obfuscator.domain.Artifact.of(ArtifactFormat.JVM_CLASS,
                        TestClasses.compile(SUBJECT, SOURCE)),
                options);
    }
}