package io.agt.core.flow;

import java.util.Objects;

/** 指向前一版本的元数据记录；不声称能防范有权改写数据库的管理员。 */
public record LabelHistory(StoredLabel mark, long previousRevision, LabelProvenance provenance) {
    public LabelHistory {
        Objects.requireNonNull(mark);
        Objects.requireNonNull(provenance);
        if (previousRevision != mark.revision() - 1)
            throw new IllegalArgumentException("Invalid provenance chain");
    }
}
