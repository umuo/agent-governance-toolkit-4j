package io.agt.examples.live.web.asi02;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** ASI02 网页案例入口；请求只选择固定实验与模拟审批决定，不上传策略或权限。 */
@RestController
@RequestMapping("/api/examples/asi02")
public class Asi02Controller {
    public record RunInput(String message, String approval) {}

    private final Asi02Service service;
    private final String frontendOrigin;

    public Asi02Controller(
            Asi02Service service,
            @Value("${agt.examples.frontend-origin:http://127.0.0.1:3000}") String frontendOrigin) {
        this.service = service;
        this.frontendOrigin = frontendOrigin;
    }

    @GetMapping
    public List<Asi02Catalog.Entry> catalog() {
        return Asi02Catalog.ENTRIES;
    }

    @GetMapping("/{id}/source")
    public Map<String, String> source(@PathVariable String id) throws IOException {
        return Map.of(
                "code",
                read(Asi02Catalog.entry(id).source()),
                "rules",
                read("Rules.java"),
                "runner",
                read("Asi02Service.java")
                        + "\n"
                        + new ClassPathResource("experiment-examples/ExperimentRunner.java")
                                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static String read(String file) throws IOException {
        return new ClassPathResource("asi02-examples/" + file)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping("/{id}/run")
    public Asi02Service.Comparison run(
            @PathVariable String id,
            @RequestBody RunInput input,
            @RequestHeader(value = "Origin", required = false) String origin) {
        Asi02Catalog.entry(id);
        if (origin != null
                && !origin.equals(frontendOrigin)
                && !List.of(
                                "http://localhost:3000",
                                "http://127.0.0.1:3000",
                                "http://localhost:8080",
                                "http://127.0.0.1:8080")
                        .contains(origin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "origin_denied");
        }
        if (input.message() == null
                || input.message().isBlank()
                || input.message().length() > 16000
                || !List.of("deny", "allow")
                        .contains(input.approval() == null ? "" : input.approval())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_experiment");
        }
        return service.compare(id, input.message(), input.approval());
    }
}
