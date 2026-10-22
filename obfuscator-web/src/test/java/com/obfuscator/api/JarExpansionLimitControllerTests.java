package com.obfuscator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Proves a decompression bomb is rejected by the API instead of being served. */
@SpringBootTest(properties = {
        "obfuscator.max-archive-entry-bytes=4096",
        "obfuscator.max-archive-expanded-bytes=6000"
})
class JarExpansionLimitControllerTests {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void refusesAJarWhoseEntryExceedsTheExpansionLimit() throws Exception {
        byte[] bomb = jarOf(Map.of("payload.bin", new byte[32 * 1024]));

        mvc.perform(multipart("/api/v1/obfuscations")
                        .file(new MockMultipartFile("file", "bomb.jar", "application/java-archive", bomb))
                        .param("format", "JAR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("maximum uncompressed size")))
                .andExpect(jsonPath("$.errors.reason", containsString("payload.bin")));
    }

    @Test
    void refusesAJarWhoseTotalExpansionExceedsTheLimit() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("first.bin", new byte[4000]);
        entries.put("second.bin", new byte[4000]);

        mvc.perform(multipart("/api/v1/obfuscations")
                        .file(new MockMultipartFile("file", "big.jar", "application/java-archive", jarOf(entries)))
                        .param("format", "JAR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("maximum total uncompressed size")));
    }

    @Test
    void stillProcessesAJarWithinTheExpansionLimits() throws Exception {
        byte[] small = jarOf(Map.of("readme.txt", new byte[1024]));

        mvc.perform(multipart("/api/v1/obfuscations")
                        .file(new MockMultipartFile("file", "small.jar", "application/java-archive", small))
                        .param("format", "JAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedSteps[0]").value("method-renaming"));
    }

    private static byte[] jarOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new ZipEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
