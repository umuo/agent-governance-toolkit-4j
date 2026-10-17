package io.agt.examples.live.web.risks;

import io.agt.examples.live.web.experiment.ExperimentRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 十类统一导航与八类新增固定案例入口；不接受上传类名、权限、制品或代码。 */
@RestController
@RequestMapping("/api/examples")
public final class RiskController {
    private static final String RISK = "{risk:asi01|asi03|asi04|asi06|asi07|asi08|asi09|asi10}";

    public record Input(String message, String approval) {}

    private final RiskService service;
    private final String frontendOrigin;

    public RiskController(
            RiskService service,
            @Value("${agt.examples.frontend-origin:http://127.0.0.1:3000}") String frontendOrigin) {
        this.service = service;
        this.frontendOrigin = frontendOrigin;
    }

    @GetMapping("/risks")
    public List<RiskCatalog.Category> categories() {
        return RiskCatalog.categories();
    }

    @GetMapping("/" + RISK)
    public List<RiskCatalog.Entry> catalog(@PathVariable String risk) {
        return RiskCatalog.category(risk).cases();
    }

    @GetMapping("/" + RISK + "/{id}/source")
    public Map<String, String> source(@PathVariable String risk, @PathVariable String id)
            throws IOException {
        var entry = RiskCatalog.entry(risk, id);
        String helpers = read("risk-examples/LearningCase.java");
        if (risk.equals("asi07")) helpers += "\n" + read("risk-examples/SignedChannel.java");
        return Map.of(
                "code",
                read("risk-examples/" + risk + "/" + entry.source()),
                "rules",
                helpers,
                "runner",
                read("risk-examples/RiskService.java")
                        + "\n"
                        + read("experiment-examples/ExperimentRunner.java"));
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    @PostMapping("/" + RISK + "/{id}/run")
    public ExperimentRunner.Comparison run(
            @PathVariable String risk,
            @PathVariable String id,
            @RequestBody Input input,
            @RequestHeader(value = "Origin", required = false) String origin) {
        RiskCatalog.entry(risk, id);
        if (origin != null
                && !origin.equals(frontendOrigin)
                && !List.of(
                                "http://localhost:3000",
                                "http://127.0.0.1:3000",
                                "http://localhost:8080",
                                "http://127.0.0.1:8080")
                        .contains(origin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "origin_denied");
        if (input.message() == null
                || input.message().isBlank()
                || input.message().length() > 16000
                || !List.of("deny", "allow")
                        .contains(input.approval() == null ? "" : input.approval()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_experiment");
        return service.compare(id, input.message(), input.approval());
    }
}
