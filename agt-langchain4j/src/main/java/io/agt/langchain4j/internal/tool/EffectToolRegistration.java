package io.agt.langchain4j.internal.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agt.core.*;
import io.agt.core.effects.*;
import java.util.Objects;
import java.util.function.Function;

record EffectToolRegistration(
        String name,
        EffectLedger ledger,
        Function<HostSession, EffectIntent> intent,
        EffectBackend backend)
        implements ToolExecutor {
    EffectToolRegistration {
        Identifiers.require(name);
        Objects.requireNonNull(ledger);
        Objects.requireNonNull(intent);
        Objects.requireNonNull(backend);
    }

    public String execute(ToolExecutionRequest request, Object memory) {
        throw new GovernanceException("effect_registration_not_executable");
    }
}
