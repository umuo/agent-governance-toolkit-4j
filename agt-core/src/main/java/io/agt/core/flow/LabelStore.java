package io.agt.core.flow;

import io.agt.core.GovernanceException;
import io.agt.core.data.DataFragment;
import io.agt.core.data.DataResource;
import java.util.Optional;

/** 宿主可信数据目录；实例身份来自实际 handle，不能用文档自报的 metadata 替代。故障必须拒绝。 */
public interface LabelStore extends DocumentCatalog {
    /** 持久目录的稳定标识；非持久自定义实现可返回 null，但不能绑定可恢复授权状态。 */
    default String catalogIdentity() {
        return null;
    }

    Optional<StoredLabel> read(DataResource resource);

    StoredLabel record(DataResource resource, DataLabel label, LabelProvenance provenance);

    Optional<LabelHistory> history(DataResource resource, long revision);

    default Optional<StoredLabel> readFragment(DataResource resource, DataFragment fragment) {
        throw new GovernanceException("label_store_fragments_unsupported");
    }

    default StoredLabel recordFragment(
            DataResource resource,
            DataFragment fragment,
            DataLabel label,
            LabelProvenance provenance) {
        throw new GovernanceException("label_store_fragments_unsupported");
    }

    default Optional<LabelHistory> fragmentHistory(
            DataResource resource, DataFragment fragment, long revision) {
        throw new GovernanceException("label_store_fragments_unsupported");
    }
}
