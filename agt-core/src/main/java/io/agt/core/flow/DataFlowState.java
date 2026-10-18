package io.agt.core.flow;

import io.agt.core.*;
import io.agt.core.data.DataFragment;
import io.agt.core.data.DataOperation;
import io.agt.core.data.DataResource;
import java.util.*;

/** 保守的根任务级传播：等级只升不降，提交检查与来源登记原子完成。 */
public final class DataFlowState implements DataFlowLedger {
    private final DataFlowPolicy policy;
    private DataLabel label;
    private final Set<String> sources = new TreeSet<>();
    private final Set<String> completed = new TreeSet<>();
    private final String taskId;
    private final LabelStore store;
    private final Map<String, StoredLabel> observed = new HashMap<>();
    private final Map<String, FragmentObservation> fragments = new HashMap<>();

    public record FragmentObservation(
            DataResource resource, DataFragment fragment, StoredLabel mark) {
        public FragmentObservation {
            Objects.requireNonNull(resource);
            Objects.requireNonNull(fragment).requireResource(resource);
        }
    }

    public DataFlowState(DataFlowPolicy policy) {
        this(policy, UUID.randomUUID().toString(), null);
    }

    public DataFlowState(DataFlowPolicy policy, String taskId, LabelStore store) {
        this.policy = Objects.requireNonNull(policy);
        this.taskId = Identifiers.require(taskId);
        this.store = store;
        label = policy.inputLabel();
        sources.add("host-input");
    }

    public DataFlowPolicy policy() {
        return policy;
    }

    public record Checkpoint(
            String taskId,
            Snapshot snapshot,
            Map<String, StoredLabel> observed,
            String catalogIdentity,
            Map<String, FragmentObservation> fragments) {
        public Checkpoint(
                String taskId,
                Snapshot snapshot,
                Map<String, StoredLabel> observed,
                String catalogIdentity) {
            this(taskId, snapshot, observed, catalogIdentity, Map.of());
        }

        public Checkpoint {
            Identifiers.require(taskId);
            Objects.requireNonNull(snapshot);
            observed = Map.copyOf(observed);
            fragments = fragments == null ? Map.of() : Map.copyOf(fragments);
            if (fragments.size() > 128) throw new IllegalArgumentException("Too many fragments");
        }

        @Override
        public String toString() {
            return "FlowCheckpoint[redacted]";
        }
    }

    public synchronized Checkpoint checkpoint() {
        String catalog = store == null ? null : store.catalogIdentity();
        if (store != null && catalog == null)
            throw new GovernanceException("data_flow_catalog_identity_required");
        return new Checkpoint(taskId, snapshot(), observed, catalog, fragments);
    }

