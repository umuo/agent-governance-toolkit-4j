package io.agt.examples.live.web.tracing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TraceBodiesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void numericRepresentationMatchesPersistedJson() throws Exception {
        var source = json.createObjectNode().put("duration", 120000L).put("large", Long.MAX_VALUE);
        var captured = TraceBodies.capture("task_budget", source, 65536);
        assertEquals(captured.body(), json.readTree(captured.body().toString()));
        assertEquals(Long.MAX_VALUE, captured.body().path("large").asLong());
    }

    @Test
    void preservesStructureAndRedactsCredentialFieldsWithoutChangingOriginal() throws Exception {
        var source =
                json.readTree(
                        "{\"api_key\":\"private-key\",\"nested\":{\"Authorization\":\"Bearer private-token\"},\"messages\":[{\"content\":\"normal text Bearer another-token sk-abcdefghijklmnopqrstuvwx\"}],\"usage\":{\"total_tokens\":23}}");
        var payload = TraceBodies.capture("http_request", source, 65536);
        assertFalse(payload.truncated());
        assertTrue(payload.body().path("messages").isArray());
        assertEquals(23, payload.body().path("usage").path("total_tokens").asInt());
        String body = payload.body().toString();
        assertFalse(body.contains("private-key"));
        assertFalse(body.contains("private-token"));
        assertFalse(body.contains("another-token"));
        assertFalse(body.contains("abcdefghijklmnopqrstuvwx"));
        assertTrue(body.contains("normal text"));
        assertEquals("private-key", source.path("api_key").asText());
    }

    @Test
    void oversizedBodyIsExplicitlyMarkedAndFitsByteBudget() {
        var source = json.createObjectNode().put("content", "正文😀".repeat(50000));
        var result = TraceBodies.capture("http_response", source, 4096);
        assertTrue(result.truncated());
        assertTrue(result.body().isTextual());
        assertTrue(result.originalBytes() > 4096);
        assertTrue(TraceBodies.bytes(result.body()) <= 4096);
        assertFalse(result.body().asText().contains("\ufffd"));
        var omitted = TraceBodies.capture("http_response", source, 0);
        assertTrue(omitted.truncated());
        assertTrue(omitted.body().isNull());
    }
}
