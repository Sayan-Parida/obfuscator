package com.obfuscator.application;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ObfuscationException;
import com.obfuscator.domain.ObfuscationRequest;
import com.obfuscator.domain.ObfuscationResult;
import com.obfuscator.domain.TransformationContext;
import com.obfuscator.domain.TransformationStep;
import com.obfuscator.engine.StepRegistry;
import java.util.List;

/**
 * Runs the selected transformations in order and reports what happened.
 *
 * <p>Steps record their own entries in the transformation log, so this only
 * threads the artifact through the pipeline.
 *
 * <p>The reported functionality status comes from whichever steps ran: a step
 * that verifies its own output raises it, otherwise it stays UNVERIFIED.
 *
 * <p>Spring-free: the web module and the CLI both construct this directly.
 */
public class ObfuscationService {

    public static final long DEFAULT_MAX_INPUT_BYTES = 64L * 1024 * 1024;

    private final StepRegistry stepRegistry;
    private final long maxInputBytes;

    public ObfuscationService(StepRegistry stepRegistry, long maxInputBytes) {
        this.stepRegistry = stepRegistry;
        this.maxInputBytes = maxInputBytes;
    }

    public ObfuscationResult obfuscate(ObfuscationRequest request) {
        validate(request);

        stepRegistry.requireKnown(request.stepIds());
        List<TransformationStep> pipeline =
                stepRegistry.resolve(request.stepIds(), request.input().format());

        TransformationContext context =
                new TransformationContext(request.input(), request.options());

        for (TransformationStep step : pipeline) {
            Artifact transformed = step.apply(context);
            context.replaceWith(transformed);
        }

        return new ObfuscationResult(
                context.artifact(),
                context.log().stepIds(),
                context.functionality(),
                context.log().entries());
    }

    private void validate(ObfuscationRequest request) {
        Artifact input = request.input();
        if (input.size() == 0) {
            throw new ObfuscationException("input artifact is empty");
        }
        if (input.size() > maxInputBytes) {
            throw new ObfuscationException(
                    "input exceeds max size of " + maxInputBytes + " bytes");
        }
    }
}