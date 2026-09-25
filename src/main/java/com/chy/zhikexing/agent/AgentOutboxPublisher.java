package com.chy.zhikexing.agent;

public interface AgentOutboxPublisher {
    void publish(AgentCommand command) throws Exception;
}
