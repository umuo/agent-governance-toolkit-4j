package io.agt.core.flow;

/** 由宿主数据目录赋值，不从模型或检索文档自报的 metadata 获取。 */
public enum DataLabel {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    SECRET;

    public boolean permits(DataLabel data) {
        return ordinal() >= data.ordinal();
    }

    public DataLabel join(DataLabel other) {
        return permits(other) ? this : other;
    }
}
