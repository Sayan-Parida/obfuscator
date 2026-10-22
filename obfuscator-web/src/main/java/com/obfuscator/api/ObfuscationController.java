package com.obfuscator.api;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.ObfuscationException;
import com.obfuscator.domain.ObfuscationRequest;
import com.obfuscator.domain.ObfuscationResult;
import com.obfuscator.application.ObfuscationService;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    private static final String SHA256_HEADER = "X-Sha256";
    private static final String APPLIED_STEPS_HEADER = "X-Applied-Steps";
    private static final String FUNCTIONALITY_STATUS_HEADER = "X-Functionality-Status";

    private final ObfuscationService service;

    public ObfuscationController(ObfuscationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObfuscationResponse submit(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "format", defaultValue = "JVM_CLASS") ArtifactFormat format,
            @RequestParam(name = "steps", defaultValue = "") List<String> steps,
            @RequestParam(name = "keepMethods", defaultValue = "") List<String> keepMethods) {

        return toResponse(run(file, format, steps, keepMethods));
    }

    /** Same pipeline as {@link #submit}, but streams the artifact bytes instead of base64 JSON. */
    @PostMapping(path = "/download", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> download(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "format", defaultValue = "JVM_CLASS") ArtifactFormat format,
            @RequestParam(name = "steps", defaultValue = "") List<String> steps,
            @RequestParam(name = "keepMethods", defaultValue = "") List<String> keepMethods) {

        ObfuscationResult result = run(file, format, steps, keepMethods);
        Artifact output = result.output();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaTypeOf(output.format()));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + suggestedName(originalName(file), output.format()) + "\"");
        headers.set(SHA256_HEADER, output.sha256());
        headers.set(FUNCTIONALITY_STATUS_HEADER, result.functionality().status().name());
        if (!result.appliedSteps().isEmpty()) {
            headers.set(APPLIED_STEPS_HEADER, String.join(",", result.appliedSteps()));
        }
        return ResponseEntity.ok().headers(headers).body(output.bytes());
    }

    private ObfuscationResult run(
            MultipartFile file, ArtifactFormat format, List<String> steps, List<String> keepMethods) {
        ObfuscationRequest request = new ObfuscationRequest(
                Artifact.of(format, readBytes(file)), steps, joinKeepRules(keepMethods));
        return service.obfuscate(request);
    }

    private static Map<String, String> joinKeepRules(List<String> keepMethods) {
        String joined = String.join(",", keepMethods);
        return joined.isBlank() ? Map.of() : Map.of("keepMethods", joined);
    }

    private static MediaType mediaTypeOf(ArtifactFormat format) {
        return switch (format) {
            case JAR -> MediaType.valueOf("application/java-archive");
            case JVM_CLASS -> MediaType.valueOf("application/java-vm");
            case NATIVE -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private static String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null ? "" : name.replaceAll("[\"\\r\\n]", "");
    }

    private static String suggestedName(String original, ArtifactFormat format) {
        String base = original;
        int pathSeparator = Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\'));
        if (pathSeparator >= 0) {
            base = original.substring(pathSeparator + 1);
        }
        String extension = switch (format) {
            case JAR -> ".jar";
            case JVM_CLASS -> ".class";
            case NATIVE -> base.lastIndexOf('.') < 0 ? "" : base.substring(base.lastIndexOf('.'));
        };
        if (base.isBlank()) {
            return "obfuscated" + extension;
        }
        String stem = base.lastIndexOf('.') < 0 ? base : base.substring(0, base.lastIndexOf('.'));
        return (stem.isBlank() ? "obfuscated" : stem) + "-obfuscated" + extension;
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