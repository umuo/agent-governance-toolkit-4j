package io.agt.delegation;

import io.agt.core.*;
import io.agt.core.execution.*;
import io.agt.core.flow.DataFlowState;
import io.agt.core.flow.DataLabel;
import io.agt.core.flow.LabelStore;
import io.agt.core.usage.UsageBudget;
import java.time.Clock;
import java.util.*;

/** 单个授权服务的在线状态，重启丢失状态后拒绝旧令牌；不是多个接收进程各自创建的独立缓存。 */
public final class InMemoryGrantRegistry implements GrantRegistry {
    private enum State {
        ISSUED,
        BOUND,
        CLOSED,
        REVOKED
    }

    private static final long LEASE_MILLIS = 2000;

    private record Admission(
            String kind,
            String fingerprint,
            String tool,
            boolean guarded,
            DataLabel label,
            String dataIdentity) {
        Admission(String kind, String fingerprint, String tool, boolean guarded, DataLabel label) {
            this(kind, fingerprint, tool, guarded, label, null);
        }
    }

    private static final class Entry {
        final GrantDocument doc;
        State state;
        String secretDigest;
        String session;
        long heartbeatUntil;
        int models, tools, data;
        final Map<String, Admission> commits = new HashMap<>();
        GrantUsage usage;
        DataFlowState flow;
        GrantExternalWork work;
        boolean accountingRetired;

        Entry(GrantDocument doc, State state) {
            this.doc = doc;
            this.state = state;
        }
    }

    private final Map<String, Entry> grants = new HashMap<>();
    private final Clock clock;

    public InMemoryGrantRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /** 存储适配器专用，不调用检查/续租或任何实际动作。 */
    public synchronized RegistryCheckpoint checkpoint(String rootId) {
        var root = grants.get(rootId);
        if (root == null || root.doc.parentId() != null)
            throw new GovernanceException("delegation_root_denied");
        var values = new ArrayList<RegistryCheckpoint.Node>();
        for (var entry : grants.values()) {
            var ancestor = entry;
            for (int depth = 0; ancestor.doc.parentId() != null && depth < 33; depth++)
                ancestor = Objects.requireNonNull(grants.get(ancestor.doc.parentId()));
            if (!ancestor.doc.id().equals(rootId)) continue;
            var calls = new TreeMap<String, RegistryCheckpoint.Admission>();
            entry.commits.forEach(
                    (key, call) ->
                            calls.put(
                                    key,
                                    new RegistryCheckpoint.Admission(
                                            call.kind(),
                                            call.fingerprint(),
                                            call.tool(),
                                            call.guarded(),
                                            call.label(),
                                            call.dataIdentity())));
            values.add(
                    new RegistryCheckpoint.Node(
                            GrantCodec.encode(entry.doc),
                            entry.state.name(),
                            entry.secretDigest,
                            entry.session,
                            entry.heartbeatUntil,
                            entry.models,
                            entry.tools,
                            entry.data,
                            entry.accountingRetired,
                            calls,
                            entry.usage == null ? null : entry.usage.checkpoint(),
                            entry.flow == null ? null : entry.flow.checkpoint(),
                            entry.work == null ? null : entry.work.checkpoint()));
        }
        values.sort(Comparator.comparing(RegistryCheckpoint.Node::document));
        return new RegistryCheckpoint(1, values);
    }

