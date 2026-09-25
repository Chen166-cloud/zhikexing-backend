package com.chy.ai.agent;

import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.agent.rocketmq-enabled", havingValue = "true")
public class AgentCommandMessageListener implements MessageListenerOrderly {
    private static final Logger log = LoggerFactory.getLogger(AgentCommandMessageListener.class);
    private final AgentRuntimeClient runtime;
    private final ObjectMapper mapper;

    public AgentCommandMessageListener(AgentRuntimeClient runtime, ObjectMapper mapper) {
        this.runtime = runtime;
        this.mapper = mapper;
    }

    @Override
    public ConsumeOrderlyStatus consumeMessage(
            List<MessageExt> messages, ConsumeOrderlyContext context) {
        for (MessageExt message : messages) {
            try {
                AgentCommand command =
                        mapper.readValue(
                                new String(message.getBody(), StandardCharsets.UTF_8),
                                AgentCommand.class);
                if (command.id() == null
                        || command.path() == null
                        || command.payload() == null
                        || command.actorId() == null
                        || command.workspaceId() == null)
                    throw new IllegalArgumentException("INVALID_AGENT_COMMAND");
                // 不等待 Agent 执行：接口在 PG 提交 Run/取消状态后返回成功。
                // 创建请求按 Run 主键与 request_hash 去重；此处的持久接收不依赖独立 inbox 表。
                var response = runtime.command(command);
                if (!response.getStatusCode().is2xxSuccessful())
                    throw new AgentDeliveryException("HTTP_" + response.getStatusCode().value());
            } catch (Exception error) {
                log.warn(
                        "Agent command delivery pending: key={}, attempt={}, error={}",
                        message.getKeys(),
                        message.getReconsumeTimes(),
                        error.getClass().getSimpleName());
                context.setSuspendCurrentQueueTimeMillis(10000);
                return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
            }
        }
        return ConsumeOrderlyStatus.SUCCESS;
    }
}
