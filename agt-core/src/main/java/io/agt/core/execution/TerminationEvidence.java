package io.agt.core.execution;

/** 由可信后端给出的终止证据；Future 的取消/完成本身不构成这些证据。 */
public enum TerminationEvidence {
    CONFIRMED,
    NOT_STARTED,
    WORKER_ONLY,
    UNKNOWN
}
