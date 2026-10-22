package com.obfuscator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.obfuscator.engine.jvm.TestClasses;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

    private static final String JAR_MAIN_SOURCE = """
            package demo;

            public class Main {
                public static void main(String[] args) {
                    System.out.println(computeTotal(1, 2, 3));
                }

                private static int computeTotal(int first, int second, int third) {
                    return first + second + third;
                }
            }
            """;

    private static final String JAR_HELPER_SOURCE = """
            package demo;

            public class Helper {
                public static int work(int value) {
                    return helper(value);
                }

                private static int helper(int value) {
                    return value + 1;
                }
            }
            """;

    @Test
    void obfuscatesAJarAndPreservesResources() throws Exception {
        byte[] jar = jarOf(
                entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: demo.Main\r\n\r\n"),
                entry("demo/Main.class", TestClasses.compile("demo.Main", JAR_MAIN_SOURCE)),
                entry("demo/app.properties", "app.name=obfuscator\n"));

        MockMultipartFile file = new MockMultipartFile("file", "app.jar", "application/java-archive", jar);

        String body = mvc.perform(multipart("/api/v1/obfuscations").file(file).param("format", "JAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedSteps.length()").value(1))
                .andExpect(jsonPath("$.appliedSteps[0]").value("method-renaming"))
                .andExpect(jsonPath("$.functionalityStatus").value("VERIFIED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        byte[] output = Base64.getDecoder().decode(extractBase64(body, "outputBase64"));
        Map<String, byte[]> entries = readEntries(output);
        assertThat(entries).containsKeys("META-INF/MANIFEST.MF", "demo/Main.class", "demo/app.properties");
        assertThat(entries.get("demo/app.properties")).isEqualTo("app.name=obfuscator\n".getBytes());
        assertThat(TestClasses.methodNames(entries.get("demo/Main.class"))).doesNotContain("computeTotal");
    }

    @Test
    void rejectsSignedJar() throws Exception {
        byte[] jar = jarOf(
                entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".getBytes()),
                entry("META-INF/APP.SF", "signature".getBytes()));
        MockMultipartFile file = new MockMultipartFile("file", "app.jar", "application/java-archive", jar);

        mvc.perform(multipart("/api/v1/obfuscations").file(file).param("format", "JAR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("signed JARs cannot be obfuscated: META-INF/APP.SF"));
    }

    @Test
    void rejectsInvalidJar() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "app.jar", "application/java-archive", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/v1/obfuscations").file(file).param("format", "JAR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("input is not a valid JAR file"));
    }

    private static Map.Entry<String, byte[]> entry(String name, String content) {
        return Map.entry(name, content.getBytes());
    }

    private static Map.Entry<String, byte[]> entry(String name, byte[] content) {
        return Map.entry(name, content);
    }

    private static byte[] jarOf(Map.Entry<String, byte[]>... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> item : entries) {
                jar.putNextEntry(new ZipEntry(item.getKey()));
                jar.write(item.getValue());
                jar.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> readEntries(byte[] jar) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return entries;
    }

    private static String extractBase64(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([A-Za-z0-9+/=]+)\"").matcher(json);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
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

    private static final byte[] CLASS_MAGIC = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    @Test
    void streamsAnObfuscatedClassFileAsBinary() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "Controller.class", null, TestClasses.compile(SUBJECT, SOURCE));

        MvcResult result = mvc
                .perform(multipart("/api/v1/obfuscations/download").file(file).param("format", "JVM_CLASS"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/java-vm"))
                .andExpect(header().string(
                        "Content-Disposition", "attachment; filename=\"Controller-obfuscated.class\""))
                .andExpect(header().string("X-Applied-Steps", "method-renaming"))
                .andExpect(header().string("X-Functionality-Status", "VERIFIED"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).startsWith(CLASS_MAGIC);
        assertThat(TestClasses.methodNames(body)).doesNotContain("inner");
        assertThat(headerOf(result, "X-Sha256")).isEqualTo(sha256(body));
    }

    @Test
    void streamsAnObfuscatedJarAsBinary() throws Exception {
        byte[] jar = jarOf(
                entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: demo.Main\r\n\r\n"),
                entry("demo/Main.class", TestClasses.compile("demo.Main", JAR_MAIN_SOURCE)),
                entry("demo/app.properties", "app.name=obfuscator\n"));
        MockMultipartFile file = new MockMultipartFile("file", "app.jar", "application/java-archive", jar);

        MvcResult result = mvc.perform(multipart("/api/v1/obfuscations/download").file(file).param("format", "JAR"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/java-archive"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"app-obfuscated.jar\""))
                .andExpect(header().string("X-Applied-Steps", "method-renaming"))
                .andExpect(header().string("X-Functionality-Status", "VERIFIED"))
                .andReturn();

        Map<String, byte[]> entries = readEntries(result.getResponse().getContentAsByteArray());
        assertThat(entries).containsKeys("META-INF/MANIFEST.MF", "demo/Main.class", "demo/app.properties");
        assertThat(entries.get("demo/app.properties")).isEqualTo("app.name=obfuscator\n".getBytes());
        assertThat(TestClasses.methodNames(entries.get("demo/Main.class"))).doesNotContain("computeTotal");
    }

    @Test
    void downloadBodyMatchesTheJsonPayloadForTheSameInput() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "Controller.class", null, TestClasses.compile(SUBJECT, SOURCE));

        byte[] downloaded = mvc
                .perform(multipart("/api/v1/obfuscations/download").file(file).param("format", "JVM_CLASS"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        String json = mvc.perform(multipart("/api/v1/obfuscations").file(file).param("format", "JVM_CLASS"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(downloaded).isEqualTo(Base64.getDecoder().decode(extractBase64(json, "outputBase64")));
    }

    @Test
    void streamsNativeArtifactsThroughUnchanged() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "lib.so", null, new byte[] {1, 2, 3});

        MvcResult result = mvc
                .perform(multipart("/api/v1/obfuscations/download").file(file).param("format", "NATIVE"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"lib-obfuscated.so\""))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(new byte[] {1, 2, 3});
        assertThat(headerOf(result, "X-Applied-Steps")).isNull();
        assertThat(headerOf(result, "X-Functionality-Status")).isEqualTo("UNVERIFIED");
    }

    @Test
    void rejectsInvalidClassOnTheDownloadPath() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "App.class", null, new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/v1/obfuscations/download").file(file).param("format", "JVM_CLASS"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("input is not a valid JVM class file"));
    }

    @Test
    void rejectsInvalidJarOnTheDownloadPath() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "app.jar", "application/java-archive", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/v1/obfuscations/download").file(file).param("format", "JAR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("input is not a valid JAR file"));
    }

    @Test
    void stripsUnsafeCharactersFromTheSuggestedFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "App\r.jar\"; x", null, TestClasses.compile(SUBJECT, SOURCE));

        mvc.perform(multipart("/api/v1/obfuscations/download").file(file).param("format", "JVM_CLASS"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"App-obfuscated.class\""));
    }

    @Test
    void defaultsTheSuggestedFilenameWhenTheUploadHasNoName() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", null, null, TestClasses.compile(SUBJECT, SOURCE));

        mvc.perform(multipart("/api/v1/obfuscations/download").file(file).param("format", "JVM_CLASS"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"obfuscated.class\""));
    }

    private static String headerOf(MvcResult result, String name) {
        return result.getResponse().getHeader(name);
    }

    @Test
    void keepsRequestedMethodsThroughTheJsonEndpoint() throws Exception {
        byte[] compiled = TestClasses.compile(SUBJECT, SOURCE);
        MockMultipartFile file = new MockMultipartFile("file", "Controller.class", null, compiled);

        String json = mvc.perform(multipart("/api/v1/obfuscations").file(file)
                        .param("format", "JVM_CLASS")
                        .param("keepMethods", "com.example.Controller#inner")
                        .param("keepMethods", "discardedMatchOnly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedSteps[0]").value("method-renaming"))
                .andExpect(jsonPath("$.functionalityStatus").value("VERIFIED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(Base64.getDecoder().decode(extractBase64(json, "outputBase64"))).isEqualTo(compiled);
    }

    @Test
    void keepsRequestedMethodsThroughTheDownloadEndpoint() throws Exception {
        byte[] compiled = TestClasses.compile(SUBJECT, SOURCE);
        MockMultipartFile file = new MockMultipartFile("file", "Controller.class", null, compiled);

        MvcResult result = mvc
                .perform(multipart("/api/v1/obfuscations/download").file(file)
                        .param("format", "JVM_CLASS")
                        .param("keepMethods", "inner"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Functionality-Status", "VERIFIED"))
                .andExpect(header().string("X-Sha256", sha256(compiled)))
                .andReturn();

        assertThat(TestClasses.methodNames(result.getResponse().getContentAsByteArray())).contains("inner");
    }

    @Test
    void appliesScopedKeepRulesToEveryClassInAJar() throws Exception {
        byte[] jar = jarOf(
                entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n"),
                entry("demo/Main.class", TestClasses.compile("demo.Main", JAR_MAIN_SOURCE)),
                entry("demo/Helper.class", TestClasses.compile("demo.Helper", JAR_HELPER_SOURCE)));
        MockMultipartFile file = new MockMultipartFile("file", "app.jar", "application/java-archive", jar);

        MvcResult result = mvc
                .perform(multipart("/api/v1/obfuscations/download").file(file)
                        .param("format", "JAR")
                        .param("keepMethods", "demo.Main#computeTotal"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Functionality-Status", "VERIFIED"))
                .andReturn();

        Map<String, byte[]> entries = readEntries(result.getResponse().getContentAsByteArray());
        assertThat(TestClasses.methodNames(entries.get("demo/Main.class"))).contains("computeTotal");
        assertThat(TestClasses.methodNames(entries.get("demo/Helper.class"))).doesNotContain("helper");
    }

    @Test
    void rejectsMalformedKeepRulesWithAClearErrorOnBothEndpoints() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "Controller.class", null, TestClasses.compile(SUBJECT, SOURCE));
        String detail = "invalid keepMethods rule: '#sum' (expected a method name or Owner#method)";

        mvc.perform(multipart("/api/v1/obfuscations").file(file).param("keepMethods", "#sum"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(detail));

        mvc.perform(multipart("/api/v1/obfuscations/download").file(file).param("keepMethods", "a b"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value("invalid keepMethods rule: 'a b' (expected a method name or Owner#method)"));
    }

    @Test
    void treatsBlankKeepParamsAsNoRules() throws Exception {
        byte[] compiled = TestClasses.compile(SUBJECT, SOURCE);
        MockMultipartFile file = new MockMultipartFile("file", "Controller.class", null, compiled);

        String json = mvc.perform(multipart("/api/v1/obfuscations").file(file).param("keepMethods", " "))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(Base64.getDecoder().decode(extractBase64(json, "outputBase64"))).isNotEqualTo(compiled);
    }
}