package io.agt.core.policy;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agt.core.*;
import java.nio.file.*;
import java.util.*;

/** 不执行表达式、不访问网络、策略实例启动后不可变。 */
public final class StructuredPolicy implements PolicyEvaluator {
  public record Document(
      String apiVersion,
      String version,
      Set<InterventionPoint> interventionPoints,
      BudgetLimits budget,
      Map<String, ToolRule> tools,
      List<ContentRule> contentRules) {}

  private final Document doc;

  public StructuredPolicy(Document document) {
    if (!"agt.java/v1".equals(document.apiVersion()))
      throw new IllegalArgumentException("Unsupported policy version");
    Identifiers.require(document.version());
    var points = Set.copyOf(Objects.requireNonNull(document.interventionPoints()));
    var tools = Map.copyOf(document.tools() == null ? Map.of() : document.tools());
    tools.keySet().forEach(Identifiers::require);
    var rules = List.copyOf(document.contentRules() == null ? List.of() : document.contentRules());
    Set<String> ids = new HashSet<>();
    for (ContentRule rule : rules) {
      if (!points.containsAll(rule.points()) || !ids.add(rule.id()))
        throw new IllegalArgumentException("Invalid content rule binding");
    }
    doc =
        new Document(
            document.apiVersion(),
            document.version(),
            points,
            Objects.requireNonNull(document.budget()),
            tools,
            rules);
  }

  public static StructuredPolicy fromPath(Path path) {
    try {
      if (Files.size(path) > 1_048_576) throw new IllegalArgumentException();
      return fromYaml(Files.readString(path));
    } catch (Exception e) {
      throw new GovernanceException("invalid_policy");
    }
  }

  public static StructuredPolicy fromYaml(String yaml) {
    try {
      if (yaml == null || yaml.length() > 1_048_576) throw new IllegalArgumentException();
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

  @Override
  public Set<InterventionPoint> interventionPoints() {
    return doc.interventionPoints();
  }

  public BudgetLimits budget() {
    return doc.budget();
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
    for (var rule : doc.contentRules())
      if (rule.points().contains(r.point())) {
        effective = rule.transform(effective);
        lastRule = rule.id();
      }
    if (!Objects.equals(effective, r.target()))
      return PolicyDecision.transform(lastRule, effective);
    if (r.point() == InterventionPoint.PRE_TOOL_CALL
        && doc.tools().get(r.toolName()).approvalRequired())
      return PolicyDecision.approval("tool_approval_required");
    return PolicyDecision.allow();
  }

  @Override
  public void validateEffective(PolicyRequest r) {
    if (!doc.interventionPoints().contains(r.point()))
      throw new GovernanceException("point_not_configured");
    if (r.point() == InterventionPoint.PRE_TOOL_CALL) {
      ToolRule rule = doc.tools().get(r.toolName());
      if (rule == null) throw new GovernanceException("tool_not_allowed");
      if (!r.context().permissions().containsAll(rule.permissions()))
        throw new GovernanceException("permission_denied");
      rule.parameters().validate(r.target(), r.context());
    }
    for (var rule : doc.contentRules())
      if (rule.points().contains(r.point()) && rule.denies(r.target()))
        throw new GovernanceException(rule.id());
  }

  /** 改写后的工具仍可能需要审批；供宿主在执行前独立判断。 */
  public boolean requiresApproval(PolicyRequest r) {
    return r.point() == InterventionPoint.PRE_TOOL_CALL
        && doc.tools().get(r.toolName()) != null
        && doc.tools().get(r.toolName()).approvalRequired();
  }

  public static final class Builder {
    private final String version;
    private Set<InterventionPoint> points = EnumSet.allOf(InterventionPoint.class);
    private BudgetLimits budget = BudgetLimits.defaults();
    private final Map<String, ToolRule> tools = new LinkedHashMap<>();
    private final List<ContentRule> rules = new ArrayList<>();

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
      if (tools.putIfAbsent(name, rule) != null)
        throw new IllegalArgumentException("Duplicate tool");
      return this;
    }

    public Builder content(ContentRule rule) {
      rules.add(rule);
      return this;
    }

    public StructuredPolicy build() {
      return new StructuredPolicy(
          new Document("agt.java/v1", version, points, budget, tools, rules));
    }
  }
}