    public static DataFlowState restored(
            DataFlowPolicy policy, Checkpoint checkpoint, LabelStore store) {
        try {
            if (checkpoint.catalogIdentity() != null
                    && (store == null
                            || !checkpoint.catalogIdentity().equals(store.catalogIdentity())))
                throw new IllegalArgumentException();
            var restored =
                    new DataFlowState(
                            policy,
                            checkpoint.taskId(),
                            checkpoint.catalogIdentity() == null ? null : store);
            var snapshot = checkpoint.snapshot();
            if (!snapshot.label().permits(policy.inputLabel())
                    || !snapshot.sources().contains("host-input")
                    || new HashSet<>(snapshot.sources()).size() != snapshot.sources().size()
                    || checkpoint.observed().size() > 128) throw new IllegalArgumentException();
            var allowed = new HashSet<String>();
            allowed.add("host-input");
            policy.sourceTools()
                    .forEach(
                            (tool, level) -> {
                                String source = "tool:" + tool;
                                allowed.add(source);
                                if (snapshot.sources().contains(source)
                                        && !snapshot.label().permits(level))
                                    throw new IllegalArgumentException();
                            });
            var resourceKeys = new HashSet<String>();
            policy.resources()
                    .forEach(
                            (resource, level) -> {
                                String key = JsonValues.digest(resource.descriptor());
                                resourceKeys.add(key);
                                allowed.add("data:" + key);
                                if (snapshot.sources().contains("data:" + key)
                                        && !snapshot.label().permits(level))
                                    throw new IllegalArgumentException();
                            });
            checkpoint
                    .observed()
                    .forEach(
                            (key, mark) -> {
                                if (checkpoint.catalogIdentity() == null
                                        || !resourceKeys.contains(key)
                                        || !snapshot.label().permits(mark.label())
                                        || !snapshot.sources().contains("data:" + key))
                                    throw new IllegalArgumentException();
                                String source = "stored:" + key + ":" + mark.revision();
                                allowed.add(source);
                                if (!snapshot.sources().contains(source))
                                    throw new IllegalArgumentException();
                            });
            checkpoint
                    .fragments()
                    .forEach(
                            (key, fragment) -> {
                                if (!policy.resources().containsKey(fragment.resource())
                                        || !snapshot.label()
                                                .permits(
                                                        policy.resources().get(fragment.resource()))
                                        || !snapshot.sources()
                                                .contains(
                                                        "data:"
                                                                + JsonValues.digest(
                                                                        fragment.resource()
                                                                                .descriptor()))
                                        || ((fragment.mark() != null)
                                                != (checkpoint.catalogIdentity() != null))
                                        || !key.equals(fragment.fragment().key(fragment.resource()))
                                        || !snapshot.sources().contains("fragment:" + key))
                                    throw new IllegalArgumentException();
                                allowed.add("fragment:" + key);
                                if (fragment.mark() != null) {
                                    if (checkpoint.catalogIdentity() == null
                                            || !snapshot.label().permits(fragment.mark().label()))
                                        throw new IllegalArgumentException();
                                    String reference =
                                            "stored-fragment:"
                                                    + key
                                                    + ":"
                                                    + fragment.mark().revision();
                                    if (!snapshot.sources().contains(reference))
                                        throw new IllegalArgumentException();
                                    allowed.add(reference);
                                }
                            });
            var steps = new HashSet<String>();
            policy.sinkTools()
                    .values()
                    .forEach(rule -> steps.addAll(rule.requiresCompletedTools()));
            if (!allowed.containsAll(snapshot.sources())
                    || !steps.containsAll(snapshot.completedTools()))
                throw new IllegalArgumentException();
            restored.label = snapshot.label();
            restored.sources.clear();
            restored.sources.addAll(snapshot.sources());
            restored.completed.addAll(snapshot.completedTools());
            restored.observed.putAll(checkpoint.observed());
            restored.fragments.putAll(checkpoint.fragments());
            return restored;
        } catch (RuntimeException invalid) {
            throw new GovernanceException("data_flow_checkpoint_denied");
        }
    }

    public synchronized void validate(PolicyRequest request) {
        // 关闭必须能完成取消和退出审计，不能因文档已更新而再次中断清理。
        if (request.point() != InterventionPoint.AGENT_SHUTDOWN) refreshDocuments();
        if (request.point() == InterventionPoint.PRE_TOOL_CALL) {
            checkTool(request.toolName(), request.target());
        } else if (request.point() == InterventionPoint.PRE_DATA_ACCESS) {
            checkData(request.target());
        } else if (request.point() == InterventionPoint.POST_DATA_ACCESS
                && isRead(request.target())) {
            // 返回前再观察目录版本，覆盖检查与底层读取之间的并发敏感写入。
            observeData(request.actionId(), request.target(), false);
            observeFragments(request.actionId(), request.target());
        } else if (request.point() == InterventionPoint.PRE_MODEL_CALL
                && !policy.modelClearance().permits(label)) {
            throw new GovernanceException("data_flow_model_denied");
        } else if (request.point() == InterventionPoint.OUTPUT
                && !policy.outputClearance().permits(label)) {
            throw new GovernanceException("data_flow_output_denied");
        }
    }

    private void checkTool(String name, Object arguments) {
        if (name == null) throw new GovernanceException("data_flow_tool_unclassified_denied");
        if (!policy.sourceTools().containsKey(name)
                && !policy.sinkTools().containsKey(name)
                && !policy.neutralTools().contains(name)) {
            throw new GovernanceException("data_flow_tool_unclassified_denied");
        }
        var prospective = label.join(policy.sourceTools().getOrDefault(name, DataLabel.PUBLIC));
        var sink = policy.sinkTools().get(name);
        if (sink != null) sink.validate(arguments, prospective, completed);
    }

