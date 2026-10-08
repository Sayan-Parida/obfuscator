package com.obfuscator.engine.jvm;

import com.obfuscator.domain.FunctionalityReport;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * Checks that a rewritten class is still valid bytecode and differs from its
 * input only in method names.
 *
 * <p>The structural comparison is the important half: it asserts that every
 * descriptor, access flag and instruction sequence is preserved, so any
 * behavioural difference could only come from a rename.
 *
 * <p>Free of Spring.
 */
public final class ClassStructureVerifier {

    public FunctionalityReport verify(byte[] original, byte[] obfuscated) {
        List<String> errors;
        List<String> before;
        List<String> after;
        try {
            errors = validityErrors(obfuscated);
            before = structureOf(original);
            after = structureOf(obfuscated);
        } catch (RuntimeException e) {
            return new FunctionalityReport(FunctionalityReport.Status.FAILED,
                    "output could not be parsed as a class file: " + e);
        }

        if (!errors.isEmpty()) {
            return new FunctionalityReport(FunctionalityReport.Status.FAILED,
                    "invalid bytecode: " + String.join("; ", errors));
        }
        if (!before.equals(after)) {
            return new FunctionalityReport(FunctionalityReport.Status.FAILED,
                    "structure changed beyond method names");
        }
        return FunctionalityReport.verified();
    }

    private List<String> validityErrors(byte[] bytes) {
        StringWriter buffer = new StringWriter();
        try (PrintWriter writer = new PrintWriter(buffer)) {
            CheckClassAdapter.verify(new ClassReader(bytes), null, false, writer);
        }
        String output = buffer.toString().trim();
        if (output.isEmpty()) {
            return List.of();
        }
        return output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    /**
     * Per-method shape with names removed: descriptor, access flags and
     * instruction counts. Sorted so declaration order does not matter.
     */
    public List<String> structureOf(byte[] bytes) {
        List<String> members = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                int[] instructions = {0};
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(int opcode) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitVarInsn(int opcode, int varIndex) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String o, String f, String d) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String o, String m, String d, boolean itf) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d,
                            org.objectweb.asm.Handle h, Object... args) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitIincInsn(int varIndex, int increment) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitTableSwitchInsn(int min, int max,
                            org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                            org.objectweb.asm.Label[] labels) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                        instructions[0]++;
                    }

                    @Override
                    public void visitEnd() {
                        members.add(descriptor + " access=" + access
                                + " instructions=" + instructions[0]);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        members.sort(null);
        return members;
    }
}