package io.agt.examples.live.web.streaming;

import io.agt.langchain4j.StreamDelivery;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/examples/streaming")
public class StreamingExamplesController {
    public record RunInput(String mode, String provider, String message) {}

    private final StreamingExamplesService service;
    private final String frontendOrigin;

    public StreamingExamplesController(
            StreamingExamplesService service,
            @Value("${agt.examples.frontend-origin:http://127.0.0.1:3000}") String frontendOrigin) {
        this.service = service;
        this.frontendOrigin = frontendOrigin;
    }

    @GetMapping
    public List<StreamingCases.Entry> catalog() {
        return StreamingCases.ENTRIES;
    }

    @GetMapping("/{id}/source")
    public Map<String, String> source(@PathVariable String id) throws IOException {
        StreamingCases.entry(id);
        return Map.of(
                "code",
                read("StreamingCases.java"),
                "configuration",
                read("StreamingExamplesService.java")
                        + "\n"
                        + read("StreamingExamplesConfiguration.java"));
    }

    private String read(String file) throws IOException {
        return new ClassPathResource("streaming-examples/" + file)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping(value = "/{id}/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(
            @PathVariable String id,
            @RequestBody RunInput input,
            HttpSession session,
            @RequestHeader(value = "Origin", required = false) String origin) {
        checkOrigin(origin);
        var entry = StreamingCases.entry(id);
        if (input.provider() == null
                || !List.of("fixture", "live").contains(input.provider())
                || input.mode() == null
                || !List.of("REALTIME", "GUARDED", "STRICT").contains(input.mode())
                || input.message() == null
                || input.message().isBlank()
                || input.message().length() > 16000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_streaming_run");
        return service.start(
                session.getId(),
                entry,
                StreamDelivery.valueOf(input.mode()),
                input.provider(),
                input.message());
    }

    @PostMapping("/runs/{id}/cancel")
    public Map<String, Boolean> cancel(
            @PathVariable String id,
            HttpSession session,
            @RequestHeader(value = "Origin", required = false) String origin) {
        checkOrigin(origin);
        service.cancel(session.getId(), id);
        return Map.of("ok", true);
    }

    private void checkOrigin(String origin) {
        if (origin != null
                && !origin.equals(frontendOrigin)
                && !List.of(
                                "http://localhost:3000",
                                "http://127.0.0.1:3000",
                                "http://localhost:8080",
                                "http://127.0.0.1:8080")
                        .contains(origin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "origin_denied");
    }
}
