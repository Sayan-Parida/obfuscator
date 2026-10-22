package com.obfuscator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.ObfuscationException;
import com.obfuscator.domain.TransformationStep;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StepRegistryTests {

    @Test
    void ordersStepsByDeclaredOrder() {
        StepRegistry registry = new StepRegistry(List.of(step("b", 20), step("a", 10)));

        assertThat(registry.resolve(List.of(), ArtifactFormat.JVM_CLASS))
                .extracting(TransformationStep::id)
                .containsExactly("a", "b");
    }

    @Test
    void filtersOutStepsThatDoNotSupportFormat() {
        StepRegistry registry = new StepRegistry(List.of(step("a", 10)));

        assertThat(registry.resolve(List.of(), ArtifactFormat.NATIVE)).isEmpty();
    }

    @Test
    void rejectsUnknownStepIds() {
        StepRegistry registry = new StepRegistry(List.of(step("a", 10)));

        assertThatThrownBy(() -> registry.requireKnown(List.of("a", "nope")))
                .isInstanceOf(ObfuscationException.class)
                .hasMessageContaining("nope");
    }

    private static TransformationStep step(String id, int order) {
        return new TransformationStep() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return id;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public Set<ArtifactFormat> supports() {
                return Set.of(ArtifactFormat.JVM_CLASS);
            }

            @Override
            public com.obfuscator.domain.Artifact apply(com.obfuscator.domain.TransformationContext context) {
                return context.artifact();
            }
        };
    }
}