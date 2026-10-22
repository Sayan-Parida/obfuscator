package com.obfuscator.domain;

import java.util.Set;

/**
 * One obfuscation pass. Implementations are Spring beans; the pipeline orders
 * them by {@link #order()}. A step must not change observable behaviour of the
 * artifact it processes.
 *
 * <p>No implementations exist yet — this interface is the contract the engine
 * will be built against.
 */
public interface TransformationStep {

    /** Stable identifier callers select by, e.g. {@code control-flow-flatten}. */
    String id();

    /** Human-readable summary for the API response and docs. */
    String description();

    /** Lower values run first. */
    int order();

    /** Artifact formats this step can operate on. */
    Set<ArtifactFormat> supports();

    /** Applies the transformation, returning the replacement artifact. */
    Artifact apply(TransformationContext context);
}