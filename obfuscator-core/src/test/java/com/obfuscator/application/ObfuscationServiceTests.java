package com.obfuscator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.FunctionalityReport;
import com.obfuscator.domain.ObfuscationException;
import com.obfuscator.domain.ObfuscationRequest;
import com.obfuscator.domain.ObfuscationResult;
import com.obfuscator.domain.TransformationContext;
import com.obfuscator.domain.TransformationStep;
import com.obfuscator.engine.StepRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObfuscationServiceTests {

    private final ObfuscationService service = new ObfuscationService(
            new StepRegistry(List.of()), ObfuscationService.DEFAULT_MAX_INPUT_BYTES);

    @Test
    void rejectsEmptyInput() {
        ObfuscationRequest request = request(Artifact.of(ArtifactFormat.JVM_CLASS, new byte[0]));

        assertThatThrownBy(() -> service.obfuscate(request))
                .isInstanceOf(ObfuscationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsOversizedInput() {
        ObfuscationService bounded = new ObfuscationService(new StepRegistry(List.of()), 4);

        assertThatThrownBy(() -> bounded.obfuscate(request(Artifact.of(ArtifactFormat.JVM_CLASS, new byte[8]))))
                .isInstanceOf(ObfuscationException.class)
                .hasMessageContaining("max size");
    }

    @Test
    void reportsUnverifiedUntilBehaviourCheckingExists() {
        ObfuscationResult result = service.obfuscate(
                request(Artifact.of(ArtifactFormat.JVM_CLASS, new byte[] {1, 2, 3})));

        assertThat(result.output().size()).isEqualTo(3);
        assertThat(result.appliedSteps()).isEmpty();
        assertThat(result.logEntries()).isEmpty();
        assertThat(result.functionality().status()).isEqualTo(FunctionalityReport.Status.UNVERIFIED);
    }

    @Test
    void recordsEachStepExactlyOnceEvenWhenStepsLogThemselves() {
        TransformationStep step = new RecordingStep();
        ObfuscationService withStep = new ObfuscationService(
                new StepRegistry(List.of(step)), ObfuscationService.DEFAULT_MAX_INPUT_BYTES);

        ObfuscationResult result = withStep.obfuscate(
                new ObfuscationRequest(Artifact.of(ArtifactFormat.JVM_CLASS, new byte[] {1, 2, 3}),
                        List.of(), Map.of()));

        assertThat(result.appliedSteps()).containsExactly("recording");
        assertThat(result.logEntries()).containsExactly(
                new com.obfuscator.domain.TransformationLog.Entry("recording", "records itself, as real steps do"));
    }

    private static final class RecordingStep implements TransformationStep {
        @Override
        public String id() {
            return "recording";
        }

        @Override
        public String description() {
            return "records itself, as real steps do";
        }

        @Override
        public int order() {
            return 1;
        }

        @Override
        public java.util.Set<ArtifactFormat> supports() {
            return java.util.Set.of(ArtifactFormat.JVM_CLASS);
        }

        @Override
        public Artifact apply(TransformationContext context) {
            context.log().record(id(), description());
            return context.artifact();
        }
    }

    private static ObfuscationRequest request(Artifact artifact) {
        return new ObfuscationRequest(artifact, List.of(), Map.of());
    }
}
