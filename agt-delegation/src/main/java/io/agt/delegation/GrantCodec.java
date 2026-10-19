package io.agt.delegation;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.agt.core.*;
import io.agt.core.data.*;
import io.agt.core.delegation.*;
import io.agt.core.flow.*;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.usage.UsageBudget;
import io.agt.core.usage.UsageLimits;
import java.io.IOException;
import java.util.*;

/** 有界、明确 DTO 的状态协议；资源使用列表，避免复杂对象作为 JSON map key。 */
public final class GrantCodec {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    static {
        var module = new SimpleModule();
        @SuppressWarnings("rawtypes")
        Class<Set> type = Set.class;
        module.addSerializer(
                type,
                new JsonSerializer<Set>() {
                    public void serialize(
                            Set values, JsonGenerator generator, SerializerProvider provider)
                            throws IOException {
                        var sorted = new ArrayList<Object>(values);
                        if (sorted.stream()
                                .anyMatch(
                                        value ->
                                                !(value instanceof String)
                                                        && !(value instanceof Enum<?>)))
                            throw new IOException("Unsupported grant set");
                        sorted.sort(Comparator.comparing(Object::toString));
                        generator.writeStartArray();
                        for (Object value : sorted) generator.writeObject(value);
                        generator.writeEndArray();
                    }
                });
        JSON.registerModule(module);
    }

    private GrantCodec() {}

    public record Resource(DataResource resource, Set<DataOperation> operations) {}

    public record Frame(
            TrustedContext owner,
            String taskName,
            boolean modelAllowed,
            Map<String, ParameterRule> tools,
            List<Resource> resources) {}

    public record Document(
            String id,
            String issuer,
            String audience,
            TrustedContext identity,
            String taskId,
            String parentId,
            int depth,
            int maxDepth,
            long issuedAt,
            long expiresAt,
            List<Frame> frames,
            DelegationQuota quota,
            String policyDigest,
            TrustedContext policyOwner,
            BudgetLimits sessionBudget,
            DataLimits dataLimits,
            UsageLimits usageLimits,
            String flowPolicy,
            Map<String, String> toolBindings) {}

    public static String encode(GrantDocument grant) {
        var frames = frames(grant.frames());
        var doc =
                new Document(
                        grant.id(),
                        grant.issuer(),
                        grant.audience(),
                        grant.identity(),
                        grant.taskId(),
                        grant.parentId(),
                        grant.depth(),
                        grant.maxDepth(),
                        grant.issuedAt(),
                        grant.expiresAt(),
                        frames,
                        grant.quota(),
                        grant.policyDigest(),
                        grant.policyOwner(),
                        grant.sessionBudget(),
                        grant.dataLimits(),
                        grant.usageLimits(),
                        grant.flowPolicy(),
                        grant.toolBindings());
        var encoded = JSON.convertValue(doc, java.util.LinkedHashMap.class);
        if (grant.toolBindings().isEmpty()) encoded.remove("toolBindings");
        return JsonValues.write(encoded);
    }

    private static List<Frame> frames(List<AuthorityFrame> lineage) {
        return lineage.stream()
                .map(
                        frame ->
                                new Frame(
                                        frame.owner(),
                                        frame.scope().taskName(),
                                        frame.scope().modelAllowed(),
                                        frame.scope().tools(),
                                        frame.scope().resources().entrySet().stream()
                                                .map(
                                                        entry ->
                                                                new Resource(
                                                                        entry.getKey(),
                                                                        entry.getValue()))
                                                .sorted(
                                                        Comparator.comparing(
                                                                entry ->
                                                                        JsonValues.write(
                                                                                entry.resource()
                                                                                        .descriptor())))
                                                .toList()))
                .toList();
    }

    public static String lineageDigest(List<AuthorityFrame> lineage) {
        return JsonValues.digest(JSON.convertValue(frames(lineage), Object.class));
    }

    public static GrantDocument decode(String encoded) {
        try {
            Object checked = JsonValues.parse(encoded);
            var doc = JSON.convertValue(checked, Document.class);
            var frames =
                    doc.frames().stream()
                            .map(
                                    frame -> {
                                        var resources =
                                                new LinkedHashMap<
                                                        DataResource, Set<DataOperation>>();
                                        for (var resource : frame.resources()) {
                                            if (resources.put(
                                                            resource.resource(),
                                                            resource.operations())
                                                    != null) throw new IllegalArgumentException();
                                        }
                                        return new AuthorityFrame(
                                                frame.owner(),
                                                new DelegationScope(
                                                        frame.taskName(),
                                                        frame.modelAllowed(),
                                                        frame.tools(),
                                                        resources));
                                    })
                            .toList();
            return new GrantDocument(
                    doc.id(),
                    doc.issuer(),
                    doc.audience(),
                    doc.identity(),
                    doc.taskId(),
                    doc.parentId(),
                    doc.depth(),
                    doc.maxDepth(),
                    doc.issuedAt(),
                    doc.expiresAt(),
                    frames,
                    doc.quota(),
                    doc.policyDigest(),
                    doc.policyOwner(),
                    doc.sessionBudget(),
                    doc.dataLimits(),
                    doc.usageLimits(),
                    doc.flowPolicy(),
                    doc.toolBindings());
        } catch (RuntimeException invalid) {
            throw new GovernanceException("delegation_document_denied");
        }
    }

    public static String digest(GrantDocument grant) {
        return JsonValues.digest(JsonValues.parse(encode(grant)));
    }

