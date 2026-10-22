package com.obfuscator.engine;

import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.ObfuscationException;
import com.obfuscator.domain.TransformationStep;
import java.util.List;

/**
 * Resolves requested step ids against the registered {@link TransformationStep}
 * instances and filters out steps that do not support the input format.
 *
 * <p>With no steps registered, every request resolves to an empty pipeline.
 */
public class StepRegistry {

    private final List<TransformationStep> steps;

    public StepRegistry(List<TransformationStep> steps) {
        this.steps = List.copyOf(steps);
    }

    public List<TransformationStep> resolve(List<String> requestedIds, ArtifactFormat format) {
        return steps.stream()
                .filter(step -> requestedIds.isEmpty() || requestedIds.contains(step.id()))
                .filter(step -> step.supports().contains(format))
                .sorted((a, b) -> Integer.compare(a.order(), b.order()))
                .toList();
    }

    public void requireKnown(List<String> requestedIds) {
        for (String id : requestedIds) {
            boolean known = steps.stream().anyMatch(step -> step.id().equals(id));
            if (!known) {
                throw new ObfuscationException("unknown transformation step: " + id);
            }
        }
    }

    public List<TransformationStep> all() {
        return steps;
    }
}