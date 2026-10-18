package io.agt.identity;

/** 在线状态源必须由可信宿主提供；异常、超时、过载不能回退成离线允许。 */
@FunctionalInterface
public interface TokenStatus {
    boolean active(VerifiedToken token) throws Exception;
}