    public synchronized void beforeTool(String name, Object arguments) {
        refreshDocuments();
        checkTool(name, arguments);
        var source = policy.sourceTools().get(name);
        if (source != null) {
            label = label.join(source);
            sources.add("tool:" + name);
        }
    }

    public synchronized void beforeModel() {
        refreshDocuments();
        if (!policy.modelClearance().permits(label))
            throw new GovernanceException("data_flow_model_denied");
    }

    private Map.Entry<DataResource, DataLabel> checkData(Object target) {
        var resource = DataResource.resource(target);
        var entry =
                policy.resources().entrySet().stream()
                        .filter(candidate -> candidate.getKey().descriptor().equals(resource))
                        .findFirst()
                        .orElseThrow(() -> new GovernanceException("data_flow_resource_denied"));
        if (!isRead(target) && !entry.getValue().permits(label)) {
            throw new GovernanceException("data_flow_write_denied");
        }
        var fragments = DataFragment.fromTarget(target, entry.getKey());
        if (((Map<?, ?>) target).get("operation").equals(DataOperation.MEMORY_CLEAR.name())
                && !fragments.isEmpty())
            throw new GovernanceException("data_fragment_identity_denied");
        return entry;
    }

    public synchronized void beforeData(Object target) {
        beforeData(UUID.randomUUID().toString(), target);
    }

    public synchronized void beforeData(String actionId, Object target) {
        refreshDocuments();
        var entry = checkData(target);
        if (isRead(target)) {
            observeData(actionId, target, true);
        } else if (store != null) {
            var mark =
                    store.record(
                            entry.getKey(),
                            label.join(entry.getValue()),
                            new LabelProvenance(taskId, actionId, List.copyOf(sources)));
            if (!mark.label().permits(label.join(entry.getValue())))
                throw new GovernanceException("label_store_integrity_failed");
            for (var fragment : DataFragment.fromTarget(target, entry.getKey())) {
                var fragmentMark =
                        store.recordFragment(
                                entry.getKey(),
                                fragment,
                                label.join(entry.getValue()),
                                new LabelProvenance(taskId, actionId, List.copyOf(sources)));
                if (!fragmentMark.label().permits(label.join(entry.getValue())))
                    throw new GovernanceException("label_store_integrity_failed");
            }
        }
    }

