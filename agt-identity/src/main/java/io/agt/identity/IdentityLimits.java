package io.agt.identity;

/** 身份检查资源预算；拒绝过载，不新增无限等待/工作线程。 */
public record IdentityLimits(
        int maxSessions,
        int statusWorkers,
        int statusQueue,
        int monitorIntervalMillis,
        int unattachedMillis) {
    public static final IdentityLimits DEFAULT = new IdentityLimits(16, 4, 32, 1000, 2000);

    public IdentityLimits {
        if (maxSessions < 1
                || maxSessions > 128
                || statusWorkers < 1
                || statusWorkers > 16
                || statusQueue < 1
                || statusQueue > 128
                || monitorIntervalMillis < 100
                || monitorIntervalMillis > 5000
                || unattachedMillis < 100
                || unattachedMillis > 10000)
            throw new IllegalArgumentException("Invalid identity resource limits");
    }
}
