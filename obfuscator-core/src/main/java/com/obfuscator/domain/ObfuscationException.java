package com.obfuscator.domain;

/** Rejected up front by input validation; carries an HTTP-facing reason. */
public class ObfuscationException extends RuntimeException {

    private final String reason;

    public ObfuscationException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}