    private void observeFragments(String actionId, Object target) {
        var entry = checkData(target);
        String parentKey = JsonValues.digest(entry.getKey().descriptor());
        var parentMark = observed.get(parentKey);
        var baseline =
                parentMark == null ? entry.getValue() : entry.getValue().join(parentMark.label());
        var documents = new HashMap<String, DocumentVersion>();
        for (var fragment : DataFragment.fromTarget(target, entry.getKey())) {
            var published = document(entry.getKey(), fragment, documents);
            var fragmentBaseline = published == null ? baseline : baseline.join(published.label());
            String key = fragment.key(entry.getKey());
            var previous = fragments.get(key);
            if (previous == null && fragments.size() >= 128)
                throw new GovernanceException("data_fragment_capacity_denied");
            var mark =
                    store == null
                            ? Optional.<StoredLabel>empty()
                            : store.readFragment(entry.getKey(), fragment);
            List<String> classificationSources = List.of("host-policy");
            if (published != null) {
                // 加上版本不能洗掉同一实际内容已有的分类；新旧身份共享内容级保守下界。
                var content = new DataFragment(fragment.kind(), fragment.digest());
                var contentMark = store.readFragment(entry.getKey(), content);
                fragmentBaseline =
                        fragmentBaseline
                                .join(mark.map(StoredLabel::label).orElse(DataLabel.PUBLIC))
                                .join(contentMark.map(StoredLabel::label).orElse(DataLabel.PUBLIC));
                var refs =
                        List.of(
                                "host-policy",
                                "document:" + published.manifestDigest(),
                                "data-action:" + taskId + ":" + actionId);
                if (contentMark.isEmpty() || !contentMark.get().label().permits(fragmentBaseline))
                    contentMark =
                            Optional.of(
                                    store.recordFragment(
                                            entry.getKey(),
                                            content,
                                            fragmentBaseline,
                                            new LabelProvenance(
                                                    taskId,
                                                    JsonValues.digest(
                                                            List.of(
                                                                    "AGT-DOCUMENT-CONTENT-EVENT-V1",
                                                                    actionId,
                                                                    key,
                                                                    fragmentBaseline.name())),
                                                    refs,
                                                    LabelProvenance.Kind.CLASSIFICATION)));
                if (!contentMark.get().label().permits(fragmentBaseline))
                    throw new GovernanceException("label_store_integrity_failed");
                fragmentBaseline = fragmentBaseline.join(contentMark.get().label());
                classificationSources =
                        List.of(
                                "host-policy",
                                "document:" + published.manifestDigest(),
                                "stored-fragment:"
                                        + content.key(entry.getKey())
                                        + ":"
                                        + contentMark.get().revision());
            }
            if (previous != null
                    && previous.mark() != null
                    && (mark.isEmpty()
                            || mark.get().revision() < previous.mark().revision()
                            || !mark.get().label().permits(previous.mark().label())
                            || (mark.get().revision() == previous.mark().revision()
                                    && !mark.get().equals(previous.mark()))))
                throw new GovernanceException("label_store_integrity_failed");
            // 分类事件标识宿主对读到内容的判定，不把读取者误报成写入者。
            if (store != null && (mark.isEmpty() || !mark.get().label().permits(fragmentBaseline)))
                mark =
                        Optional.of(
                                store.recordFragment(
                                        entry.getKey(),
                                        fragment,
                                        fragmentBaseline,
                                        new LabelProvenance(
                                                taskId,
                                                actionId,
                                                classificationSources,
                                                LabelProvenance.Kind.CLASSIFICATION)));
            if (mark.isPresent() && !mark.get().label().permits(fragmentBaseline))
                throw new GovernanceException("label_store_integrity_failed");
            if (sources.size() + (previous == null ? 2 : 0) > 512)
                throw new GovernanceException("data_flow_sources_capacity_denied");
            if (previous != null && previous.mark() != null)
                sources.remove("stored-fragment:" + key + ":" + previous.mark().revision());
            sources.add("fragment:" + key);
            if (mark.isPresent())
                sources.add("stored-fragment:" + key + ":" + mark.get().revision());
            fragments.put(
                    key, new FragmentObservation(entry.getKey(), fragment, mark.orElse(null)));
            label =
                    label.join(fragmentBaseline)
                            .join(mark.map(StoredLabel::label).orElse(DataLabel.PUBLIC));
        }
    }

    private DocumentVersion document(
            DataResource resource, DataFragment fragment, Map<String, DocumentVersion> cache) {
        if (fragment.document() == null) return null;
        if (store == null) throw new GovernanceException("document_catalog_required");
        String key =
                JsonValues.digest(List.of(resource.descriptor(), fragment.document().documentId()));
        var published =
                cache.computeIfAbsent(
                        key,
                        ignored ->
                                store.currentDocument(resource, fragment.document().documentId())
                                        .orElseThrow(
                                                () ->
                                                        new GovernanceException(
                                                                "document_version_unknown")));
        published.require(fragment);
        return published;
    }

