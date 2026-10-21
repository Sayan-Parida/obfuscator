package com.obfuscator.engine.jvm;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.FunctionalityReport;
import com.obfuscator.domain.TransformationContext;
import com.obfuscator.domain.TransformationStep;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pipeline adapter for {@link MethodRenaming}. This class holds the Spring
 * wiring only; the transformation itself is framework-free.
 *
 * <p>Handles both single class files and whole JARs. For a JAR the transformation
 * runs on every {@code .class} entry while non-class resources and the manifest
 * are copied through unchanged.
 *
 * <p>Configure with the {@code keepMethods} option, a comma-separated list of
 * method names, or {@code com.example.Owner#method} to scope one rule.
 */
@Component
public class MethodRenamingStep implements TransformationStep {

    public static final String ID = "method-renaming";
    private static final String KEEP_OPTION = "keepMethods";

    private final MethodRenaming renaming;
    private final ClassStructureVerifier verifier;
    private final JarArchive jarArchive;

    public MethodRenamingStep() {
        this(new MethodRenaming(), new ClassStructureVerifier(), new JarArchive());
    }

    MethodRenamingStep(MethodRenaming renaming, ClassStructureVerifier verifier, JarArchive jarArchive) {
        this.renaming = renaming;
        this.verifier = verifier;
        this.jarArchive = jarArchive;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Replaces private method names with generated identifiers in class files and JARs";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Set<ArtifactFormat> supports() {
        return Set.of(ArtifactFormat.JVM_CLASS, ArtifactFormat.JAR);
    }

    @Override
    public Artifact apply(TransformationContext context) {
        return context.artifact().format() == ArtifactFormat.JAR
                ? applyToJar(context)
                : applyToClass(context);
    }

    private Artifact applyToClass(TransformationContext context) {
        Artifact input = context.artifact();
        KeepNames keep = KeepNames.parse(context.options().get(KEEP_OPTION));

        MethodRenaming.RenamingResult result = renaming.apply(input, keep);
        FunctionalityReport report = verifier.verify(input.bytes(), result.output().bytes());
        context.report(report);

        context.log().record(id(), "renamed " + result.renamedCount()
                + " method(s), kept " + result.keptMethodNames().size() + ", " + report.status().name());
        return result.output();
    }

    private Artifact applyToJar(TransformationContext context) {
        Artifact input = context.artifact();
        KeepNames keep = KeepNames.parse(context.options().get(KEEP_OPTION));

        JarArchive.Result result = jarArchive.rewrite(input.bytes(), (entryName, classBytes) ->
                transformClass(classBytes, keep));

        List<JarArchive.EntryResult> classes = result.entries().stream()
                .filter(JarArchive.EntryResult::classFile)
                .toList();
        long transformed = classes.stream().filter(JarArchive.EntryResult::changed).count();
        List<String> skipped = classes.stream()
                .filter(JarArchive.EntryResult::skipped)
                .map(JarArchive.EntryResult::name)
                .toList();

        FunctionalityReport report = aggregate(transformed, skipped);
        context.report(report);

        context.log().record(id(), "obfuscated " + transformed + " class(es), skipped "
                + skipped.size() + ", " + report.status().name());
        return Artifact.of(ArtifactFormat.JAR, result.jarBytes());
    }

    private JarArchive.Outcome transformClass(byte[] classBytes, KeepNames keep) {
        MethodRenaming.RenamingResult renamed =
                renaming.apply(Artifact.of(ArtifactFormat.JVM_CLASS, classBytes), keep);
        FunctionalityReport report = verifier.verify(classBytes, renamed.output().bytes());
        if (report.status() == FunctionalityReport.Status.FAILED) {
            return new JarArchive.Outcome(classBytes, false, true, "verification failed; left unchanged");
        }
        boolean changed = !Arrays.equals(classBytes, renamed.output().bytes());
        return new JarArchive.Outcome(renamed.output().bytes(), changed, false,
                changed ? "renamed " + renamed.renamedCount() + " method(s)"
                        : "no private methods to rename");
    }

    /**
     * A single skipped class means the archive was not fully obfuscated, so the
     * result is reported as unverified rather than claiming every class was
     * checked.
     */
    private FunctionalityReport aggregate(long transformed, List<String> skipped) {
        if (transformed == 0) {
            return FunctionalityReport.unverified("no class files were transformed");
        }
        if (!skipped.isEmpty()) {
            return FunctionalityReport.unverified(
                    "skipped " + skipped.size() + " class file(s): " + String.join(", ", skipped));
        }
        return FunctionalityReport.verified();
    }
}