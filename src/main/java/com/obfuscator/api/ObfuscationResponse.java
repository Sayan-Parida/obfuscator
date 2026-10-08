package com.obfuscator.api;

import com.obfuscator.domain.ArtifactFormat;
import jakarta.validation.constraints.NotNull;

/** Response body for a completed obfuscation run. */
public record ObfuscationResponse(
        @NotNull ArtifactFormat format,
        String sha256,
        int size,
        java.util.List<String> appliedSteps,
        String functionalityStatus,
        String functionalityDetail,
        String outputBase64) {
}