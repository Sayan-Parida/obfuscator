package com.obfuscator.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.obfuscator.engine.jvm.JarArchive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * The documented upload window has to match what is actually configured: a
 * 64 MB file cap enforced by the container, a slightly larger request cap for
 * multipart framing, and the service's own re-check of the same 64 MB.
 */
@SpringBootTest
class UploadAndArchiveLimitDefaultsTests {

    @Autowired
    private Environment environment;

    @Test
    void documentsTheEffectiveUploadLimit() {
        DataSize fileLimit = DataSize.parse(required("spring.servlet.multipart.max-file-size"));
        DataSize requestLimit = DataSize.parse(required("spring.servlet.multipart.max-request-size"));
        long serviceLimit = Long.parseLong(required("obfuscator.max-input-bytes"));

        assertThat(fileLimit).isEqualTo(DataSize.ofMegabytes(64));
        assertThat(serviceLimit).isEqualTo(fileLimit.toBytes());
        assertThat(requestLimit).isGreaterThan(fileLimit);
        assertThat(requestLimit.toBytes() - fileLimit.toBytes())
                .as("multipart framing headroom")
                .isBetween(1L, DataSize.ofMegabytes(2).toBytes());
    }

    @Test
    void defaultArchiveExpansionCapsMatchTheConfiguration() {
        assertThat(Long.parseLong(required("obfuscator.max-archive-entry-bytes")))
                .isEqualTo(JarArchive.DEFAULT_MAX_ENTRY_BYTES);
        assertThat(Long.parseLong(required("obfuscator.max-archive-expanded-bytes")))
                .isEqualTo(JarArchive.DEFAULT_MAX_EXPANDED_BYTES);
    }

    private String required(String property) {
        return environment.getRequiredProperty(property);
    }
}
