package com.obfuscator.cli;

import com.obfuscator.engine.jvm.TestClasses;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import picocli.CommandLine;

/** In-process invocations and fixtures shared by the CLI tests. */
final class CliTestSupport {

    static final String SUBJECT = "demo.App";
    static final String SOURCE = """
            package demo;

            public class App {
                public String call() {
                    return secret();
                }

                private String secret() {
                    return "value";
                }
            }
            """;

    private CliTestSupport() {
    }

    record Invocation(int exitCode, String out, String err) {
    }

    static Invocation run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        PrintWriter outWriter = new PrintWriter(out);
        PrintWriter errWriter = new PrintWriter(err);

        CommandLine commandLine = ObfuscatorCli.createCommandLine();
        commandLine.setOut(outWriter);
        commandLine.setErr(errWriter);
        for (CommandLine subcommand : commandLine.getSubcommands().values()) {
            subcommand.setOut(outWriter);
            subcommand.setErr(errWriter);
        }

        int exitCode = commandLine.execute(args);
        outWriter.flush();
        errWriter.flush();
        return new Invocation(exitCode, out.toString(), err.toString());
    }

    static byte[] compiledClass() {
        return TestClasses.compile(SUBJECT, SOURCE);
    }

    static Path write(Path directory, String name, byte[] bytes) throws IOException {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent() == null ? directory : file.getParent());
        return Files.write(file, bytes);
    }

    static Path jar(Path directory, String name, Map<String, byte[]> entries, String mainClass)
            throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        Path jar = directory.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    static Map<String, byte[]> entries(Path jar) throws IOException {
        Map<String, byte[]> entries = new java.util.HashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                entries.put(entry.getName(), file.getInputStream(entry).readAllBytes());
            }
        }
        return entries;
    }
}
