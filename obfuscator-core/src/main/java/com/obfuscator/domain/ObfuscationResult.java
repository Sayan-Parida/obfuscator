package com.obfuscator.domain;

import java.util.List;

/** The obfuscated artifact plus an audit trail of how it was produced. */
public record ObfuscationResult(
        Artifact output,
        List<String> appliedSteps,
        FunctionalityReport functionality,
        List<TransformationLog.Entry> logEntries) {

    public ObfuscationResult {
        appliedSteps = List.copyOf(appliedSteps);
        logEntries = List.copyOf(logEntries);
    }
}
