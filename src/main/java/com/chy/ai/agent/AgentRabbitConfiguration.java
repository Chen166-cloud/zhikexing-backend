package com.chy.ai.agent;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.agent.rabbitmq-enabled", havingValue = "true")
public class AgentRabbitConfiguration {
    public static final String EXCHANGE = "iiip.agent";
    public static final String ROUTING_KEY = "commands";
    public static final String QUEUE = "iiip.agent.commands";
    public static final String DEAD_EXCHANGE = "iiip.agent.dlx";
    public static final String DEAD_ROUTING_KEY = "commands.failed";
    public static final String DEAD_QUEUE = "iiip.agent.failed";

    @Bean
    public Declarables agentCommandTopology() {
        DirectExchange commands = new DirectExchange(EXCHANGE, true, false);
        DirectExchange failures = new DirectExchange(DEAD_EXCHANGE, true, false);
        Queue queue =
                QueueBuilder.durable(QUEUE)
                        .deadLetterExchange(DEAD_EXCHANGE)
                        .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                        .build();
        Queue deadQueue = QueueBuilder.durable(DEAD_QUEUE).build();
        return new Declarables(
                commands,
                failures,
                queue,
                deadQueue,
                BindingBuilder.bind(queue).to(commands).with(ROUTING_KEY),
                BindingBuilder.bind(deadQueue).to(failures).with(DEAD_ROUTING_KEY));
    }
}
