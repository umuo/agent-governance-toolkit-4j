package io.agt.sandbox;

import io.agt.core.GovernanceException;
import io.agt.core.execution.CommandExecution;
import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.OutputCallbackMode;
import io.github.sandboxdemo.api.OutputLimitAction;
import io.github.sandboxdemo.api.SandboxEnvironment;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import java.time.Duration;

/**
 * 将宿主不可变沙箱策略与已审批命令合并为 SDK 请求。
 *
 * <p>保留资源硬限额和隔离机制要求，不能在重建策略时静默丢失；模型不提供环境或路径权限。
 */
final class SandboxRequests {
    private SandboxRequests() {}

    static SandboxRequest create(
            SandboxPolicy policy, CommandExecution execution, SandboxEnvironment environment) {
        try {
            int output = Math.min(policy.maxOutputBytes(), execution.limits().maxOutputBytes());
            int combined =
                    policy.maxCombinedOutputBytes() == 0
                            ? output
                            : Math.min(output, policy.maxCombinedOutputBytes());
            var builder =
                    SandboxRequest.builder(
                                    execution.directory(),
                                    new CommandSpec(
                                            execution.executable().toString(),
                                            execution.arguments()))
                            .readPolicy(policy.readPolicy())
                            .network(policy.networkPolicy())
                            .deletion(policy.deletionPolicy())
                            .resourceLimits(policy.resourceLimits())
                            .requiredIsolation(policy.requiredIsolation())
                            .allowPathSearch(false)
                            .timeout(
                                    Duration.ofMillis(
                                            Math.min(
                                                    policy.timeout().toMillis(),
                                                    execution.limits().timeoutMillis())))
                            .maxOutputBytes(output)
                            .maxCombinedOutputBytes(combined)
                            .onOutputLimit(OutputLimitAction.TERMINATE)
                            .outputCallbackMode(OutputCallbackMode.RETAINED)
                            .executionEnvironment(
                                    environment == null
                                            ? SandboxEnvironment.strict(
                                                    execution.executable().getParent())
                                            : environment);
            if (!policy.writableRoots().contains(policy.workingDirectory())) {
                builder.readOnlyWorkingDirectory();
            }
            policy.readableRoots().stream()
                    .filter(
                            root ->
                                    !root.equals(policy.workingDirectory())
                                            && !policy.writableRoots().contains(root))
                    .distinct()
                    .forEach(builder::readableRoot);
            policy.writableRoots().stream()
                    .filter(root -> !root.equals(policy.workingDirectory()))
                    .distinct()
                    .forEach(builder::writableRoot);
            policy.protectedPaths().forEach(builder::protect);
            // 不注册原文回调、不传 stdin 或自定义变量；严格 PATH 只包含宿主工具目录。
            return builder.build();
        } catch (RuntimeException error) {
            throw new GovernanceException("sandbox_request_invalid");
        }
    }
}
