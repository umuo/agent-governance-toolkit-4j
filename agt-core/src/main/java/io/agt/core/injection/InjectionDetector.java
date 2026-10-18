package io.agt.core.injection;

/** 可信宿主插件；运行于独立有界工作池，不继承可调用工具的会话绑定。 */
@FunctionalInterface
public interface InjectionDetector {
    DetectionResult detect(DetectionInput input) throws Exception;
}
