package com.obfuscator.application;

import com.obfuscator.engine.jvm.JarArchive;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the obfuscation service. */
@ConfigurationProperties(prefix = "obfuscator")
public class ObfuscationProperties {

    /** Rejects oversized uploads before any parsing work happens. */
    private long maxInputBytes = ObfuscationService.DEFAULT_MAX_INPUT_BYTES;

    /** Cap on a single inflated archive entry. */
    private long maxArchiveEntryBytes = JarArchive.DEFAULT_MAX_ENTRY_BYTES;

    /** Cap on the total inflated size of an archive. */
    private long maxArchiveExpandedBytes = JarArchive.DEFAULT_MAX_EXPANDED_BYTES;

    public long getMaxInputBytes() {
        return maxInputBytes;
    }

    public void setMaxInputBytes(long maxInputBytes) {
        this.maxInputBytes = maxInputBytes;
    }

    public long getMaxArchiveEntryBytes() {
        return maxArchiveEntryBytes;
    }

    public void setMaxArchiveEntryBytes(long maxArchiveEntryBytes) {
        this.maxArchiveEntryBytes = maxArchiveEntryBytes;
    }

    public long getMaxArchiveExpandedBytes() {
        return maxArchiveExpandedBytes;
    }

    public void setMaxArchiveExpandedBytes(long maxArchiveExpandedBytes) {
        this.maxArchiveExpandedBytes = maxArchiveExpandedBytes;
    }
}
