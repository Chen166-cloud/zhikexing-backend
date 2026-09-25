package com.chy.zhikexing.trial;

import com.chy.zhikexing.agent.AgentJson;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.agent.rocketmq-enabled", havingValue = "true")
public class TrialMessaging implements TransactionListener {
    public static final String TOPIC = "zhikexing_trial_claims";
    private final TrialService service;
    private final AgentJson json;
    private final MeterRegistry metrics;
    private final TransactionMQProducer producer = new TransactionMQProducer("zhikexing_trial_tx_v1");
    private final DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("zhikexing_trial_order_v1");

    public TrialMessaging(
            TrialService service,
            AgentJson json,
            MeterRegistry metrics,
            @Value("${rocketmq.name-server:127.0.0.1:9876}") String namesrv) {
        this.service = service;
        this.json = json;
        this.metrics = metrics;
        producer.setNamesrvAddr(namesrv);
        producer.setTransactionListener(this);
        producer.setSendMsgTimeout(5000);
        producer.setRetryTimesWhenSendFailed(0);
        consumer.setNamesrvAddr(namesrv);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setConsumeThreadMin(4);
        consumer.setConsumeThreadMax(8);
        consumer.setMaxReconsumeTimes(16);
    }

    @PostConstruct
    public void start() throws Exception {
        producer.start();
        try {
            consumer.subscribe(TOPIC, "*");
            consumer.registerMessageListener(
                    (MessageListenerConcurrently)
                            (messages, ctx) -> {
                                try {
                                    for (var message : messages) service.consume(id(message));
                                    metrics.counter("trial.consume", "outcome", "ack").increment();
                                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                                } catch (Exception failure) {
                                    metrics.counter("trial.consume", "outcome", "retry")
                                            .increment();
                                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                                }
                            });
            consumer.start();
        } catch (Exception failure) {
            producer.shutdown();
            throw failure;
        }
    }

    private String id(Message message) {
        var body = json.read(new String(message.getBody(), StandardCharsets.UTF_8));
        if (!Integer.valueOf(1).equals(body.get("schemaVersion")))
            throw new IllegalArgumentException("Unsupported trial schema");
        String id = AgentJson.required(body, "requestId", 64);
        // Old UUID receipts may still be in flight while a deployment is rolling forward.
        if (!id.matches("[0-9]{1,19}|[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Invalid requestId");
        return id;
    }

    @Override
    public LocalTransactionState executeLocalTransaction(Message message, Object arg) {
        return decide(message, false);
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt message) {
        return decide(message, true);
    }

    private LocalTransactionState decide(Message message, boolean check) {
        try {
            String decision = check ? service.check(id(message)) : service.reserve(id(message));
            metrics.counter(
                            "trial.transaction",
                            "phase",
                            check ? "check" : "execute",
                            "decision",
                            decision)
                    .increment();
            return switch (decision) {
                case "COMMIT" -> LocalTransactionState.COMMIT_MESSAGE;
                case "ROLLBACK" -> LocalTransactionState.ROLLBACK_MESSAGE;
                default -> LocalTransactionState.UNKNOW;
            };
        } catch (Exception failure) {
            metrics.counter(
                            "trial.transaction",
                            "phase",
                            check ? "check" : "execute",
                            "decision",
                            "UNKNOWN")
                    .increment();
            return LocalTransactionState.UNKNOW;
        }
    }

    @Scheduled(fixedDelayString = "${app.trial.recovery-delay-ms:1000}")
    public void recover() {
        // Durable PENDING also covers crash before half-message send. RESERVED covers lost/DLQ
        // deliveries.
        for (String id : service.due()) {
            try {
                Message message =
                        new Message(
                                TOPIC,
                                "claim",
                                id,
                                json.write(Map.of("schemaVersion", 1, "requestId", id))
                                        .getBytes(StandardCharsets.UTF_8));
                producer.sendMessageInTransaction(message, null);
                metrics.counter("trial.send", "outcome", "returned").increment();
            } catch (Exception failure) {
                metrics.counter("trial.send", "outcome", "retry").increment();
            }
        }
        service.compensate();
    }

    @PreDestroy
    public void stop() {
        consumer.shutdown();
        producer.shutdown();
    }
}
