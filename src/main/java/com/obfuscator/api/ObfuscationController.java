package com.obfuscator.api;

import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.ObfuscationException;
import com.obfuscator.domain.ObfuscationRequest;
import com.obfuscator.domain.ObfuscationResult;
import com.obfuscator.application.ObfuscationService;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** HTTP entrypoint for submitting binaries to obfuscate. */
@RestController
@RequestMapping("/api/v1/obfuscations")
public class ObfuscationController {

    private final ObfuscationService service;

    public ObfuscationController(ObfuscationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObfuscationResponse submit(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "format", defaultValue = "JVM_CLASS") ArtifactFormat format,
            @RequestParam(name = "steps", defaultValue = "") List<String> steps) {

        ObfuscationRequest request = new ObfuscationRequest(
                com.obfuscator.domain.Artifact.of(format, readBytes(file)), steps, Map.of());
        return toResponse(service.obfuscate(request));
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new ObfuscationException("could not read uploaded artifact");
        }
    }

    private static ObfuscationResponse toResponse(ObfuscationResult result) {
        return new ObfuscationResponse(
                result.output().format(),
                result.output().sha256(),
                result.output().size(),
                result.appliedSteps(),
                result.functionality().status().name(),
                result.functionality().detail(),
                Base64.getEncoder().encodeToString(result.output().bytes()));
    }
}