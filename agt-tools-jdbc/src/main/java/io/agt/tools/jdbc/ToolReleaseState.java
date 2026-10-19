package io.agt.tools.jdbc;

import io.agt.core.*;
import io.agt.core.tools.ToolDescriptor;
import java.util.*;

/** 加密检查点的纯状态格式与重放不变量；不保存制品、JWT 或查看凭据明文。 */
final class ToolReleaseState {
    private ToolReleaseState() {}

    enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        REVOKED
    }

    record View(String id, String actor, String token, String secretHash, long expires) {}

    record Decision(String view, String actor, String secretHash, boolean approved) {}

    record Event(String kind, String actor, long time, String reference) {}

    record Release(
            ToolDescriptor descriptor,
            String publisher,
            Status status,
            List<View> views,
            List<Event> events,
            Decision decision) {}

    record State(String identity, List<Release> releases, List<String> history, long observed) {}

    static void validate(State state, long seq, String identity, ToolReleasePolicy policy) {
        if (!identity.equals(state.identity())
                || state.releases().size() > policy.maxReleases()
                || state.history().size() != seq
                || seq > (long) policy.maxReleases() * (policy.maxReviews() + 3)
                || state.observed() < 0) {
            throw integrity();
        }
        state.history()
                .forEach(
                        h -> {
                            if (!h.matches("[0-9a-f]{64}")) {
                                throw integrity();
                            }
                        });
        var slots = new HashSet<List<String>>();
        var bindings = new HashSet<String>();
        long events = 0;
        for (var r : state.releases()) {
            if (!identity.equals(r.descriptor().reviewAuthority())) {
                throw integrity();
            }
            if (!slots.add(slot(r.descriptor()))
                    || !bindings.add(r.descriptor().identity())
                    || r.views().size() > policy.maxReviews()
                    || r.events().isEmpty()
                    || r.events().size() > policy.maxReviews() + 3) {
                throw integrity();
            }
            Identifiers.require(r.publisher());
            var views = new HashMap<String, View>();
            for (var v : r.views()) {
                Identifiers.require(v.id());
                Identifiers.require(v.actor());
                if (views.put(v.id(), v) != null
                        || v.actor().equals(r.publisher())
                        || !v.secretHash().matches("sha256:[0-9a-f]{64}")
                        || v.expires() < 1
                        || v.token() == null) {
                    throw integrity();
                }
            }
            Status status = null;
            long previousTime = -1;
            var seenViews = new HashSet<String>();
            int decisions = 0;
            for (var e : r.events()) {
                Identifiers.require(e.actor());
                if (e.time() < previousTime || e.time() > state.observed()) {
                    throw integrity();
                }
                previousTime = e.time();
                switch (e.kind()) {
                    case "SUBMIT" -> {
                        if (status != null
                                || !e.actor().equals(r.publisher())
                                || !e.reference().equals(r.descriptor().identity())) {
                            throw integrity();
                        }
                        status = Status.PENDING;
                    }
                    case "REVIEW" -> {
                        var v = views.get(e.reference());
                        if (status != Status.PENDING
                                || v == null
                                || !seenViews.add(v.id())
                                || !v.actor().equals(e.actor())
                                || v.expires() <= e.time()
                                || v.expires() - e.time() > 300000) {
                            throw integrity();
                        }
                    }
                    case "APPROVE", "REJECT" -> {
                        var d = r.decision();
                        var v = views.get(e.reference());
                        if (status != Status.PENDING
                                || ++decisions > 1
                                || d == null
                                || v == null
                                || !seenViews.contains(v.id())
                                || !v.actor().equals(e.actor())
                                || !d.actor().equals(e.actor())
                                || !d.view().equals(v.id())
                                || !d.secretHash().equals(v.secretHash())
                                || d.approved() != e.kind().equals("APPROVE")
                                || e.time() >= v.expires()) {
                            throw integrity();
                        }
                        status = d.approved() ? Status.APPROVED : Status.REJECTED;
                    }
                    case "REVOKE" -> {
                        if (status == null
                                || status == Status.REVOKED
                                || !e.reference().equals(r.descriptor().identity())) {
                            throw integrity();
                        }
                        status = Status.REVOKED;
                    }
                    default -> throw integrity();
                }
            }
            if (status != r.status()
                    || seenViews.size() != views.size()
                    || (r.decision() != null) != (decisions == 1)) {
                throw integrity();
            }
            events += r.events().size();
        }
        if (events != seq) {
            throw integrity();
        }
    }

    private static List<String> slot(ToolDescriptor descriptor) {
        return List.of(descriptor.provider(), descriptor.name(), descriptor.version());
    }

    private static GovernanceException integrity() {
        return new GovernanceException("tool_release_integrity_denied");
    }
}
