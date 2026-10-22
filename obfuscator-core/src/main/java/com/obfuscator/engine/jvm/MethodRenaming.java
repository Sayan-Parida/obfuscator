package com.obfuscator.engine.jvm;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ObfuscationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

/**
 * Replaces method names with generated identifiers.
 *
 * <p>Only private methods are renamed. This is a soundness constraint, not a
 * stylistic choice: a non-private method can be referenced from another class,
 * or override a supertype method, and this transformation only ever sees a
 * single class, so it cannot rewrite those call sites or keep virtual dispatch
 * intact. Private methods are unreachable from other classes, so renaming them
 * cannot change dispatch.
 *
 * <p>Constructors, class initialisers, native methods, the standard private
 * serialization hooks ({@code readObject}, {@code writeObject},
 * {@code readObjectNoData}, {@code readResolve}, {@code writeReplace}),
 * compiler-generated members, and anything matched by {@link KeepNames} are
 * left alone: all of those are looked up by name by the JVM or reflection.
 * Class names are never changed.
 *
 * <p>Deliberately free of Spring: the pipeline wires this in as a plain object.
 */
public final class MethodRenaming {

    /** Prefix the compiler uses for members it synthesises. */
    private static final Set<String> COMPILER_GENERATED_PREFIXES = Set.of("lambda$", "access$");

    /**
     * Constructors and the class initializer are never renameable: the JVM
     * identifies them by name alone, and renaming {@code <init>} produces a
     * class that fails verification at load time.
     */
    private static final Set<String> JVM_SPECIAL_NAMES = Set.of("<init>", "<clinit>");

    /**
     * Private methods the JVM serialization machinery looks up by exact name,
     * so their names are part of the object's API across streams.
     */
    private static final Map<String, String> SERIALIZATION_HOOKS = Map.of(
            "readObject", "(Ljava/io/ObjectInputStream;)V",
            "writeObject", "(Ljava/io/ObjectOutputStream;)V",
            "readObjectNoData", "()V",
            "readResolve", "()Ljava/lang/Object;",
            "writeReplace", "()Ljava/lang/Object;");

    public RenamingResult apply(Artifact input, KeepNames keep) {
        byte[] bytes = input.bytes();
        ClassReader reader = readOrReject(bytes);
        String owner = reader.getClassName();

        List<Method> methods = methodsOf(reader);
        Map<String, String> mapping = planMethods(owner, methods, keep);

        byte[] output = remap(bytes, mapping);
        int applied = confirmApplied(output, mapping);

        return new RenamingResult(
                Artifact.of(input.format(), output),
                applied,
                methods.stream()
                        .filter(m -> !mapping.containsKey(key(owner, m.name(), m.descriptor())))
                        .map(m -> m.name())
                        .distinct()
                        .sorted()
                        .toList(),
                mapping);
    }

    private static ClassReader readOrReject(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xCA || (bytes[1] & 0xFF) != 0xFE
                || (bytes[2] & 0xFF) != 0xBA || (bytes[3] & 0xFF) != 0xBE) {
            throw new ObfuscationException("input is not a valid JVM class file");
        }
        try {
            return new ClassReader(bytes);
        } catch (RuntimeException e) {
            throw new ObfuscationException("input is not a valid JVM class file");
        }
    }

    private List<Method> methodsOf(ClassReader reader) {
        List<Method> methods = new ArrayList<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                methods.add(new Method(name, descriptor, access));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private Map<String, String> planMethods(String owner, List<Method> methods, KeepNames keep) {
        Set<String> reserved = methods.stream().map(Method::name)
                .collect(java.util.stream.Collectors.toSet());
        NameAllocator allocator = new NameAllocator(reserved);

        Map<String, String> mapping = new LinkedHashMap<>();
        for (Method method : methods) {
            if (!isRenameable(method, owner, keep)) {
                continue;
            }
            mapping.put(key(owner, method.name(), method.descriptor()), allocator.next());
        }
        return mapping;
    }

    private boolean isRenameable(Method method, String owner, KeepNames keep) {
        boolean isPrivate = (method.access() & Opcodes.ACC_PRIVATE) != 0;
        if (!isPrivate || isNameBound(method)) {
            return false;
        }
        boolean isSynthetic = (method.access() & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
        boolean isCompilerGenerated = COMPILER_GENERATED_PREFIXES.stream()
                .anyMatch(method.name()::startsWith);
        return !isSynthetic && !isCompilerGenerated && !keep.keeps(owner, method.name());
    }

    private boolean isNameBound(Method method) {
        if (JVM_SPECIAL_NAMES.contains(method.name())) {
            return true;
        }
        if ((method.access() & Opcodes.ACC_NATIVE) != 0) {
            return true;
        }
        String hookDescriptor = SERIALIZATION_HOOKS.get(method.name());
        return hookDescriptor != null && hookDescriptor.equals(method.descriptor());
    }

    /**
     * ASM 9.10 keys method renames on {@code owner + "." + name + descriptor},
     * while field renames use {@code owner + "." + name}. A method key without
     * the descriptor silently matches nothing and leaves the name unchanged.
     */
    static String key(String owner, String name, String descriptor) {
        return owner + "." + name + descriptor;
    }

    private byte[] remap(byte[] bytes, Map<String, String> mapping) {
        if (mapping.isEmpty()) {
            return bytes;
        }
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(mapping)), 0);
        return writer.toByteArray();
    }

    /**
     * Re-reads the result and counts how many planned renames really landed. A
     * silently ineffective mapping is otherwise indistinguishable from success.
     */
    private int confirmApplied(byte[] output, Map<String, String> mapping) {
        Set<String> actualNames = methodsOf(new ClassReader(output)).stream()
                .map(Method::name)
                .collect(java.util.stream.Collectors.toSet());

        int applied = 0;
        for (String newName : mapping.values()) {
            if (actualNames.contains(newName)) {
                applied++;
            }
        }
        if (applied != mapping.size()) {
            throw new ObfuscationException(
                    "method renaming was ineffective: expected " + mapping.size()
                            + " renames but " + applied + " were applied");
        }
        return applied;
    }

    record Method(String name, String descriptor, int access) {
    }

    public record RenamingResult(
            Artifact output,
            int renamedCount,
            List<String> keptMethodNames,
            Map<String, String> mapping) {
    }
}