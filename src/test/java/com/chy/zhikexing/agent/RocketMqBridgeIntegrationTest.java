package com.chy.zhikexing.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated real Broker plus loopback HTTP fixture; never starts Spring or calls a model. */
class RocketMqBridgeIntegrationTest {
    private static final String NAME_SERVER = "127.0.0.1:19876";
    private static final String DEFAULT_TOPIC = "zhikexing-agent-bridge-test";

    @Test
    @Timeout(120)
    @EnabledIfEnvironmentVariable(named = "TRIAL_TEST_ROCKETMQ", matches = "true")
    void nativeProducerConsumerRetries503AndPreservesCommandIdentity() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String topic = System.getenv().getOrDefault("TRIAL_TEST_AGENT_TOPIC", DEFAULT_TOPIC);
        String runId = UUID.randomUUID().toString();
        String token = "bridge-fixture-token-" + suffix;
        var mapper = JsonMapper.builder().build();
        var json = new AgentJson(mapper);
        var command =
                new AgentCommand(
                        "bridge-" + suffix,
                        "/runs/" + runId + "/resume",
                        "10001",
                        "bridge-workspace-" + suffix,
                        Map.of("approvalId", "approval-" + suffix, "runId", runId));
        AtomicInteger httpAttempts = new AtomicInteger();
        List<ReceivedHttp> requests = new CopyOnWriteArrayList<>();
        List<AgentCommand> deliveries = new CopyOnWriteArrayList<>();
        List<ConsumeOrderlyStatus> results = new CopyOnWriteArrayList<>();
        CountDownLatch acknowledged = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1",
                exchange -> {
                    requests.add(
                            new ReceivedHttp(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().getPath(),
                                    exchange.getRequestHeaders().getFirst("X-Actor-Id"),
                                    exchange.getRequestHeaders().getFirst("X-Workspace-Id"),
                                    exchange.getRequestHeaders().getFirst("X-Internal-Token"),
                                    exchange.getRequestHeaders().getFirst("X-Command-Delivery"),
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8)));
                    int status = httpAttempts.incrementAndGet() == 1 ? 503 : 202;
                    byte[] body = "{\"fixture\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        DefaultMQProducer producer = null;
        DefaultMQPushConsumer consumer = null;
        server.start();
        try {
            var runtime =
                    new AgentRuntimeClient(
                            "http://127.0.0.1:" + server.getAddress().getPort(), token, json);
            var listener = new AgentCommandMessageListener(runtime, mapper);
            var configuration = new RocketMqConfiguration();
            producer =
                    configuration.agentCommandProducer(NAME_SERVER, "zhikexing-bridge-test-p-" + suffix);
            producer.setInstanceName("bridge-test-producer-" + suffix);
            consumer =
                    configuration.agentCommandConsumer(
                            NAME_SERVER, topic, "zhikexing-bridge-test-c-" + suffix, listener);
            consumer.setInstanceName("bridge-test-consumer-" + suffix);
            // A unique group must not forward older or other tests' messages to this HTTP fixture.
            consumer.registerMessageListener(
                    (MessageListenerOrderly)
                            (messages, context) -> {
                                var owned =
                                        messages.stream()
                                                .filter(
                                                        message ->
                                                                command.id()
                                                                        .equals(message.getKeys()))
                                                .toList();
                                if (owned.isEmpty()) return ConsumeOrderlyStatus.SUCCESS;
                                for (var message : owned) {
                                    deliveries.add(
                                            mapper.readValue(
                                                    message.getBody(), AgentCommand.class));
                                }
                                var result = listener.consumeMessage(owned, context);
                                results.add(result);
                                if (result == ConsumeOrderlyStatus.SUCCESS)
                                    acknowledged.countDown();
                                return result;
                            });
            consumer.start();
            producer.start();
            new RocketMqOutboxPublisher(producer, json, topic).publish(command);
            assertTrue(
                    acknowledged.await(90, TimeUnit.SECONDS),
                    "Broker delivery was not ACKed after HTTP recovery");
            assertTrue(
                    deliveries.size() >= 2,
                    "HTTP 503 must cause a retry of the same Broker command");
            assertEquals(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT, results.getFirst());
            assertEquals(ConsumeOrderlyStatus.SUCCESS, results.getLast());
            assertEquals(deliveries.size(), requests.size());
            for (AgentCommand delivered : deliveries) assertEquals(command, delivered);
            for (ReceivedHttp received : requests) {
                assertEquals("POST", received.method());
                assertEquals("/internal/v1" + command.path(), received.path());
                assertEquals(command.actorId(), received.actor());
                assertEquals(command.workspaceId(), received.workspace());
                assertEquals(token, received.token());
                assertEquals("true", received.delivery());
                assertEquals(command.payload(), json.read(received.body()));
            }
        } finally {
            if (consumer != null) consumer.shutdown();
            if (producer != null) producer.shutdown();
            server.stop(0);
        }
    }

    @Test
    void publisherRejectsEveryUnconfirmedSendResult() {
        var producer = new StubProducer();
        var publisher =
                new RocketMqOutboxPublisher(
                        producer, new AgentJson(JsonMapper.builder().build()), DEFAULT_TOPIC);
        var command =
                new AgentCommand(
                        "unit-id", "/runs", "10001", "unit-workspace", Map.of("runId", "unit-run"));
        for (SendStatus status : SendStatus.values()) {
            if (status == SendStatus.SEND_OK) continue;
            producer.result = new SendResult();
            producer.result.setSendStatus(status);
            assertEquals(
                    "ROCKETMQ_SEND_UNCONFIRMED",
                    assertThrows(AgentDeliveryException.class, () -> publisher.publish(command))
                            .getMessage());
        }
        producer.result = null;
        assertThrows(AgentDeliveryException.class, () -> publisher.publish(command));
    }

    @Test
    void createResumeAndCancelSelectTheSameRunAggregate() throws Exception {
        var producer = new StubProducer();
        producer.result = new SendResult();
        producer.result.setSendStatus(SendStatus.SEND_OK);
        var json = new AgentJson(JsonMapper.builder().build());
        var publisher = new RocketMqOutboxPublisher(producer, json, DEFAULT_TOPIC);
        String runId = "stable-unit-run";
        var commands =
                List.of(
                        new AgentCommand(
                                "create",
                                "/runs",
                                "10001",
                                "unit-workspace",
                                Map.of("runId", runId)),
                        new AgentCommand(
                                "resume",
                                "/runs/" + runId + "/resume",
                                "10001",
                                "unit-workspace",
                                Map.of("approvalId", "approval")),
                        new AgentCommand(
                                "cancel",
                                "/runs/" + runId + "/cancel",
                                "10001",
                                "unit-workspace",
                                Map.of()));
        for (var command : commands) {
            publisher.publish(command);
            assertEquals(runId, producer.aggregate);
            assertEquals(command.id(), producer.message.getKeys());
            assertEquals("1", producer.message.getUserProperty("schemaVersion"));
            assertEquals(
                    command,
                    JsonMapper.builder()
                            .build()
                            .readValue(producer.message.getBody(), AgentCommand.class));
        }
    }

    record ReceivedHttp(
            String method,
            String path,
            String actor,
            String workspace,
            String token,
            String delivery,
            String body) {}

    static final class StubProducer extends DefaultMQProducer {
        SendResult result;
        Object aggregate;
        Message message;

        @Override
        public SendResult send(
                Message message, MessageQueueSelector selector, Object aggregate, long timeout) {
            this.message = message;
            this.aggregate = aggregate;
            return result;
        }
    }
}
