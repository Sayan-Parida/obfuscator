package com.obfuscator.engine.jvm;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.FunctionalityReport;
import com.obfuscator.domain.TransformationContext;
import com.obfuscator.domain.TransformationStep;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pipeline adapter for {@link MethodRenaming}. This class holds the Spring
 * wiring only; the transformation itself is framework-free.
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

    public MethodRenamingStep() {
        this(new MethodRenaming(), new ClassStructureVerifier());
    }

    MethodRenamingStep(MethodRenaming renaming, ClassStructureVerifier verifier) {
        this.renaming = renaming;
        this.verifier = verifier;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Replaces private method names with generated identifiers";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Set<ArtifactFormat> supports() {
        return Set.of(ArtifactFormat.JVM_CLASS);
    }

    @Override
    public Artifact apply(TransformationContext context) {
        Artifact input = context.artifact();
        KeepNames keep = KeepNames.parse(context.options().get(KEEP_OPTION));

        MethodRenaming.RenamingResult result = renaming.apply(input, keep);

        FunctionalityReport report = verifier.verify(input.bytes(), result.output().bytes());
        context.report(report);

        context.log().record(id(), "renamed " + result.renamedCount()
                + " method(s), kept " + result.keptMethodNames().size() + ", "
                + report.status().name());
        return result.output();
    }
}