    public static InMemoryGrantRegistry restored(
            RegistryCheckpoint checkpoint, Clock clock, LabelStore catalog) {
        try {
            var registry = new InMemoryGrantRegistry(clock);
            Entry root = null;
            for (var node : checkpoint.nodes()) {
                var doc = GrantCodec.decode(node.document());
                var entry = new Entry(doc, State.valueOf(node.state()));
                if (registry.grants.put(doc.id(), entry) != null
                        || node.models() < 0
                        || node.tools() < 0
                        || node.data() < 0
                        || node.heartbeatUntil() < 0
                        || node.heartbeatUntil() > doc.expiresAt()
                        || (node.secretDigest() != null
                                && !node.secretDigest().matches("sha256:[0-9a-f]{64}")))
                    throw new IllegalArgumentException();
                if (node.session() != null) Identifiers.require(node.session());
                if (entry.state == State.ISSUED
                        && (node.secretDigest() != null
                                || node.session() != null
                                || node.heartbeatUntil() != 0
                                || node.retired())) throw new IllegalArgumentException();
                if (entry.state == State.BOUND
                        && (node.secretDigest() == null
                                || node.heartbeatUntil() < doc.issuedAt()
                                || node.retired())) throw new IllegalArgumentException();
                if (doc.parentId() == null) {
                    if (root != null
                            || node.secretDigest() == null
                            || node.session() == null
                            || doc.depth() != 0) throw new IllegalArgumentException();
                    root = entry;
                    entry.work =
                            node.work() == null
                                    ? new GrantExternalWork(doc.taskId(), doc.id(), null, clock)
                                    : GrantExternalWork.restored(node.work(), clock);
                    if (!entry.work.state.summary().taskId().equals(doc.taskId()))
                        throw new IllegalArgumentException();
                    if ((doc.usageLimits() == null) != (node.usage() == null)
                            || (doc.flowPolicy() == null) != (node.flow() == null))
                        throw new IllegalArgumentException();
                    entry.usage =
                            node.usage() == null
                                    ? null
                                    : GrantUsage.restored(doc.usageLimits(), node.usage(), clock);
                    entry.flow =
                            node.flow() == null
                                    ? null
                                    : DataFlowState.restored(
                                            GrantCodec.decodeFlowPolicy(doc.flowPolicy()),
                                            node.flow(),
                                            catalog);
                    if (entry.flow != null && !node.flow().taskId().equals(doc.taskId()))
                        throw new IllegalArgumentException();
                } else if (node.usage() != null
                        || node.work() != null
                        || node.flow() != null
                        || !node.admissions().isEmpty()
                        || node.models() != 0
                        || node.tools() != 0
                        || node.data() != 0) throw new IllegalArgumentException();
                entry.secretDigest = node.secretDigest();
                entry.session = node.session();
                entry.heartbeatUntil = node.heartbeatUntil();
                entry.models = node.models();
                entry.tools = node.tools();
                entry.data = node.data();
                entry.accountingRetired = node.retired();
                for (var value : node.admissions().entrySet()) {
                    var call = value.getValue();
                    if (value.getKey().length() > 1024
                            || !Set.of("model", "tool", "data").contains(call.kind())
                            || call.guarded() != (node.flow() != null)
                            || (call.guarded()
                                    ? call.label() == null
                                            || !call.fingerprint().matches("sha256:[0-9a-f]{64}")
                                            || !node.flow().snapshot().label().permits(call.label())
                                    : call.label() != null
                                            || call.tool() != null
                                            || !call.fingerprint()
                                                    .matches(call.kind() + ":sha256:[0-9a-f]{64}")))
                        throw new IllegalArgumentException();
                    if (call.tool() != null) Identifiers.require(call.tool());
                    if (call.dataIdentity() != null
                            && (!call.guarded()
                                    || !call.kind().equals("data")
                                    || !call.dataIdentity().matches("sha256:[0-9a-f]{64}")))
                        throw new IllegalArgumentException();
                    entry.commits.put(
                            value.getKey(),
                            new Admission(
                                    call.kind(),
                                    call.fingerprint(),
                                    call.tool(),
                                    call.guarded(),
                                    call.label(),
                                    call.dataIdentity()));
                }
                if (entry.models
                                != entry.commits.values().stream()
                                        .filter(value -> value.kind().equals("model"))
                                        .count()
                        || entry.tools
                                != entry.commits.values().stream()
                                        .filter(value -> value.kind().equals("tool"))
                                        .count()
                        || entry.data
                                != entry.commits.values().stream()
                                        .filter(value -> value.kind().equals("data"))
                                        .count()
                        || entry.models > doc.quota().modelCalls()
                        || entry.tools > doc.quota().toolCalls()
                        || entry.data > doc.quota().dataCalls())
                    throw new IllegalArgumentException();
            }
            if (root == null) throw new IllegalArgumentException();
            root.work.validateOwners(
                    root.doc.id(),
                    id -> {
                        var owner = registry.grants.get(id);
                        return owner == null ? null : owner.session;
                    });
            for (var entry : registry.grants.values()) {
                var current = entry;
                int depth = 0;
                while (current.doc.parentId() != null) {
                    if (++depth > 32) throw new IllegalArgumentException();
                    var parent = registry.grants.get(current.doc.parentId());
                    if (parent == null) throw new IllegalArgumentException();
                    validateChild(current.doc, parent.doc);
                    current = parent;
                }
                if (current != root) throw new IllegalArgumentException();
            }
            return registry;
        } catch (RuntimeException invalid) {
            throw new GovernanceException("delegation_checkpoint_denied");
        }
    }

