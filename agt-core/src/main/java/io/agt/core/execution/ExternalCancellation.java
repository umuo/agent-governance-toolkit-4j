package io.agt.core.execution;

import java.util.concurrent.CompletionStage;

/** 必须快速返回；只有实际终止或可信清理确认后才能完成返回的 stage。 */
@FunctionalInterface
public interface ExternalCancellation {
    CompletionStage<TerminationEvidence> requestCancellation();
}
