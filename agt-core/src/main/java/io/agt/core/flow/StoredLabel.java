package io.agt.core.flow;

import java.util.Objects;

public record StoredLabel(DataLabel label, long revision) {
    public StoredLabel {
        Objects.requireNonNull(label);
        if (revision < 1) throw new IllegalArgumentException("Invalid label revision");
    }
}
