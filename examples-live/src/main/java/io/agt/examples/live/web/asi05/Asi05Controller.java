package io.agt.examples.live.web.asi05;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** ASI05 网页案例入口；请求只选择固定实验与模拟审批决定，不上传策略或权限。 */
@RestController
@RequestMapping("/api/examples/asi05")
public class Asi05Controller {
    public record RunInput(String message, String approval) {}

    private final Asi05Service service;

    public Asi05Controller(Asi05Service service) {
        this.service = service;
    }

    @GetMapping
    public List<Asi05Catalog.Entry> catalog() {
        return Asi05Catalog.ENTRIES;
    }

    @GetMapping("/{id}/source")
    public Map<String, String> source(@PathVariable String id) throws IOException {
        return Map.of(
                "code",
                read(Asi05Catalog.entry(id).source()),
                "rules",
                read("ExecutionCase.java"),
                "runner",
                read("Asi05Service.java")
                        + "\n"
                        + new ClassPathResource("experiment-examples/ExperimentRunner.java")
                                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static String read(String file) throws IOException {
        return new ClassPathResource("asi05-examples/" + file)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping("/{id}/run")
    public Asi05Service.Comparison run(@PathVariable String id, @RequestBody RunInput input) {
        Asi05Catalog.entry(id);

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
