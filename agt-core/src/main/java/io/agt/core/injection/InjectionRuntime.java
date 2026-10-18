package io.agt.core.injection;

import io.agt.core.*;
import java.util.*;
import java.util.concurrent.*;

/** 单个运行时共享的检测资源；超时任务不扩容，迟到结果不能恢复交付。 */
public final class InjectionRuntime implements AutoCloseable {
    public record Assessment(String refusal, List<String> warnings) {
        public Assessment {
            warnings = List.copyOf(warnings);
        }
    }

    private final List<InjectionCheck> checks;
    private final Map<String, DetectorBinding> bindings;
    private final ThreadPoolExecutor workers;

    public InjectionRuntime(List<InjectionCheck> checks, Map<String, DetectorBinding> bindings) {
        this.checks = List.copyOf(checks);
        this.bindings = Map.copyOf(bindings);
        if (checks.size() > 16
                || checks.stream().map(InjectionCheck::id).distinct().count() != checks.size())
            throw new IllegalArgumentException("Invalid injection check set");
        for (var check : checks) {
            var binding = bindings.get(check.detector());
            if (binding == null || !binding.revision().equals(check.revision()))
                throw new GovernanceException("injection_detector_binding_denied");
        }
        workers =
                checks.isEmpty()
                        ? null
                        : new ThreadPoolExecutor(
                                2,
                                2,
                                30,
                                TimeUnit.SECONDS,
                                new ArrayBlockingQueue<>(32),
                                task -> {
                                    var t = new Thread(task, "agt-injection");
                                    t.setDaemon(true);
                                    return t;
                                },
                                new ThreadPoolExecutor.AbortPolicy());
    }

    public Assessment check(
            HostSession session,
            PolicyRequest request,
            DetectionInput.Stage stage,
            EnforcementMode mode) {
        var warnings = new ArrayList<String>();
        for (var check : checks) {
            if (!check.points().contains(request.point())) continue;
            var input = DetectionInput.from(request, stage);
            warnings.add("injection_check:" + JsonValues.digest(check.id()));
            warnings.add(
                    "injection_detector:"
                            + JsonValues.digest(List.of(check.detector(), check.revision())));
            warnings.add("injection_origin:" + input.source().origin().name());
            warnings.add("injection_trust:" + input.source().trust().name());
            warnings.add("injection_stage:" + stage.name());
            String failure = null;
            DetectionResult result = null;
            if (JsonValues.write(input.content()).length() > check.maxCharacters())
                failure = "injection_input_limit";
            else {
                var delivery = new CompletableFuture<DetectionResult>();
                var task =
                        new FutureTask<Void>(
                                () -> {
                                    if (delivery.isDone()) return null;
                                    try {
                                        delivery.complete(
                                                Objects.requireNonNull(
                                                        bindings.get(check.detector())
                                                                .detector()
                                                                .detect(input)));
                                    } catch (Throwable unavailable) {
                                        delivery.completeExceptionally(
                                                new GovernanceException(
                                                        "injection_detector_error"));
                                    }
                                    return null;
                                });
                session.watchTermination(delivery);
                delivery.whenComplete(
                        (value, error) -> {
                            if (error != null) task.cancel(true);
                        });
                try {
                    // 权威状态查询可能需要网络；先完成它，再启动检测的排队/执行时钟。
                    long waitMillis =
                            Math.min(check.timeoutMillis(), session.remainingDurationMillis());
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
                    workers.execute(task);
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException();
                    result = delivery.get(remaining, TimeUnit.NANOSECONDS);
                    if (System.nanoTime() > deadline) throw new TimeoutException();
                } catch (RejectedExecutionException saturated) {
                    failure = "injection_detector_saturated";
                } catch (TimeoutException timeout) {
                    failure = "injection_detector_timeout";
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new GovernanceException("operation_cancelled");
                } catch (ExecutionException error) {
                    session.checkActive();
                    failure = "injection_detector_error";
                } finally {
                    if (!delivery.isDone())
                        delivery.completeExceptionally(
                                new GovernanceException("injection_detector_cancelled"));
                    task.cancel(true);
                    workers.remove(task);
                }
            }
            session.checkActive();
            if (failure == null && result.status() == DetectionResult.Status.UNKNOWN)
                failure = "injection_detector_unknown";
            if (failure != null) {
                warnings.add(failure);
                if (check.failure() == InjectionCheck.Failure.DENY)
                    return new Assessment(failure, warnings);
            } else if (result.status() == DetectionResult.Status.SUSPECT) {
                warnings.add("injection_detected");
                if (mode == EnforcementMode.ENFORCE)
                    return new Assessment("injection_detected", warnings);
            } else {
                warnings.add("injection_clear");
            }
        }
        return new Assessment(null, warnings.stream().distinct().toList());
    }

    public void close() {
        if (workers != null) workers.shutdownNow();
    }

    public boolean requiresCompleteOutputCheck() {
        return checks.stream()
                .anyMatch(
                        check ->
                                check.points().contains(InterventionPoint.POST_MODEL_CALL)
                                        || check.points().contains(InterventionPoint.OUTPUT));
    }
}
