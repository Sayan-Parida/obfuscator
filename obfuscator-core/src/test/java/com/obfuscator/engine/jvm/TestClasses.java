package com.obfuscator.engine.jvm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static javax.tools.JavaFileObject.Kind.SOURCE;

/** Compiles Java sources at test time so fixtures are always real class files. */
public final class TestClasses {

    private TestClasses() {
    }

    /**
     * Compiles {@code source} and returns its class file. Extra sources are
     * compiled alongside it so fixtures can declare a supertype.
     */
    public static byte[] compile(String qualifiedName, String source, String... otherSources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler available");
        }
        Path output = createTempDirectory();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(output.toFile()));

            List<JavaFileObject> sources = new ArrayList<>();
            sources.add(sourceFile(qualifiedName, source));
            for (String extra : otherSources) {
                sources.add(sourceFile(qualifiedNameOf(extra), extra));
            }

            List<String> options = List.of("--release", "17");
            boolean compiled = compiler.getTask(null, fileManager, null, options, null, sources).call();
            if (!compiled) {
                throw new IllegalStateException("fixture " + qualifiedName + " failed to compile");
            }
            return Files.readAllBytes(output.resolve(qualifiedName.replace('.', '/') + ".class"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteRecursively(output);
        }
    }

    private static JavaFileObject sourceFile(String qualifiedName, String source) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + qualifiedName.replace('.', '/') + ".java"), SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    /** Reads the class name out of a {@code package a.b; public class C {}} source. */
    static String qualifiedNameOf(String source) {
        String pkg = "";
        int packageStart = source.indexOf("package ");
        if (packageStart >= 0) {
            int end = source.indexOf(';', packageStart);
            pkg = source.substring(packageStart + "package ".length(), end).trim() + ".";
        }
        int classStart = source.indexOf("class ");
        int nameEnd = source.indexOf('{', classStart);
        String simpleName = source.substring(classStart + "class ".length(), nameEnd).trim();
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (c == ' ' || c == '<' || c == '\n') {
                simpleName = simpleName.substring(0, i);
                break;
            }
        }
        return pkg + simpleName;
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("obfuscator-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
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
        } catch (IOException ignored) {
            // best effort cleanup of a temp directory
        }
    }

    /** Loads one class from bytes so original and obfuscated can be compared. */
    public static final class SingleClassLoader extends ClassLoader {

        private final String name;
        private final byte[] bytes;

        public SingleClassLoader(String name, byte[] bytes) {
            super(null);
            this.name = name;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String requested) throws ClassNotFoundException {
            if (!requested.equals(name)) {
                throw new ClassNotFoundException(requested);
            }
            return defineClass(requested, bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(requested)) {
                Class<?> loaded = findLoadedClass(requested);
                if (loaded == null) {
                    loaded = requested.equals(name) ? findClass(requested) : super.loadClass(requested, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    /** Declared method names of a class file, in declaration order. */
    public static List<String> methodNames(byte[] bytes) {
        List<String> names = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                names.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return names;
    }

    public static String className(byte[] bytes) {
        return new ClassReader(bytes).getClassName();
    }
}