package io.agt.core.flow;

import io.agt.core.*;
import io.agt.core.data.*;
import java.util.*;

/** 完整发布清单，不存正文；空清单表示撤下，历史版本仍保留。 */
public record DocumentVersion(
        String documentId,
        long version,
        DataLabel label,
        Map<String, String> segments,
        LabelProvenance provenance) {
    public static final int MAX_SEGMENTS = 2048;

    public DocumentVersion {
        Identifiers.require(documentId);
        if (version < 1) throw new IllegalArgumentException("Invalid document version");
        Objects.requireNonNull(label);
        Objects.requireNonNull(provenance);
        if (provenance.kind() != LabelProvenance.Kind.CLASSIFICATION)
            throw new IllegalArgumentException("Document publication requires classification");
        segments = Collections.unmodifiableMap(new TreeMap<>(segments));
        if (segments.size() > MAX_SEGMENTS)
            throw new IllegalArgumentException("Too many document segments");
        segments.forEach(
                (id, digest) -> {
                    Identifiers.require(id);
                    new DataFragment(DataFragment.Kind.DOCUMENT_SEGMENT, digest);
                });
        if (JsonValues.write(segments).length() > JsonValues.MAX_CHARACTERS)
            throw new IllegalArgumentException("Document manifest too large");
    }

    public void require(DataFragment fragment) {
        var reference = Objects.requireNonNull(fragment.document());
        if (!documentId.equals(reference.documentId()) || version != reference.version())
            throw new GovernanceException("document_version_stale");
        if (!fragment.digest().equals(segments.get(reference.segmentId())))
            throw new GovernanceException("document_segment_mismatch_denied");
    }

    public String manifestDigest() {
        return JsonValues.digest(
                List.of(
                        "AGT-DOCUMENT-MANIFEST-V1",
                        documentId,
                        version,
                        label.name(),
                        segments,
                        provenance.taskId(),
                        provenance.actionId(),
                        provenance.sources()));
    }
}
