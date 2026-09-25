package com.chy.ai.trial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chy.ai.agent.AgentJson;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

class TrialProtocolTest {
    @Test
    void unavailableDatabaseAlwaysReturnsUnknownAndReadOnlyCheckDoesNotReserve() {
        TrialService service = mock(TrialService.class);
        String id = UUID.randomUUID().toString();
        MessageExt message = new MessageExt();
        message.setBody(
                ("{\"schemaVersion\":1,\"requestId\":\"" + id + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
        var messaging =
                new TrialMessaging(
                        service,
                        new AgentJson(JsonMapper.builder().build()),
                        new SimpleMeterRegistry(),
                        "unused:9876");
        when(service.check(id)).thenThrow(new IllegalStateException("database unavailable"));
        assertEquals(LocalTransactionState.UNKNOW, messaging.checkLocalTransaction(message));
        verify(service, never()).reserve(anyString());
        when(service.reserve(id)).thenThrow(new IllegalStateException("redis unavailable"));
        assertEquals(
                LocalTransactionState.UNKNOW, messaging.executeLocalTransaction(message, null));
    }
}