    public synchronized RegistryInspection inspect(String rootId, TrustedContext reader) {
        var root = grants.get(rootId);
        if (root == null || root.doc.parentId() != null)
            throw new GovernanceException("delegation_unknown_denied");
        if (!reader.permissions().contains("governance:inspect")
                || !reader.tenantId().equals(root.doc.identity().tenantId())
                || !reader.principalId().equals(root.doc.identity().principalId()))
            throw new GovernanceException("delegation_inspection_denied");
        expireLeases();
        var usage = root.usage == null ? null : root.usage.snapshot();
        return new RegistryInspection(
                rootId,
                root.doc.taskId(),
                root.doc.identity().tenantId(),
                root.doc.identity().principalId(),
                root.state == State.BOUND
                        && clock.millis() < root.heartbeatUntil
                        && clock.millis() < root.doc.expiresAt()
                        && (usage == null || usage.fault() == null)
                        && root.work.state.summary().fault() == null,
                root.doc.expiresAt(),
                root.models,
                root.tools,
                root.data,
                usage,
                root.flow == null ? null : root.flow.snapshot(),
                root.work.state.snapshot());
    }

    public synchronized void expireLeases() {
        for (var entry : grants.values()) {
            if (entry.state == State.BOUND
                    && (clock.millis() >= entry.heartbeatUntil
                            || clock.millis() >= entry.doc.expiresAt())) {
                retire(entry);
                entry.state = State.CLOSED;
            }
        }
        for (var entry : grants.values()) if (entry.usage != null) entry.usage.snapshot();
        for (var entry : grants.values()) if (entry.work != null) entry.work.state.summary();
    }

    /** 准入响应提交后还须检查未越过该租约及任何祖先的期限，不在此续租。 */
    public synchronized long validUntil(String id) {
        var entry = grants.get(id);
        long until = Long.MAX_VALUE;
        for (int depth = 0; entry != null && depth < 33; depth++) {
            if (entry.state != State.BOUND) return 0;
            until = Math.min(until, Math.min(entry.doc.expiresAt(), entry.heartbeatUntil));
            if (entry.doc.parentId() == null)
                return (entry.usage != null && entry.usage.snapshot().fault() != null)
                                || entry.work.state.summary().fault() != null
                        ? 0
                        : until;
            entry = grants.get(entry.doc.parentId());
        }
        return 0;
    }

    private Entry active(String id) {
        Entry original = grants.get(id);
        Entry entry = original;
        int depth = 0;
        while (entry != null) {
            if (++depth > 33
                    || entry.state == State.CLOSED
                    || entry.state == State.REVOKED
                    || clock.millis() >= entry.doc.expiresAt()
                    || (entry.state == State.BOUND && clock.millis() >= entry.heartbeatUntil)) {
                retire(entry);
                entry.state = State.CLOSED;
                throw new GovernanceException("delegation_inactive_denied");
            }
            if (entry.doc.parentId() == null) {
                if (entry.usage != null) entry.usage.checkActive();
                if (entry.work != null) entry.work.state.checkActive();
                break;
            }
            entry = grants.get(entry.doc.parentId());
            if (entry == null || entry.state != State.BOUND || entry.session == null)
                throw new GovernanceException("delegation_inactive_denied");
        }
        if (original == null) throw new GovernanceException("delegation_unknown_denied");
        return original;
    }

