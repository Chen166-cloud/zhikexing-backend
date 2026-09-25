package com.chy.zhikexing.agent;

/** 只保存稳定的投递错误码，不把下游响应正文或凭据写入 outbox。 */
public class AgentDeliveryException extends IllegalStateException {
    public AgentDeliveryException(String code) {
        super(code);
    }
}
