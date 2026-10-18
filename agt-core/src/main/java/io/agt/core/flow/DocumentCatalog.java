package io.agt.core.flow;

import io.agt.core.GovernanceException;
import io.agt.core.data.DataResource;
import java.util.*;

/** 宿主入库发布接口，不能作为模型可调用的版本提升或解密工具。 */
public interface DocumentCatalog {
    default DocumentVersion publishDocument(
            DataResource resource,
            String documentId,
            long expectedVersion,
            Map<String, String> segments,
            DataLabel label,
            LabelProvenance provenance) {
        throw new GovernanceException("document_catalog_unsupported");
    }

    default Optional<DocumentVersion> currentDocument(DataResource resource, String documentId) {
        throw new GovernanceException("document_catalog_unsupported");
    }

    default Optional<DocumentVersion> documentHistory(
            DataResource resource, String documentId, long version) {
        throw new GovernanceException("document_catalog_unsupported");
    }
}