    private Entry own(GrantLease lease) {
        var entry = active(lease.grantId());
        if (entry.state != State.BOUND
                || !Objects.equals(entry.secretDigest, JsonValues.digest(lease.secret()))) {
            throw new GovernanceException("delegation_lease_denied");
        }
        return entry;
    }

    private void capacity() {
        grants.values().removeIf(entry -> entry.doc.expiresAt() <= clock.millis());
        if (grants.size() >= 10000) throw new GovernanceException("delegation_registry_full");
    }

    private void touch(Entry entry) {
        entry.heartbeatUntil = Math.min(entry.doc.expiresAt(), clock.millis() + LEASE_MILLIS);
        var book = usage(entry);
        if (book != null && entry.doc.parentId() != null)
            book.renew(entry.doc.id(), entry.doc.parentId(), entry.heartbeatUntil);
    }

    private GrantUsage usage(Entry entry) {
        for (int depth = 0; entry != null && depth < 33; depth++) {
            if (entry.doc.parentId() == null) return entry.usage;
            entry = grants.get(entry.doc.parentId());
        }
        return null;
    }

    private void retire(Entry entry) {
        if (entry.accountingRetired) return;
        entry.accountingRetired = true;
        var root = rawRoot(entry);
        if (root.work != null) {
            if (entry.doc.parentId() == null) root.work.endAll();
            else
                for (var candidate : grants.values())
                    if (descendsFrom(candidate, entry.doc.id())) root.work.end(candidate.doc.id());
        }
        var book = usage(entry);
        if (book != null) {
            if (entry.doc.parentId() == null) book.endAll();
            else book.end(entry.doc.id());
        }
    }

    private Entry rawRoot(Entry entry) {
        for (int i = 0; entry.doc.parentId() != null && i < 33; i++) {
            entry = grants.get(entry.doc.parentId());
            if (entry == null) throw new GovernanceException("delegation_checkpoint_denied");
        }
        return entry;
    }

    private boolean descendsFrom(Entry entry, String ancestor) {
        for (int i = 0; entry != null && i < 33; i++) {
            if (entry.doc.id().equals(ancestor)) return true;
            entry = grants.get(entry.doc.parentId());
        }
        return false;
    }

    private Entry root(Entry entry) {
        while (entry.doc.parentId() != null) entry = active(entry.doc.parentId());
        return entry;
    }

    private void reserveSession(Entry entry) {
        var root = root(entry);
        int active = 0;
        for (var candidate : grants.values()) {
            if (candidate.state != State.BOUND || candidate.doc.parentId() == null) continue;
            try {
                if (root(active(candidate.doc.id())).doc.id().equals(root.doc.id())) active++;
            } catch (GovernanceException expired) {
                /* 过期或祖先停止，不再占用活跃名额。 */
            }
        }
        if (active >= root.doc.quota().sessionSlots())
            throw new GovernanceException("task_session_limit");
    }

    public synchronized void registerRoot(
            GrantDocument doc,
            GrantLease lease,
            String session,
            UsageBudget usage,
            DataFlowState flow) {
        registerRoot(doc, lease, session, usage, flow, null);
    }

