package com.chy.zhikexing.agent;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 原生 remoting client，避免引入面向旧 Spring Boot 的自动配置。 */
@Configuration
@ConditionalOnProperty(name = "app.agent.rocketmq-enabled", havingValue = "true")
public class RocketMqConfiguration {
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer agentCommandProducer(
            @Value("${rocketmq.name-server}") String nameServer,
            @Value("${rocketmq.agent.producer-group:zhikexing-agent-command-producer}") String group) {
        DefaultMQProducer producer = new DefaultMQProducer(group);
        producer.setNamesrvAddr(nameServer);
        producer.setSendMsgTimeout(5000);
        // 持久化 outbox 负责有限间隔重试；重试始终保留同一个 command id。
        producer.setRetryTimesWhenSendFailed(0);
        return producer;
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer agentCommandConsumer(
            @Value("${rocketmq.name-server}") String nameServer,
            @Value("${rocketmq.agent.topic:zhikexing-agent-commands}") String topic,
            @Value("${rocketmq.agent.consumer-group:zhikexing-agent-command-consumer}") String group,
            AgentCommandMessageListener listener)
            throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(group);
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(8);
        consumer.setMaxReconsumeTimes(16);
        consumer.subscribe(topic, "*");
        // 同一个 run 的创建/恢复/取消固定队列并顺序转交；Python 提交 Run 状态后 ACK。
        // Runtime 用 Run 主键与 request_hash 幂等接收，没有另建独立 inbox 表。
        consumer.registerMessageListener((MessageListenerOrderly) listener);
        return consumer;
    }
}
