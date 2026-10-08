package com.obfuscator.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the obfuscation service. */
@ConfigurationProperties(prefix = "obfuscator")
public class ObfuscationProperties {

    /** Rejects oversized uploads before any parsing work happens. */
    private long maxInputBytes = 64L * 1024 * 1024;

    public long getMaxInputBytes() {
        return maxInputBytes;
    }

    public void setMaxInputBytes(long maxInputBytes) {
        this.maxInputBytes = maxInputBytes;
    }
}