    public synchronized void registerRoot(
            GrantDocument doc,
            GrantLease lease,
            String session,
            UsageBudget usage,
            DataFlowState flow,
            ExternalExecutionState.Checkpoint external) {
        capacity();
        if (doc.parentId() != null
                || doc.depth() != 0
                || !lease.grantId().equals(doc.id())
                || grants.containsKey(doc.id()))
            throw new GovernanceException("delegation_root_denied");
        var entry = new Entry(doc, State.BOUND);
        if (!Objects.equals(doc.usageLimits(), usage == null ? null : usage.limits()))
            throw new GovernanceException("delegation_usage_denied");
        entry.usage = usage == null ? null : new GrantUsage(usage, clock);
        if (!Objects.equals(
                doc.flowPolicy(), flow == null ? null : GrantCodec.encodeFlowPolicy(flow.policy())))
            throw new GovernanceException("delegation_flow_policy_denied");
        entry.flow = flow;
        if (external != null
                && (external.fault() != null
                        || ExternalExecutionState.restored(external, clock).summary().active()
                                != 0))
            throw new GovernanceException("external_execution_inflight_denied");
        entry.work = new GrantExternalWork(doc.taskId(), doc.id(), external, clock);
        entry.secretDigest = JsonValues.digest(lease.secret());
        entry.session = Identifiers.require(session);
        touch(entry);
        grants.put(doc.id(), entry);
    }

    public synchronized GrantDocument parent(GrantLease lease) {
        var entry = own(lease);
        if (entry.session == null) throw new GovernanceException("delegation_session_denied");
        return entry.doc;
    }

    public synchronized void issue(GrantDocument doc, GrantLease parent) {
        var previous = parent(parent);
        validateChild(doc, previous);
        capacity();
        if (grants.containsKey(doc.id())) throw new GovernanceException("delegation_replay_denied");
        grants.put(doc.id(), new Entry(doc, State.ISSUED));
    }

    private static void validateChild(GrantDocument doc, GrantDocument previous) {
        if (!Objects.equals(doc.parentId(), previous.id())
                || !doc.taskId().equals(previous.taskId())
                || !doc.issuer().equals(previous.issuer())
                || !doc.quota().equals(previous.quota())
                || !doc.policyDigest().equals(previous.policyDigest())
                || !doc.policyOwner().equals(previous.policyOwner())
                || !doc.sessionBudget().equals(previous.sessionBudget())
                || !doc.dataLimits().equals(previous.dataLimits())
                || !Objects.equals(doc.usageLimits(), previous.usageLimits())
                || !Objects.equals(doc.flowPolicy(), previous.flowPolicy())
                || doc.depth() != previous.depth() + 1
                || doc.maxDepth() != previous.maxDepth()
                || doc.expiresAt() > previous.expiresAt()
                || !doc.identity().tenantId().equals(previous.identity().tenantId())
                || !doc.identity().principalId().equals(previous.identity().principalId())
                || !previous.identity().permissions().containsAll(doc.identity().permissions())
                || doc.frames().size() != previous.frames().size() + 1
                || !doc.frames().get(0).owner().equals(doc.identity())
                || !GrantCodec.lineageDigest(doc.frames().subList(1, doc.frames().size()))
                        .equals(GrantCodec.lineageDigest(previous.frames())))
            throw new GovernanceException("delegation_scope_denied");
        doc.frames().get(0).scope().requireNarrowerThan(previous.frames().get(0).scope());
    }

    public synchronized GrantDocument claim(
            String id, String digest, String agent, String audience, GrantLease lease) {
        var entry = active(id);
        if (!lease.grantId().equals(id)
                || !entry.doc.digest().equals(digest)
                || !entry.doc.identity().agentId().equals(agent)
                || !entry.doc.audience().equals(audience))
            throw new GovernanceException("delegation_claim_denied");
        if (entry.state != State.ISSUED) throw new GovernanceException("delegation_replay_denied");
        reserveSession(entry);
        entry.state = State.BOUND;
        entry.secretDigest = JsonValues.digest(lease.secret());
        touch(entry);
        return entry.doc;
    }

    public synchronized void bind(GrantLease lease, String session) {
        var entry = own(lease);
        if (entry.session != null) throw new GovernanceException("delegation_replay_denied");
        entry.session = Identifiers.require(session);
        touch(entry);
    }

