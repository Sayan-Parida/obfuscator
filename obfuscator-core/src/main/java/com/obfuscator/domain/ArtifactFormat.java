package com.obfuscator.domain;

/**
 * Input encodings the engine knows how to load. Transformations are
 * format-specific, so the format is resolved before any step runs.
 */
public enum ArtifactFormat {

    JVM_CLASS,
    JAR,
    NATIVE
}