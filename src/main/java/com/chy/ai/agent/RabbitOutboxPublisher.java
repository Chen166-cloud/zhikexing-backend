package com.chy.ai.agent;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "app.agent.rabbitmq-enabled", havingValue = "true")
public class RabbitOutboxPublisher implements AgentOutboxPublisher {
    private final RabbitTemplate rabbit;
    private final AgentJson json;
    private final Duration confirmTimeout;

    public RabbitOutboxPublisher(
            RabbitTemplate rabbit,
            AgentJson json,
            @Value("${app.agent.publisher-confirm-timeout:10s}") Duration confirmTimeout) {
        this.rabbit = rabbit;
        this.json = json;
        this.confirmTimeout = confirmTimeout;
        this.rabbit.setMandatory(true);
    }

    @Override
    public void publish(AgentCommand command) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(command.id());
        Message message =
                new Message(json.write(command).getBytes(StandardCharsets.UTF_8), properties);
        // 消息编号用于消费端幂等，每次发送使用独立确认编号，避免超时确认串入下一次尝试。
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(
                AgentRabbitConfiguration.EXCHANGE,
                AgentRabbitConfiguration.ROUTING_KEY,
                message,
                correlation);
        CorrelationData.Confirm confirm =
                correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.isAck()) throw new AgentDeliveryException("BROKER_NACK");
        if (correlation.getReturned() != null)
            throw new AgentDeliveryException("BROKER_UNROUTABLE");
    }
}