    /** 已读上下文在后续授权与实际派发前重验；旧文本不能靠换节点继续使用。 */
    private void refreshDocuments() {
        var documents = new HashMap<String, DocumentVersion>();
        for (var entry : fragments.entrySet()) {
            var observation = entry.getValue();
            var current = document(observation.resource(), observation.fragment(), documents);
            if (current == null) continue;
            var mark =
                    store.readFragment(observation.resource(), observation.fragment())
                            .orElseThrow(
                                    () -> new GovernanceException("label_store_integrity_failed"));
            var old = observation.mark();
            if (old == null
                    || mark.revision() < old.revision()
                    || !mark.label().permits(old.label())
                    || (mark.revision() == old.revision() && !mark.equals(old)))
                throw new GovernanceException("label_store_integrity_failed");
            var content =
                    new DataFragment(
                            observation.fragment().kind(), observation.fragment().digest());
            var contentMark =
                    store.readFragment(observation.resource(), content)
                            .orElseThrow(
                                    () -> new GovernanceException("label_store_integrity_failed"));
            var effective = current.label().join(mark.label()).join(contentMark.label());
            if (!mark.label().permits(effective))
                mark =
                        store.recordFragment(
                                observation.resource(),
                                observation.fragment(),
                                effective,
                                new LabelProvenance(
                                        taskId,
                                        UUID.randomUUID().toString(),
                                        List.of(
                                                "document:" + current.manifestDigest(),
                                                "stored-fragment:"
                                                        + content.key(observation.resource())
                                                        + ":"
                                                        + contentMark.revision()),
                                        LabelProvenance.Kind.CLASSIFICATION));
            if (!mark.label().permits(effective))
                throw new GovernanceException("label_store_integrity_failed");
            sources.remove("stored-fragment:" + entry.getKey() + ":" + old.revision());
            sources.add("stored-fragment:" + entry.getKey() + ":" + mark.revision());
            entry.setValue(
                    new FragmentObservation(observation.resource(), observation.fragment(), mark));
            label = label.join(effective).join(mark.label());
        }
    }

    private void observeData(String actionId, Object target, boolean classify) {
        var entry = checkData(target);
        String key = JsonValues.digest(entry.getKey().descriptor());
        label = label.join(entry.getValue());
        sources.add("data:" + key);
        if (store != null) {
            var mark = store.read(entry.getKey());
            var previous = observed.get(key);
            if (previous != null
                    && (mark.isEmpty()
                            || mark.get().revision() < previous.revision()
                            || !mark.get().label().permits(previous.label())
                            || (mark.get().revision() == previous.revision()
                                    && !mark.get().equals(previous))))
                throw new GovernanceException("label_store_integrity_failed");
            if (classify
                    && entry.getValue() != DataLabel.PUBLIC
                    && (mark.isEmpty() || !mark.get().label().permits(entry.getValue()))) {
                var classification =
                        store.record(
                                entry.getKey(),
                                entry.getValue(),
                                new LabelProvenance(
                                        taskId,
                                        actionId,
                                        List.of("host-policy"),
                                        LabelProvenance.Kind.CLASSIFICATION));
                if (!classification.label().permits(entry.getValue()))
                    throw new GovernanceException("label_store_integrity_failed");
                mark = Optional.of(classification);
            }
            if (mark.isPresent()) {
                label = label.join(mark.get().label());
                String reference = "stored:" + key + ":" + mark.get().revision();
                observed.put(key, mark.get());
                if (previous != null) sources.remove("stored:" + key + ":" + previous.revision());
                sources.add(reference);
            }
        }
    }

    private boolean isRead(Object target) {
        Object operation = ((Map<?, ?>) target).get("operation");
        return DataOperation.RETRIEVE.name().equals(operation)
                || DataOperation.MEMORY_READ.name().equals(operation);
    }

    public synchronized void completedTool(String name) {
        // 仅保留策略实际引用的步骤，避免按未限制的工具/参数组合增长状态。
        if (policy.sinkTools().values().stream()
                .anyMatch(rule -> rule.requiresCompletedTools().contains(name))) {
            completed.add(name);
        }
    }

    public void completedTool(String actionId, String name) {
        completedTool(name);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(label, List.copyOf(sources), Set.copyOf(completed));
    }

    public record Snapshot(DataLabel label, List<String> sources, Set<String> completedTools) {
        public Snapshot {
            Objects.requireNonNull(label);
            sources = List.copyOf(sources);
            completedTools = Set.copyOf(completedTools);
            if (sources.size() > 512
                    || completedTools.size() > 384
                    || sources.stream()
                            .anyMatch(source -> source.isEmpty() || source.length() > 512))
                throw new IllegalArgumentException("Invalid flow snapshot");
            completedTools.forEach(Identifiers::require);
        }
    }
}
