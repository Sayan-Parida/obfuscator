package com.obfuscator.domain;

import java.util.List;
import java.util.Map;

/** A request to obfuscate one artifact using an explicit list of steps. */
public record ObfuscationRequest(
        Artifact input,
        List<String> stepIds,
        Map<String, String> options) {

    public ObfuscationRequest {
        stepIds = List.copyOf(stepIds);
        options = Map.copyOf(options);
    }
}