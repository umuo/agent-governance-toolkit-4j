package io.agt.examples.live.web.tracing;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 本机示例 API；生产宿主需增加认证和租户过滤。 */
@RestController
@RequestMapping("/api/agent-traces")
public final class TraceController {
    private final MultiAgentTraceService service;

    public TraceController(MultiAgentTraceService service) {
        this.service = service;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> failure(
            ResponseStatusException error) {
        var allowed =
                java.util.Set.of(
                        "trace_demo_busy",
                        "trace_model_unavailable",
                        "trace_store_full",
                        "trace_store_unavailable",
                        "trace_start_timeout",
                        "trace_start_failed",
                        "unknown_trace");
        String code =
                allowed.contains(error.getReason() == null ? "" : error.getReason())
                        ? error.getReason()
                        : "trace_request_failed";
        return org.springframework.http.ResponseEntity.status(error.getStatusCode())
                .body(Map.of("code", code));
    }

    public record Summary(String traceId, long createdAt, String status) {}

    @GetMapping
    public List<Summary> list() {
        return service.store().list().stream()
                .map(view -> new Summary(view.traceId(), view.createdAt(), view.status()))
                .toList();
    }

    @GetMapping("/{id}")
    public TraceStore.View get(@PathVariable String id) {
        return service.store().get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> start() {

        return Map.of("traceId", service.start());
    }
}
