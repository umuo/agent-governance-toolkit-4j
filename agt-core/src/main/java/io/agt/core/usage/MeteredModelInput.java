package io.agt.core.usage;

/** 适配器准备出的实际模型输入，必须携带当前会话预占凭据。 */
public interface MeteredModelInput {
    ModelUsageReservation usageReservation();
}
