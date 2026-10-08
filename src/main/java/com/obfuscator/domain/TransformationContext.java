package com.obfuscator.domain;

import java.util.Map;

/**
 * Mutable state threaded through a transformation pipeline. Steps read the
 * current artifact, emit a replacement, and may record options for later steps.
 */
public final class TransformationContext {

    private final Map<String, String> options;
    private Artifact artifact;
    private final TransformationLog log = new TransformationLog();
    private FunctionalityReport functionality = FunctionalityReport.unverified("not checked by any step");

    public TransformationContext(Artifact artifact, Map<String, String> options) {
        this.artifact = artifact;
        this.options = Map.copyOf(options);
    }

    public Artifact artifact() {
        return artifact;
    }

    public void replaceWith(Artifact replacement) {
        this.artifact = replacement;
    }

    public Map<String, String> options() {
        return options;
    }

    public TransformationLog log() {
        return log;
    }

    /** Steps report how far they got verifying behaviour preservation. */
    public void report(FunctionalityReport report) {
        this.functionality = report;
    }

    public FunctionalityReport functionality() {
        return functionality;
    }
}