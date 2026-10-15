package io.agt.sandbox;

import io.agt.core.GovernanceException;
import io.github.sandboxdemo.api.SandboxException;

/** 按 SDK 稳定分类映射错误，不读取调试消息、原始异常或敏感路径。 */
final class SandboxErrors {
    private SandboxErrors() {}

    static GovernanceException map(SandboxException error) {
        return new GovernanceException(
                switch (error.code()) {
                    case BACKEND_UNAVAILABLE -> "sandbox_unavailable";
                    case POLICY_UNSUPPORTED -> "sandbox_policy_unsupported";
                    case POLICY_INVALID -> "sandbox_request_invalid";
                    case START_FAILED -> "sandbox_start_failed";
                    case OUTPUT_CAPTURE_FAILED -> "sandbox_output_failed";
                    case OUTPUT_LIMIT -> "sandbox_output_limit";
                    case TIMEOUT -> "sandbox_timeout";
                    case CANCELLED -> "sandbox_cancelled";
                    case CLEANUP_FAILED -> "sandbox_cleanup_unconfirmed";
                    case UNKNOWN -> "sandbox_execution_failed";
                });
    }
}
