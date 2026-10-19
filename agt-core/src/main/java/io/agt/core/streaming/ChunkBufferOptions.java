package io.agt.core.streaming;

import io.agt.core.Identifiers;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Bounded local text checking configuration. Rule IDs explicitly select policy content rules. */
public record ChunkBufferOptions(
        int chunkCharacters,
        String boundaries,
        Duration maxWait,
        int maxBufferedCharacters,
        Set<String> localRules) {
    public ChunkBufferOptions {
        Objects.requireNonNull(boundaries, "boundaries");
        Objects.requireNonNull(maxWait, "maxWait");
        localRules = Set.copyOf(localRules);
        localRules.forEach(Identifiers::require);
        if (chunkCharacters < 1
                || maxBufferedCharacters < 2
                || maxBufferedCharacters > 65536
                || chunkCharacters > maxBufferedCharacters
                || boundaries.length() > 256
                || maxWait.compareTo(Duration.ofMillis(1)) < 0
                || maxWait.compareTo(Duration.ofSeconds(60)) > 0
                || localRules.isEmpty()
                || localRules.size() > 256) {
            throw new IllegalArgumentException("invalid guarded streaming options");
        }
    }

    public static ChunkBufferOptions defaults(Set<String> localRules) {
        return new ChunkBufferOptions(20, "，。！？\n", Duration.ofMillis(50), 4096, localRules);
    }
}
