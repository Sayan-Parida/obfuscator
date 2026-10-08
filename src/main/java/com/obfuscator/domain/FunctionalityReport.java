package com.obfuscator.domain;

/**
 * Outcome of checking that an obfuscated artifact still behaves like the input.
 * Obfuscation that breaks behaviour is a defect, so every result carries this.
 */
public record FunctionalityReport(Status status, String detail) {

    public enum Status {
        VERIFIED,
        UNVERIFIED,
        FAILED
    }

    public static FunctionalityReport unverified(String detail) {
        return new FunctionalityReport(Status.UNVERIFIED, detail);
    }

    public static FunctionalityReport verified() {
        return new FunctionalityReport(Status.VERIFIED, "output matches input behaviour");
    }
}