package com.obfuscator.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Exercises the multipart limits through a real servlet container: MockMvc
 * bypasses multipart parsing entirely, so it cannot prove them. The limits are
 * overridden to small values to keep the test fast; {@code UploadLimitDefaultsTests}
 * asserts the production values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=4KB",
        "spring.servlet.multipart.max-request-size=6KB"
})
class UploadLimitTests {

    private static final String BOUNDARY = "----obfuscator-upload-limit";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void acceptsAnUploadJustBelowTheFileLimit() throws Exception {
        HttpResponse<String> response = post("/api/v1/obfuscations",
                multipart(List.of(file("payload.bin", new byte[3000]), field("format", "NATIVE"))));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"functionalityStatus\":\"UNVERIFIED\"");
    }

    @Test
    void acceptsADownloadJustBelowTheFileLimit() throws Exception {
        HttpResponse<String> response = post("/api/v1/obfuscations/download",
                multipart(List.of(file("payload.bin", new byte[3000]), field("format", "NATIVE"))));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsAnUploadAboveTheFileLimitWithAProblemDetail() throws Exception {
        HttpResponse<String> response = post("/api/v1/obfuscations",
                multipart(List.of(file("payload.bin", new byte[5000]), field("format", "NATIVE"))));

        assertThatProblem(response, 413);
    }

    @Test
    void rejectsADownloadAboveTheFileLimitWithAProblemDetail() throws Exception {
        HttpResponse<String> response = post("/api/v1/obfuscations/download",
                multipart(List.of(file("payload.bin", new byte[5000]), field("format", "NATIVE"))));

        assertThatProblem(response, 413);
    }

    @Test
    void rejectsAnUploadAboveTheMultipartRequestLimit() throws Exception {
        HttpResponse<String> response = post("/api/v1/obfuscations", multipart(List.of(
                file("payload.bin", new byte[3000]),
                field("format", "NATIVE"),
                field("keepMethods", "x".repeat(4000)))));

        assertThatProblem(response, 413);
    }

    private static void assertThatProblem(HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body())
                .contains("\"status\":" + expectedStatus)
                .contains("maximum allowed size");
    }

    private HttpResponse<String> post(String path, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(List<Part> parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Part part : parts) {
            write(body, "--" + BOUNDARY + "\r\n");
            if (part.filename() == null) {
                write(body, "Content-Disposition: form-data; name=\"" + part.name() + "\"\r\n\r\n");
            } else {
                write(body, "Content-Disposition: form-data; name=\"" + part.name()
                        + "\"; filename=\"" + part.filename() + "\"\r\n");
                write(body, "Content-Type: application/octet-stream\r\n\r\n");
            }
            body.writeBytes(part.content());
            write(body, "\r\n");
        }
        write(body, "--" + BOUNDARY + "--\r\n");
        return body.toByteArray();
    }

    private static Part file(String name, byte[] content) {
        return new Part("file", "payload.bin", content);
    }

    private static Part field(String name, String value) {
        return new Part(name, null, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private record Part(String name, String filename, byte[] content) {
    }
}
