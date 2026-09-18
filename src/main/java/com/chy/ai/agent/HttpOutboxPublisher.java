package com.chy.ai.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.agent.rabbitmq-enabled",
        havingValue = "false",
        matchIfMissing = true)
public class HttpOutboxPublisher implements AgentOutboxPublisher {
    private final AgentRuntimeClient runtime;

    public HttpOutboxPublisher(AgentRuntimeClient runtime) {
        this.runtime = runtime;
    }

    @Override
    public void publish(AgentCommand command) {
        var response = runtime.command(command);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AgentDeliveryException("HTTP_" + response.getStatusCode().value());
        }
    }
}