    public synchronized void check(GrantLease lease, String session) {
        var entry = own(lease);
        if (session == null || !session.equals(entry.session))
            throw new GovernanceException("delegation_session_denied");
        touch(entry);
    }

    public synchronized void commit(
            GrantLease lease, String session, String actionId, String kind, String digest) {
        check(lease, session);
        var entry = own(lease);
        String grantId = entry.doc.id();
        entry = root(entry);
        if (entry.flow != null) throw new GovernanceException("delegation_flow_commit_required");
        String key = grantId + ":" + session + ":" + Identifiers.require(actionId);
        String fingerprint = kind + ":" + digest;
        Admission previous = entry.commits.get(key);
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint))
                throw new GovernanceException("delegation_action_mismatch_denied");
            return;
        }
        if (!digest.matches("sha256:[0-9a-f]{64}") || entry.commits.size() >= 100000)
            throw new GovernanceException("delegation_registry_full");
        requireQuota(entry, kind);
        consume(entry, kind);
        entry.commits.put(key, new Admission(kind, fingerprint, null, false, null));
    }

    private void requireQuota(Entry entry, String kind) {
        switch (kind) {
            case "model" -> {
                if (entry.models >= entry.doc.quota().modelCalls())
                    throw new GovernanceException("task_model_budget_exceeded");
            }
            case "tool" -> {
                if (entry.tools >= entry.doc.quota().toolCalls())
                    throw new GovernanceException("task_tool_budget_exceeded");
            }
            case "data" -> {
                if (entry.data >= entry.doc.quota().dataCalls())
                    throw new GovernanceException("task_data_budget_exceeded");
            }
            default -> throw new GovernanceException("delegation_action_denied");
        }
    }

    private void consume(Entry entry, String kind) {
        switch (kind) {
            case "model" -> entry.models++;
            case "tool" -> entry.tools++;
            case "data" -> entry.data++;
            default -> throw new GovernanceException("delegation_action_denied");
        }
    }

    private DataFlowState flow(Entry root) {
        if (root.flow == null) throw new GovernanceException("delegation_flow_unavailable");
        return root.flow;
    }

    private void flowScope(
            Entry owner,
            String session,
            String action,
            InterventionPoint point,
            String tool,
            Object projection) {
        var request =
                new PolicyRequest(point, owner.doc.identity(), session, action, tool, projection);
        for (var frame : owner.doc.frames()) {
            if (point == InterventionPoint.PRE_TOOL_CALL) {
                // 完整参数规则仍由受信任接收 SDK 在改写前后检查；服务端不接收工具正文。
                if (!frame.scope().tools().containsKey(tool))
                    throw new GovernanceException("delegation_tool_denied");
            } else frame.validate(request);
        }
    }

    public synchronized void guardedCommit(
            GrantLease lease,
            String session,
            String action,
            InterventionPoint point,
            String tool,
            Object projection,
            String digest) {
        check(lease, session);
        var owner = own(lease);
        var root = root(owner);
        var flow = flow(root);
        FlowProjection.require(flow.policy(), point, tool, projection);
        flowScope(owner, session, action, point, tool, projection);
        String kind =
                switch (point) {
                    case PRE_MODEL_CALL -> "model";
                    case PRE_TOOL_CALL -> "tool";
                    case PRE_DATA_ACCESS -> "data";
                    default -> throw new GovernanceException("delegation_action_denied");
                };
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}"))
            throw new GovernanceException("delegation_action_denied");
        String key = lease.grantId() + ":" + session + ":" + Identifiers.require(action);
        String fingerprint = JsonValues.digest(Arrays.asList(kind, tool, projection, digest));
        var previous = root.commits.get(key);
        if (previous != null) {
            if (!previous.guarded() || !previous.fingerprint().equals(fingerprint))
                throw new GovernanceException("delegation_action_mismatch_denied");
            if (previous.label() != flow.snapshot().label())
                throw new GovernanceException("data_flow_state_changed_denied");
            flow.validate(
                    new PolicyRequest(
                            point, owner.doc.identity(), session, action, tool, projection));
            return;
        }
        if (root.commits.size() >= 100000)
            throw new GovernanceException("delegation_registry_full");
        requireQuota(root, kind);
        switch (point) {
            case PRE_TOOL_CALL -> flow.beforeTool(tool, projection);
            case PRE_MODEL_CALL -> flow.beforeModel();
            case PRE_DATA_ACCESS -> flow.beforeData(action, projection);
            default -> throw new GovernanceException("delegation_action_denied");
        }
        consume(root, kind);
        root.commits.put(
                key,
                new Admission(
                        kind,
                        fingerprint,
                        tool,
                        true,
                        flow.snapshot().label(),
                        point == InterventionPoint.PRE_DATA_ACCESS
                                ? dataIdentity(projection)
                                : null));
    }

    private String dataIdentity(Object target) {
        return JsonValues.digest(
                List.of(
                        io.agt.core.data.DataResource.resource(target),
                        ((Map<?, ?>) target).get("operation")));
    }

    public synchronized void flowValidate(
            GrantLease lease,
            String session,
            InterventionPoint point,
            String tool,
            Object projection) {
        if (point == InterventionPoint.POST_DATA_ACCESS
                && projection instanceof Map<?, ?> fields
                && fields.containsKey("fragments"))
            throw new GovernanceException("delegation_data_action_denied");
        validateFlow(lease, session, "flow-check", point, tool, projection);
    }

    public synchronized void flowValidate(
            GrantLease lease,
            String session,
            String action,
            InterventionPoint point,
            String tool,
            Object projection) {
        var root = root(own(lease));
        var admission =
                root.commits.get(
                        lease.grantId() + ":" + session + ":" + Identifiers.require(action));
        if (point != InterventionPoint.POST_DATA_ACCESS
                || admission == null
                || !admission.guarded()
                || !admission.kind().equals("data")
                || !Objects.equals(admission.dataIdentity(), dataIdentity(projection)))
            throw new GovernanceException("delegation_data_action_denied");
        validateFlow(lease, session, action, point, tool, projection);
    }

    private void validateFlow(
            GrantLease lease,
            String session,
            String action,
            InterventionPoint point,
            String tool,
            Object projection) {
        check(lease, session);
        var owner = own(lease);
        var flow = flow(root(owner));
        FlowProjection.require(flow.policy(), point, tool, projection);
        flowScope(owner, session, action, point, tool, projection);
        flow.validate(
                new PolicyRequest(point, owner.doc.identity(), session, action, tool, projection));
    }

    public synchronized void flowComplete(
            GrantLease lease, String session, String action, String tool) {
        check(lease, session);
        var root = root(own(lease));
        var admission =
                root.commits.get(
                        lease.grantId() + ":" + session + ":" + Identifiers.require(action));
        if (admission == null
                || !admission.guarded()
                || !"tool".equals(admission.kind())
                || !Objects.equals(admission.tool(), tool))
            throw new GovernanceException("delegation_completion_denied");
        flow(root).completedTool(action, tool);
    }

    public synchronized DataFlowState.Snapshot flowSnapshot(GrantLease lease, String session) {
        var owner = accountingOwner(lease, session);
        // 只读统计在关闭后仍可查询；不恢复调用权限。
        var root = owner;
        for (int depth = 0; root.doc.parentId() != null && depth < 33; depth++) {
            root = grants.get(root.doc.parentId());
            if (root == null) throw new GovernanceException("delegation_unknown_denied");
        }
        return flow(root).snapshot();
    }

    public synchronized void complete(GrantLease lease, String session) {
        var entry = grants.get(lease.grantId());
        if (entry != null
                && Objects.equals(entry.secretDigest, JsonValues.digest(lease.secret()))
                && Objects.equals(entry.session, session)) {
            entry.state = State.CLOSED;
            retire(entry);
        }
    }

    public synchronized void heartbeatRoot(GrantLease lease) {
        var entry = own(lease);
        if (entry.doc.parentId() != null) throw new GovernanceException("delegation_root_denied");
        touch(entry);
    }

    public synchronized void revoke(String id) {
        var entry = grants.get(id);
        if (entry != null) {
            entry.state = State.REVOKED;
            retire(entry);
        }
    }

    private Entry accountingOwner(GrantLease lease, String session) {
        var entry = grants.get(lease.grantId());
        if (entry == null
                || entry.secretDigest == null
                || session == null
                || !session.equals(entry.session)
                || clock.millis() >= entry.doc.expiresAt()
                || !entry.secretDigest.equals(JsonValues.digest(lease.secret())))
            throw new GovernanceException("delegation_lease_denied");
        return entry;
    }

    public synchronized void authorizeCaller(GrantLease lease, String authenticatedAgent) {
        var entry = grants.get(lease.grantId());
        if (entry == null
                || entry.secretDigest == null
                || !entry.doc.identity().agentId().equals(authenticatedAgent)
                || !entry.secretDigest.equals(JsonValues.digest(lease.secret())))
            throw new GovernanceException("delegation_actor_denied");
    }

    private GrantUsage accounting(Entry entry) {
        var book = usage(entry);
        if (book == null) throw new GovernanceException("delegation_usage_unavailable");
        return book;
    }

    public synchronized void usageReserve(
            GrantLease lease, String session, String id, String model, int input, int output) {
        check(lease, session);
        accounting(own(lease)).reserve(lease.grantId(), id, model, input, output);
    }

    public synchronized void usageDispatch(GrantLease lease, String session, String id) {
        check(lease, session);
        accounting(own(lease)).dispatch(lease.grantId(), id);
    }

    public synchronized void usageSettle(
            GrantLease lease,
            String session,
            String id,
            String model,
            Integer input,
            Integer output,
            Integer total) {
        check(lease, session);
        accounting(own(lease)).settle(lease.grantId(), id, model, input, output, total);
    }

    public synchronized void usageClose(GrantLease lease, String session, String id) {
        accounting(accountingOwner(lease, session)).close(lease.grantId(), id);
    }

    public synchronized UsageBudget.Snapshot usageSnapshot(GrantLease lease, String session) {
        return accounting(accountingOwner(lease, session)).snapshot();
    }

    private Entry receiptOwner(GrantLease lease, String session) {
        var entry = grants.get(lease.grantId());
        if (entry == null
                || entry.secretDigest == null
                || session == null
                || !Objects.equals(session, entry.session)
                || !entry.secretDigest.equals(JsonValues.digest(lease.secret())))
            throw new GovernanceException("delegation_lease_denied");
        return entry;
    }

    public synchronized void workRegister(
            GrantLease lease, String session, String id, String kind) {
        check(lease, session);
        var root = rawRoot(own(lease));
        root.work.register(lease.grantId(), session, id, kind);
    }

    public synchronized void workStart(GrantLease lease, String session, String id) {
        check(lease, session);
        var work = rawRoot(own(lease)).work;
        work.own(lease.grantId(), id);
        work.state.start(id, session);
    }

    public synchronized void workCancel(GrantLease lease, String session, String id) {
        var work = rawRoot(receiptOwner(lease, session)).work;
        work.own(lease.grantId(), id);
        work.state.cancel(id, session);
    }

    public synchronized void workFinish(
            GrantLease lease, String session, String id, TerminationEvidence evidence) {
        expireLeases();
        var work = rawRoot(receiptOwner(lease, session)).work;
        work.own(lease.grantId(), id);
        work.state.finish(id, session, evidence);
    }

    public synchronized ExternalExecutionState.Summary workStatus(
            GrantLease lease, String session) {
        expireLeases();
        return rawRoot(receiptOwner(lease, session)).work.state.summary();
    }

    public synchronized ExternalExecutionState.Page workPage(
            GrantLease lease, String session, long revision, int offset) {
        expireLeases();
        return rawRoot(receiptOwner(lease, session)).work.state.page(revision, offset);
    }
}
