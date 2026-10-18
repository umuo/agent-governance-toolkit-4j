package io.agt.core.effects;

import static io.agt.core.effects.EffectLedger.*;

import io.agt.core.*;
import io.agt.core.execution.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** 只能从已经提交授权的工具动作进入；记录与业务后端不构成分布式事务。 */
public final class EffectCoordinator {
    private EffectCoordinator() {}

    private enum PhaseState {
        READY,
        ENTERED,
        ABANDONED
    }

    public static Object execute(
            HostSession session,
            String actionId,
            String tool,
            PreparedEffect plan,
            Consumer<Object> before,
            Consumer<Object> rawResult) {
        session.checkEffectAdmission(actionId, tool, plan.rule());
        var rule = plan.rule();
        var intent = plan.intent();
        var ledger = plan.ledger();
        if (!rule.ledgerId().equals(ledger.identity()))
            throw new GovernanceException("effect_ledger_binding_denied");
        var context = session.context();
        var key = new Key(context.tenantId(), context.principalId(), rule.namespace(), intent.id());
        String digest =
                JsonValues.digest(
                        Arrays.asList(
                                "AGT-EFFECT-REQUEST-V1",
                                key.digest(),
                                rule.schema(),
                                rule.phase().name(),
                                tool,
                                plan.parameters(),
                                intent.expectedState(),
                                intent.parent() == null
                                        ? null
                                        : List.of(
                                                intent.parent().receipt(),
                                                intent.parent().resultDigest())));
        before.accept(plan.parameters());
        var owner =
                new Owner(
                        session.taskSnapshot().taskId(),
                        session.sessionId(),
                        actionId,
                        context.agentId());
        var claim =
                ledger.claim(
                        key,
                        rule.phase(),
                        digest,
                        intent.parent(),
                        owner,
                        Math.min(300000, session.remainingDurationMillis()));
        long attempt = claim.lease() != null ? claim.lease().attempt() : claim.replay().attempt();
        String backendKey =
                JsonValues.digest(
                        List.of("AGT-BACKEND-EFFECT-V1", key.digest(), rule.phase().name()));
        var call =
                new EffectBackend.Call(
                        key,
                        rule.phase(),
                        plan.parameters(),
                        intent.expectedState(),
                        backendKey,
                        digest,
                        attempt);
        if (claim.replay() != null) {
            try {
                session.checkEffectAdmission(actionId, tool, plan.rule());
                var result =
                        JsonValues.freeze(plan.backend().recover(call, claim.replay().proof()));
                if (!JsonValues.digest(result).equals(claim.replay().proof().resultDigest()))
                    throw new GovernanceException("effect_result_mismatch");
                session.checkActive();
                session.effectEvidence(
                        actionId, "effect_replayed", key.digest(), digest, rule.phase(), attempt);
                rawResult.accept(result);
                return result;
            } catch (GovernanceException denied) {
                throw denied;
            } catch (Exception failure) {
                throw new GovernanceException("effect_recovery_failed");
            }
        }
        var lease = claim.lease();
        var phase = new AtomicReference<>(PhaseState.READY);
        var terminal = new CompletableFuture<TerminationEvidence>();
        ExternalExecution work = null;
        boolean settled = false;
        try {
            work =
                    session.registerExternalExecution(
                            "BUSINESS_EFFECT",
                            () -> {
                                if (phase.compareAndSet(PhaseState.READY, PhaseState.ABANDONED))
                                    terminal.complete(TerminationEvidence.NOT_STARTED);
                                return terminal;
                            });
            if (!intent.expectedState().equals(plan.backend().currentState(call)))
                throw new GovernanceException("effect_precondition_failed");
            session.checkEffectAdmission(actionId, tool, plan.rule());
            work.started();
            ledger.start(lease);
            session.checkEffectAdmission(actionId, tool, plan.rule());
            if (!phase.compareAndSet(PhaseState.READY, PhaseState.ENTERED))
                throw new GovernanceException("effect_not_dispatched");
            session.effectEvidence(
                    actionId, "effect_dispatched", key.digest(), digest, rule.phase(), attempt);
            var reply = Objects.requireNonNull(plan.backend().execute(call));
            if (reply.status() == Status.UNKNOWN)
                throw new GovernanceException("effect_outcome_unknown");
            var proof =
                    new Proof(
                            reply.receipt(),
                            JsonValues.digest(
                                    reply.status() == Status.COMMITTED ? reply.value() : null));
            finish(ledger, lease, reply.status(), proof);
            settled = true;
            work.finished(TerminationEvidence.CONFIRMED);
            terminal.complete(TerminationEvidence.CONFIRMED);
            session.effectEvidence(
                    actionId,
                    reply.status() == Status.COMMITTED ? "effect_committed" : "effect_not_applied",
                    key.digest(),
                    digest,
                    rule.phase(),
                    attempt);
            if (reply.status() != Status.COMMITTED)
                throw new GovernanceException("effect_not_applied");
            session.checkActive();
            rawResult.accept(reply.value());
            return reply.value();
        } catch (Throwable failure) {
            if (!settled) {
                boolean entered = phase.get() == PhaseState.ENTERED;
                session.noteFailureCause(
                        failure instanceof GovernanceException denied
                                ? denied.code()
                                : entered ? "effect_outcome_unknown" : "effect_preparation_failed");
                try {
                    finish(
                            ledger,
                            lease,
                            entered ? Status.UNKNOWN : Status.NOT_APPLIED,
                            entered
                                    ? null
                                    : new Proof("sdk-not-dispatched", JsonValues.digest(null)));
                } catch (RuntimeException unavailable) {
                    /* 未确认日志不允许下一次实际执行；保持原始失败原因。 */
                }
                if (work != null)
                    work.finished(
                            entered
                                    ? TerminationEvidence.UNKNOWN
                                    : TerminationEvidence.NOT_STARTED);
                terminal.complete(
                        entered ? TerminationEvidence.UNKNOWN : TerminationEvidence.NOT_STARTED);
            }
            if (failure instanceof GovernanceException denied) throw denied;
            throw new GovernanceException(
                    phase.get() == PhaseState.ENTERED
                            ? "effect_outcome_unknown"
                            : "effect_preparation_failed");
        } finally {
            phase.compareAndSet(PhaseState.READY, PhaseState.ABANDONED);
        }
    }

    /** 取消中断不应主动丢弃已知凭据；只清理元数据写入阶段的既有中断位，最后恢复。 */
    private static void finish(EffectLedger ledger, Lease lease, Status status, Proof proof) {
        boolean interrupted = Thread.interrupted();
        try {
            ledger.finish(lease, status, proof);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
