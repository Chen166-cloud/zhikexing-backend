package com.chy.ai.agent;

public interface AgentOutboxPublisher {
    void publish(AgentCommand command) throws Exception;
}
