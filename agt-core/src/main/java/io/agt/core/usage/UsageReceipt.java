package io.agt.core.usage;

/** 外部账本的单次私有凭据；dispatch 成功后才能发出模型请求，close 必须幂等且不能抛出。 */
public interface UsageReceipt extends AutoCloseable {
    void dispatch();

    void settle(String servedModel, Integer input, Integer output, Integer total);

    @Override
    void close();
}
