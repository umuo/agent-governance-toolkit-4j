package io.agt.core.policy;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agt.core.BudgetLimits;
import io.agt.core.GovernanceException;
import io.agt.core.Identifiers;
import io.agt.core.InterventionPoint;
import io.agt.core.PolicyDecision;
import io.agt.core.PolicyEvaluator;
import io.agt.core.PolicyRequest;
import io.agt.core.data.DataLimits;
import io.agt.core.data.DataResource;
import io.agt.core.data.DataRule;
import io.agt.core.injection.InjectionCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于结构化文档的确定性策略评估器实现。
 *
 * <p>不执行表达式、不访问网络、策略实例启动后不可变。
 */
public final class StructuredPolicy implements PolicyEvaluator {
    public record Document(
            String apiVersion,
            String version,
            Set<InterventionPoint> interventionPoints,
            BudgetLimits budget,
            Map<String, ToolRule> tools,
            List<ContentRule> contentRules,
            Map<String, DataRule> resources,
            DataLimits dataLimits,
            List<InjectionCheck> injectionChecks) {
        public Document(
                String apiVersion,
                String version,
                Set<InterventionPoint> points,
                BudgetLimits budget,
                Map<String, ToolRule> tools,
                List<ContentRule> rules,
                Map<String, DataRule> resources,
                DataLimits dataLimits) {
            this(
                    apiVersion,
                    version,
                    points,
                    budget,
                    tools,
                    rules,
                    resources,
                    dataLimits,
                    List.of());
        }

        public Document(
                String apiVersion,
                String version,
                Set<InterventionPoint> points,
                BudgetLimits budget,
                Map<String, ToolRule> tools,
                List<ContentRule> rules) {
            this(
                    apiVersion,
                    version,
                    points,
                    budget,
                    tools,
                    rules,
                    Map.of(),
                    DataLimits.defaults());
        }
    }

    private final Document doc;

    @Override
    public String toolBinding(String tool) {
        if (tool == null) return null;
        var rule = doc.tools().get(tool);
        return rule == null ? null : rule.binding();
    }

    @Override
    public io.agt.core.effects.EffectRule effectRule(String tool) {
        if (tool == null) return null;
        var rule = doc.tools().get(tool);
        return rule == null ? null : rule.effect();
    }

    public StructuredPolicy(Document document) {
        if (!"agt.java/v1".equals(document.apiVersion())) {
            throw new IllegalArgumentException("Unsupported policy version");
        }
        Identifiers.require(document.version());
        var points = Set.copyOf(Objects.requireNonNull(document.interventionPoints()));
        var tools = Map.copyOf(document.tools() == null ? Map.of() : document.tools());
        tools.keySet().forEach(Identifiers::require);
        var resources = Map.copyOf(document.resources() == null ? Map.of() : document.resources());
        resources.keySet().forEach(Identifiers::require);
        if (!resources.isEmpty()
                && !points.containsAll(
                        Set.of(
                                InterventionPoint.PRE_DATA_ACCESS,
                                InterventionPoint.POST_DATA_ACCESS))) {
            throw new IllegalArgumentException("Missing data intervention points");
        }
        var rules =
                List.copyOf(document.contentRules() == null ? List.of() : document.contentRules());
        Set<String> ids = new HashSet<>();
        for (ContentRule rule : rules) {
            if (!points.containsAll(rule.points()) || !ids.add(rule.id())) {
                throw new IllegalArgumentException("Invalid content rule binding");
            }
        }
        var checks =
                List.copyOf(
                        document.injectionChecks() == null
                                ? List.<InjectionCheck>of()
                                : document.injectionChecks());
        if (checks.size() > 16) throw new IllegalArgumentException("Too many injection checks");
        var checkIds = new HashSet<String>();
        for (var check : checks)
            if (!points.containsAll(check.points()) || !checkIds.add(check.id()))
                throw new IllegalArgumentException("Invalid injection check binding");
        doc =
                new Document(
                        document.apiVersion(),
                        document.version(),
                        points,
                        Objects.requireNonNull(document.budget()),
                        tools,
                        rules,
                        resources,
                        document.dataLimits() == null
                                ? DataLimits.defaults()
                                : document.dataLimits(),
                        checks);
    }

    @Override
    public List<InjectionCheck> injectionChecks() {
        return doc.injectionChecks();
    }

    public static StructuredPolicy fromPath(Path path) {
        try {
            if (Files.size(path) > 1_048_576) {
                throw new IllegalArgumentException();
            }
            return fromYaml(Files.readString(path));
        } catch (Exception e) {
            throw new GovernanceException("invalid_policy");
        }
    }

    public static StructuredPolicy fromYaml(String yaml) {
        try {
            if (yaml == null || yaml.length() > 1_048_576) {
                throw new IllegalArgumentException();
            }
            var factory =
                    YAMLFactory.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .streamReadConstraints(
                                    StreamReadConstraints.builder()
                                            .maxNestingDepth(32)
                                            .maxStringLength(1_048_576)
                                            .maxNumberLength(128)
                                            .build())
                            .build();
            var mapper =
                    JsonMapper.builder(factory)
                            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                            .build();
            return new StructuredPolicy(mapper.readValue(yaml, Document.class));
        } catch (Exception e) {
            throw new GovernanceException("invalid_policy");
        }
    }

