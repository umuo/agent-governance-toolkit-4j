package io.agt.core.flow;

import io.agt.core.Identifiers;
import java.util.*;

/** 有界的因果引用，不保存正文；task/action 由宿主运行时赋值。 */
public record LabelProvenance(String taskId, String actionId, List<String> sources, Kind kind) {
    public enum Kind {
        CLASSIFICATION,
        WRITE
    }

    public LabelProvenance(String taskId, String actionId, List<String> sources) {
        this(taskId, actionId, sources, Kind.WRITE);
    }

    public LabelProvenance {
        Identifiers.require(taskId);
        Identifiers.require(actionId);
        Objects.requireNonNull(kind);
        sources = List.copyOf(sources);
        if (sources.size() > 512
                || sources.stream().anyMatch(source -> source.isEmpty() || source.length() > 512))
            throw new IllegalArgumentException("Invalid provenance references");
        sources = sources.stream().distinct().sorted().toList();
    }
}