    public record LabeledResource(DataResource resource, DataLabel label) {}

    public record FlowDocument(
            List<LabeledResource> resources,
            Map<String, DataLabel> sources,
            Map<String, DataFlowPolicy.SinkRule> sinks,
            Set<String> neutral,
            DataLabel input,
            DataLabel model,
            DataLabel output) {}

    public static String encodeFlowPolicy(DataFlowPolicy policy) {
        var resources =
                policy.resources().entrySet().stream()
                        .map(entry -> new LabeledResource(entry.getKey(), entry.getValue()))
                        .sorted(
                                Comparator.comparing(
                                        entry -> JsonValues.write(entry.resource().descriptor())))
                        .toList();
        return JsonValues.write(
                JSON.convertValue(
                        new FlowDocument(
                                resources,
                                policy.sourceTools(),
                                policy.sinkTools(),
                                policy.neutralTools(),
                                policy.inputLabel(),
                                policy.modelClearance(),
                                policy.outputClearance()),
                        Object.class));
    }

    public static DataFlowPolicy decodeFlowPolicy(String encoded) {
        try {
            var document = JSON.convertValue(JsonValues.parseObject(encoded), FlowDocument.class);
            var resources = new LinkedHashMap<DataResource, DataLabel>();
            for (var entry : document.resources())
                if (resources.put(entry.resource(), entry.label()) != null)
                    throw new IllegalArgumentException();
            return new DataFlowPolicy(
                    resources,
                    document.sources(),
                    document.sinks(),
                    document.neutral(),
                    document.input(),
                    document.model(),
                    document.output());
        } catch (RuntimeException invalid) {
            throw new GovernanceException("delegation_document_denied");
        }
    }

    public static String encodeFlowSnapshot(DataFlowState.Snapshot snapshot) {
        return JsonValues.write(JSON.convertValue(snapshot, Object.class));
    }

    public static DataFlowState.Snapshot decodeFlowSnapshot(String encoded) {
        try {
            var value = JsonValues.parseObject(encoded);
            if (!value.keySet().equals(Set.of("label", "sources", "completedTools"))
                    || !(value.get("label") instanceof String)
                    || !(value.get("sources") instanceof List<?> refs)
                    || !(value.get("completedTools") instanceof List<?> steps)
                    || refs.stream().anyMatch(item -> !(item instanceof String))
                    || steps.stream().anyMatch(item -> !(item instanceof String)))
                throw new IllegalArgumentException();
            return JSON.convertValue(value, DataFlowState.Snapshot.class);
        } catch (RuntimeException invalid) {
            throw new GovernanceException("delegation_document_denied");
        }
    }

    public static String encodeUsageSnapshot(UsageBudget.Snapshot snapshot) {
        return JsonValues.write(JSON.convertValue(snapshot, Object.class));
    }

    public static UsageBudget.Snapshot decodeUsageSnapshot(String encoded) {
        try {
            var value = JsonValues.parseObject(encoded);
            if (!value.keySet()
                    .equals(
                            Set.of(
                                    "settledTokens",
                                    "settledCost",
                                    "heldTokens",
                                    "heldCost",
                                    "inFlightCalls",
                                    "uncertainCalls",
                                    "maxTokens",
                                    "maxCost",
                                    "currency",
                                    "fault"))) throw new IllegalArgumentException();
            for (var field :
                    Set.of(
                            "settledTokens",
                            "heldTokens",
                            "inFlightCalls",
                            "uncertainCalls",
                            "maxTokens"))
                if (!(value.get(field) instanceof java.math.BigInteger))
                    throw new IllegalArgumentException();
            for (var field : Set.of("settledCost", "heldCost", "maxCost")) {
                var number = value.get(field);
                if (!(number instanceof java.math.BigInteger)
                        && !(number instanceof java.math.BigDecimal))
                    throw new IllegalArgumentException();
                var decimal = new java.math.BigDecimal(number.toString());
                if (decimal.precision() > 64 || Math.abs((long) decimal.scale()) > 18)
                    throw new IllegalArgumentException();
            }
            if (!(value.get("currency") instanceof String)
                    || (value.get("fault") != null && !(value.get("fault") instanceof String)))
                throw new IllegalArgumentException();
            var snapshot = JSON.convertValue(value, UsageBudget.Snapshot.class);
            if (snapshot.settledTokens() < 0
                    || snapshot.heldTokens() < 0
                    || snapshot.inFlightCalls() < 0
                    || snapshot.uncertainCalls() < 0
                    || snapshot.maxTokens() < 0
                    || snapshot.settledCost().signum() < 0
                    || snapshot.heldCost().signum() < 0
                    || snapshot.maxCost().signum() < 0) throw new IllegalArgumentException();
            Identifiers.require(snapshot.currency());
            return snapshot;
        } catch (RuntimeException invalid) {
            throw new GovernanceException("delegation_document_denied");
        }
    }

    public static Map<String, String> toolBindings(StructuredPolicy.Document policy) {
        var bindings = new LinkedHashMap<String, String>();
        policy.tools()
                .forEach(
                        (name, rule) -> {
                            if (rule.binding() != null) bindings.put(name, rule.binding());
                        });
        return Map.copyOf(bindings);
    }

    public static String policyDigest(StructuredPolicy.Document policy) {
        return io.agt.core.policy.PolicyFingerprints.digest(policy);
    }
}
