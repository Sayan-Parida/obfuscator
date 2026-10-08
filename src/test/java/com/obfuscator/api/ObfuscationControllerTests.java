package com.obfuscator.api;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.obfuscator.engine.jvm.TestClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class ObfuscationControllerTests {

    private static final String SUBJECT = "com.example.Controller";
    private static final String SOURCE = """
            package com.example;

            public class Controller {
                public int handle(int value) {
                    return inner(value);
                }

                private int inner(int value) {
                    return value * 3;
                }
            }
            """;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void renamesPrivateMethodsAndReportsVerified() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "Controller.class", null, TestClasses.compile(SUBJECT, SOURCE));

        mvc.perform(multipart("/api/v1/obfuscations").file(file).param("format", "JVM_CLASS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedSteps.length()").value(1))
                .andExpect(jsonPath("$.appliedSteps[0]").value("method-renaming"))
                .andExpect(jsonPath("$.functionalityStatus").value("VERIFIED"));
    }

    @Test
    void outputDiffersFromTheInput() throws Exception {
        byte[] compiled = TestClasses.compile(SUBJECT, SOURCE);
        MockMultipartFile file = new MockMultipartFile("file", "Controller.class", null, compiled);
        String inputDigest = sha256(compiled);

        mvc.perform(multipart("/api/v1/obfuscations").file(file).param("format", "JVM_CLASS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value(not(inputDigest)));
    }

    @Test
    void staysUnverifiedWhenNoStepAppliesToTheFormat() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "lib.so", null, new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/v1/obfuscations").file(file).param("format", "NATIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.appliedSteps").isEmpty())
                .andExpect(jsonPath("$.functionalityStatus").value("UNVERIFIED"));
    }

    @Test
    void rejectsInputThatIsNotAClassFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "App.class", null, new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/v1/obfuscations").file(file).param("format", "JVM_CLASS"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("input is not a valid JVM class file"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rejectsEmptyUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "App.class", null, new byte[0]);

        mvc.perform(multipart("/api/v1/obfuscations").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("input artifact is empty"));
    }

    @Test
    void rejectsUnknownStepId() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "App.class", null, new byte[] {1});

        mvc.perform(multipart("/api/v1/obfuscations").file(file).param("steps", "does-not-exist"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("unknown transformation step: does-not-exist"));
    }
}