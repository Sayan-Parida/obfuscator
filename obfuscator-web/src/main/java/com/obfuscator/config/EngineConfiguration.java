package com.obfuscator.config;

import com.obfuscator.application.ObfuscationProperties;
import com.obfuscator.application.ObfuscationService;
import com.obfuscator.domain.TransformationStep;
import com.obfuscator.engine.StepRegistry;
import com.obfuscator.engine.jvm.MethodRenamingStep;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Spring-free core into the web application. The core classes carry no
 * stereotypes of their own, so their construction (and the archive caps read
 * from configuration) lives here.
 */
@Configuration
public class EngineConfiguration {

    @Bean
    public MethodRenamingStep methodRenamingStep(ObfuscationProperties properties) {
        return new MethodRenamingStep(
                properties.getMaxArchiveEntryBytes(), properties.getMaxArchiveExpandedBytes());
    }

    @Bean
    public StepRegistry stepRegistry(List<TransformationStep> steps) {
        return new StepRegistry(steps);
    }

    @Bean
    public ObfuscationService obfuscationService(StepRegistry stepRegistry, ObfuscationProperties properties) {
        return new ObfuscationService(stepRegistry, properties.getMaxInputBytes());
    }
}
