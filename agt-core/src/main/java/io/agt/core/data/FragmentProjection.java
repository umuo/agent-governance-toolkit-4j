package io.agt.core.data;

import io.agt.core.GovernanceException;
import java.util.List;

/** 宿主适配器从实际准备值/原生结果投影摘要；不能以模型自报的来源字段代替。 */
public interface FragmentProjection<E, T> {
    default List<DataFragment> written(E prepared) {
        return List.of();
    }

    default List<DataFragment> read(T result) {
        return List.of();
    }

    default List<DataFragment> written(DataOperation operation, E prepared) {
        var values = written(prepared);
        if (operation != DataOperation.MEMORY_ADD
                && operation != DataOperation.MEMORY_SET
                && !values.isEmpty())
            throw new GovernanceException("data_fragment_direction_denied");
        return values;
    }

    default List<DataFragment> read(DataOperation operation, T result) {
        var values = read(result);
        if (operation != DataOperation.RETRIEVE
                && operation != DataOperation.MEMORY_READ
                && !values.isEmpty())
            throw new GovernanceException("data_fragment_direction_denied");
        return values;
    }
}
