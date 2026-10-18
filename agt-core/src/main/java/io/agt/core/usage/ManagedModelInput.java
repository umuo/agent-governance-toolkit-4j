package io.agt.core.usage;

/** 宿主模型准备状态：先完成执行准入，再提交用量；未进入原生调用时释放准备登记。 */
public interface ManagedModelInput extends MeteredModelInput {
    void beforeModelDispatch();

    void closeModelPreparation();
}
