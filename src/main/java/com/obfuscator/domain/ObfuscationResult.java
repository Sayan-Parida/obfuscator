package com.obfuscator.domain;

import java.util.List;

/** The obfuscated artifact plus an audit trail of how it was produced. */
public record ObfuscationResult(
        Artifact output,
        List<String> appliedSteps,
        FunctionalityReport functionality) {

    public ObfuscationResult {
        appliedSteps = List.copyOf(appliedSteps);
    }
}