    public static Builder builder(String version) {
        return new Builder(version);
    }

    @Override
    public String version() {
        return doc.version();
    }

    public Document document() {
        return doc;
    }

    @Override
    public Set<InterventionPoint> interventionPoints() {
        return doc.interventionPoints();
    }

    public BudgetLimits budget() {
        return doc.budget();
    }

    public DataLimits dataLimits() {
        return doc.dataLimits();
    }

    @Override
    public PolicyDecision evaluate(PolicyRequest r) {
        try {
            validateEffective(r);
        } catch (GovernanceException e) {
            return PolicyDecision.deny(e.code());
        }
        Object effective = r.target();
        String lastRule = "content_rewritten";
        for (var rule : doc.contentRules()) {
            if (rule.points().contains(r.point())) {
                effective = rule.transform(effective);
                lastRule = rule.id();
            }
        }
        if (!Objects.equals(effective, r.target())) {
            return PolicyDecision.transform(lastRule, effective);
        }
        if (r.point() == InterventionPoint.PRE_TOOL_CALL
                && doc.tools().get(r.toolName()).approvalRequired()) {
            return PolicyDecision.approval("tool_approval_required");
        }
        return PolicyDecision.allow();
    }

    @Override
    public void validateEffective(PolicyRequest r) {
        if (!doc.interventionPoints().contains(r.point())) {
            throw new GovernanceException("point_not_configured");
        }
        if (r.point() == InterventionPoint.PRE_TOOL_CALL) {
            ToolRule rule = doc.tools().get(r.toolName());
            if (rule == null) {
                throw new GovernanceException("tool_not_allowed");
            }
            if (!java.util.Objects.equals(rule.binding(), r.toolBinding()))
                throw new GovernanceException("tool_version_binding_denied");
            if (!r.context().permissions().containsAll(rule.permissions())) {
                throw new GovernanceException("permission_denied");
            }
            rule.parameters().validate(r.target(), r.context());
        }
        if (r.point() == InterventionPoint.PRE_DATA_ACCESS
                || r.point() == InterventionPoint.POST_DATA_ACCESS) {
            var resource = DataResource.resource(r.target());
            var rule = doc.resources().get(resource.get("name"));
            if (rule == null) {
                throw new GovernanceException("data_resource_denied");
            }
            rule.validate(r);
        }
        for (var rule : doc.contentRules()) {
            if (rule.points().contains(r.point()) && rule.denies(r.target())) {
                throw new GovernanceException(rule.id());
            }
        }
    }

    /**
     * 改写后的工具仍可能需要审批；供宿主在执行前独立判断。
     *
     * @param r 策略请求
     * @return 是否需要审批
     */
    @Override
    public boolean requiresApproval(PolicyRequest r) {
        return r.point() == InterventionPoint.PRE_TOOL_CALL
                && doc.tools().get(r.toolName()) != null
                && doc.tools().get(r.toolName()).approvalRequired();
    }

    public static final class Builder {
        private final String version;
        private Set<InterventionPoint> points = InterventionPoint.lifecyclePoints();
        private BudgetLimits budget = BudgetLimits.defaults();
        private DataLimits dataLimits = DataLimits.defaults();
        private final Map<String, DataRule> resources = new LinkedHashMap<>();
        private final Map<String, ToolRule> tools = new LinkedHashMap<>();
        private final List<ContentRule> rules = new ArrayList<>();
        private final List<InjectionCheck> checks = new ArrayList<>();

        private Builder(String version) {
            this.version = version;
        }

        public Builder points(Set<InterventionPoint> points) {
            this.points = Set.copyOf(points);
            return this;
        }

        public Builder budget(BudgetLimits budget) {
            this.budget = budget;
            return this;
        }

        public Builder tool(String name, ToolRule rule) {
            if (tools.putIfAbsent(name, rule) != null) {
                throw new IllegalArgumentException("Duplicate tool");
            }
            return this;
        }

        public Builder resource(String name, DataRule rule) {
            if (resources.putIfAbsent(name, Objects.requireNonNull(rule)) != null) {
                throw new IllegalArgumentException("Duplicate resource");
            }
            var expanded = EnumSet.copyOf(points);
            expanded.add(InterventionPoint.PRE_DATA_ACCESS);
            expanded.add(InterventionPoint.POST_DATA_ACCESS);
            points = expanded;
            return this;
        }

        public Builder dataLimits(DataLimits value) {
            dataLimits = Objects.requireNonNull(value);
            return this;
        }

        public Builder content(ContentRule rule) {
            rules.add(rule);
            return this;
        }

        public Builder injection(InjectionCheck check) {
            checks.add(Objects.requireNonNull(check));
            return this;
        }

        public StructuredPolicy build() {
            return new StructuredPolicy(
                    new Document(
                            "agt.java/v1",
                            version,
                            points,
                            budget,
                            tools,
                            rules,
                            resources,
                            dataLimits,
                            checks));
        }
    }
}
