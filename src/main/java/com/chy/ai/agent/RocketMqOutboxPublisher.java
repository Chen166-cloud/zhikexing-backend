package com.chy.ai.agent;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name = "app.agent.rocketmq-enabled", havingValue = "true")
public class RocketMqOutboxPublisher implements AgentOutboxPublisher {
    private final DefaultMQProducer producer;
    private final AgentJson json;
    private final String topic;

    public RocketMqOutboxPublisher(
            @Qualifier("agentCommandProducer") DefaultMQProducer producer,
            AgentJson json,
            @Value("${rocketmq.agent.topic:iiip-agent-commands}") String topic) {
        this.producer = producer;
        this.json = json;
        this.topic = topic;
    }

    @Override
    public void publish(AgentCommand command) throws Exception {
        Message message = new Message(topic, json.write(command).getBytes(StandardCharsets.UTF_8));
        message.setKeys(command.id());
        message.putUserProperty("schemaVersion", "1");
        String aggregate = command.payload().getOrDefault("runId", command.path()).toString();
        // 路径包含 run id；恢复/取消 payload 未必含 runId，因此统一取首段资源标识。
        if (command.path().startsWith("/runs/")) aggregate = command.path().split("/")[2];
        var result =
                producer.send(
                        message,
                        (queues, msg, key) ->
                                queues.get(Math.floorMod(key.toString().hashCode(), queues.size())),
                        aggregate,
                        5000);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK)
            throw new AgentDeliveryException("ROCKETMQ_SEND_UNCONFIRMED");
    }
}
