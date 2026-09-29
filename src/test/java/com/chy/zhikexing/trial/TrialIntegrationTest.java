package com.chy.zhikexing.trial;

import static org.junit.jupiter.api.Assertions.*;

import com.chy.zhikexing.agent.*;
import com.chy.zhikexing.util.SnowflakeIds;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Real isolated MySQL/Redis. Never starts Spring or calls a model. */
@EnabledIfEnvironmentVariable(named = "TRIAL_TEST_MYSQL_URL", matches = ".*zhikexing_trial_test.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrialIntegrationTest {
    JdbcTemplate jdbc;
    StringRedisTemplate redis;
    JedisConnectionFactory factory;
    TrialService service;
    TrialInventory inventory;
    AgentBusinessService business;
    TrialAgentService agent;
    AgentJson json;
    TransactionTemplate tx;
    SnowflakeIds ids;
    String workspace;
    final long actor = 220331822288990209L;
    final long courseId = 220331822288990210L;
    final long schoolId = 220331822288990212L;

    @BeforeAll
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        System.getenv("TRIAL_TEST_MYSQL_URL"), "root", "trial-test-only");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"
                            + " AND TABLE_NAME IN ('zhikexing_chat_record','zhikexing_pdf_file')",
                        Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"
                            + " AND TABLE_NAME IN ('iiip_chat_record','iiip_pdf_file')",
                        Integer.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        json = new AgentJson(JsonMapper.builder().build());
        factory = new JedisConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 26379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        ids = new SnowflakeIds(888);
        business = new AgentBusinessService(jdbc, tx, json, ids);
        inventory = new TrialInventory(redis);
        service = new TrialService(jdbc, tx, business, inventory, ids,
                org.mockito.Mockito.mock(com.chy.zhikexing.catalog.CourseCatalogService.class));
        agent = new TrialAgentService(jdbc, tx, business, service, json, ids, true);
        jdbc.update(
                "INSERT IGNORE INTO user_info(id,user_name,password) VALUES(?,'trial-test-owner','unused')",
                actor);
        jdbc.update("INSERT IGNORE INTO course(id,name) VALUES(?,'Agent研发试听')", courseId);
        jdbc.update("INSERT IGNORE INTO school(id,name,city) VALUES(?,'线上','线上')", schoolId);
        workspace =
                business.createWorkspace(actor, "isolated-trial-test", false).get("id").toString();
        for (long i = actor + 1; i <= actor + 50; i++)
            jdbc.update(
                    "INSERT INTO agent_workspace_member(id,workspace_id,user_id,role)"
                        + " VALUES(?,?,?,'MEMBER')",
                    ids.nextLong(),
                    workspace,
                    i);
    }

    @AfterAll
    void stop() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void resetTestActorRate() {
        for (long i = actor; i <= actor + 50; i++) redis.delete("zhikexing:trial:rate:" + i);
    }

    String campaign(int capacity) {
        String id =
                service.create(
                                actor,
                                workspace,
                                Map.of(
                                        "title",
                                        "限量免费试听",
                                        "courseId",
                                        Long.toString(courseId),
                                        "schoolId",
                                        Long.toString(schoolId),
                                        "capacity",
                                        capacity,
                                        "startsAt",
                                        Instant.now().minusSeconds(10).toString(),
                                        "endsAt",
                                        Instant.now().plusSeconds(600).toString()))
                        .get("id")
                        .toString();
        service.publish(actor, workspace, id);
        return id;
    }

    String enqueue(String campaign, long user) {
        return service.enqueue(
                        user,
                        workspace,
                        campaign,
                        "DIRECT",
                        UUID.randomUUID().toString(),
                        null,
                        null,
                        null)
                .get("requestId")
                .toString();
    }

    int remaining(String campaign) {
        return jdbc.queryForObject(
                "SELECT remaining FROM trial_campaign WHERE id=?", Integer.class, campaign);
    }

    long redisRemaining(String campaign) {
        return Long.parseLong(
                redis.opsForHash()
                        .get(TrialInventory.keys(campaign).getFirst(), "remaining")
                        .toString());
    }

    String status(String id, long user) {
        return service.result(user, workspace, id).get("status").toString();
    }

    @Test
    void fortyConcurrentParticipantsEightSeatsDuplicateDelivery() throws Exception {
        String event = campaign(8);
        List<String> requests = new ArrayList<>();
        for (long user = actor + 1; user <= actor + 40; user++) requests.add(enqueue(event, user));
        List<String> accepted = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(16)) {
            var tasks =
                    requests.stream()
                            .<Callable<String>>map(
                                    id -> () -> "COMMIT".equals(service.reserve(id)) ? id : null)
                            .toList();
            for (var future : pool.invokeAll(tasks)) {
                String id = future.get();
                if (id != null) accepted.add(id);
            }
            List<Callable<Void>> deliveries = new ArrayList<>();
            for (String id : accepted)
                for (int copy = 0; copy < 3; copy++)
                    deliveries.add(
                            () -> {
                                service.consume(id);
                                return null;
                            });
            for (var future : pool.invokeAll(deliveries)) future.get();
        }
        assertEquals(8, accepted.size());
        assertEquals(0, remaining(event));
        assertEquals(0, redisRemaining(event));
        assertEquals(
                8,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM trial_order WHERE campaign_id=?",
                        Integer.class,
                        event));
        assertEquals(
                32,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM trial_claim_request WHERE campaign_id=? AND"
                            + " status='REJECTED'",
                        Integer.class,
                        event));
    }

    @Test
    void sameRequestReturnsReceiptDifferentPayloadConflictsAndForeignActorCannotRead() {
        String event = campaign(2), other = campaign(2), key = UUID.randomUUID().toString();
        var first = service.enqueue(actor, workspace, event, "DIRECT", key, null, null, null);
        assertEquals(
                first, service.enqueue(actor, workspace, event, "DIRECT", key, null, null, null));
        assertEquals(
                409,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        service.enqueue(
                                                actor, workspace, other, "DIRECT", key, null, null,
                                                null))
                        .getStatusCode()
                        .value());
        assertEquals(
                404,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        service.result(
                                                actor + 1,
                                                workspace,
                                                first.get("requestId").toString()))
                        .getStatusCode()
                        .value());
        assertThrows(ResponseStatusException.class, () -> enqueue(event, actor));
    }

    @Test
    void crashAfterLuaBeforeJournalCommitReplaysWithoutDoubleReservation() {
        String event = campaign(2), id = enqueue(event, actor);
        assertEquals("RESERVED", inventory.reserve(event, id, actor));
        assertEquals("UNKNOWN", service.check(id));
        assertEquals("COMMIT", service.reserve(id));
        assertEquals(1, redisRemaining(event));
        assertEquals("COMMIT", service.reserve(id));
        assertEquals(1, redisRemaining(event));
        service.consume(id);
        assertEquals("SUCCEEDED", status(id, actor));
    }

    @Test
    void committedJournalSurvivesRedisLossAndLiveCampaignIsNeverRewarmed() {
        String event = campaign(1), id = enqueue(event, actor);
        service.reserve(id);
        redis.delete(TrialInventory.keys(event));
        service.publish(actor, workspace, event);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(TrialInventory.keys(event).getFirst())));
        assertEquals("COMMIT", service.check(id));
        service.consume(id);
        assertEquals("SUCCEEDED", status(id, actor));
    }

    @Test
    void missingInventoryLeavesPendingUnknownRatherThanRollback() {
        String event = campaign(1), id = enqueue(event, actor);
        redis.delete(TrialInventory.keys(event));
        assertThrows(IllegalStateException.class, () -> service.reserve(id));
        assertEquals("UNKNOWN", service.check(id));
        assertEquals("PENDING", status(id, actor));
        assertThrows(IllegalStateException.class, () -> service.consume(id));
        assertEquals(1, remaining(event));
    }

    @Test
    void databaseGuardRejectsDriftAndCompensationIsIdempotent() {
        String event = campaign(2), id = enqueue(event, actor);
        service.reserve(id);
        jdbc.update("UPDATE trial_campaign SET remaining=0 WHERE id=?", event);
        service.consume(id);
        assertEquals("REJECTED", status(id, actor));
        assertEquals("DB_SOLD_OUT", service.result(actor, workspace, id).get("reason"));
        service.compensate();
        service.compensate();
        assertEquals(2, redisRemaining(event));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM trial_order WHERE request_id=?", Integer.class, id));
        assertEquals("ROLLBACK", service.check(id));
    }

    @Test
    void redisTimeEnforcesWindowAndKeysUseOneClusterSlot() {
        String id = UUID.randomUUID().toString();
        inventory.publish(id, 1, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120));
        assertEquals("NOT_STARTED", inventory.reserve(id, "future", actor));
        String ended = UUID.randomUUID().toString();
        inventory.publish(
                ended, 1, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
        assertEquals("ENDED", inventory.reserve(ended, "past", actor));
        assertTrue(TrialInventory.keys(id).stream().allMatch(key -> key.contains("{" + id + "}")));
    }

    String run() {
        return business.submit(
                        actor,
                        workspace,
                        Map.of(
                                "clientRequestId",
                                UUID.randomUUID().toString(),
                                "conversationId",
                                "test-conversation",
                                "input",
                                "参加试听"))
                .get("runId")
                .toString();
    }

    @Test
    void approvalIsTypedBoundToRunActionAndUserAndCannotExecuteTwice() {
        String event = campaign(2), run = run(), action = UUID.randomUUID().toString();
        var draft =
                agent.draft(actor, workspace, run, Map.of("campaignId", event, "actionId", action));
        assertEquals("claim_trial", draft.get("toolName"));
        var command = Map.<String, Object>of("actionId", action, "approvalId", draft.get("id"));
        assertThrows(
                ResponseStatusException.class, () -> agent.execute(actor, workspace, run, command));
        business.decision(
                actor,
                workspace,
                draft.get("id").toString(),
                Map.of("decision", "APPROVED", "expectedVersion", 1));
        assertThrows(
                ResponseStatusException.class,
                () -> business.execute(actor, workspace, run, command));
        var accepted = agent.execute(actor, workspace, run, command);
        assertEquals("PENDING", accepted.get("status"));
        assertEquals(action, accepted.get("actionId"));
        assertEquals(accepted, agent.execute(actor, workspace, run, command));
        String id = accepted.get("requestId").toString();
        service.reserve(id);
        service.consume(id);
        assertEquals("SUCCEEDED", agent.execute(actor, workspace, run, command).get("status"));
        assertEquals("SUCCEEDED", service.byAction(actor, workspace, run(), action).get("status"));
    }

    @Test
    void cancellationBlocksNewClaimButDoesNotUndoAlreadyAcceptedBusiness() {
        String event = campaign(2), run = run(), action = UUID.randomUUID().toString();
        var draft =
                agent.draft(actor, workspace, run, Map.of("campaignId", event, "actionId", action));
        business.decision(
                actor,
                workspace,
                draft.get("id").toString(),
                Map.of("decision", "APPROVED", "expectedVersion", 1));
        business.cancel(actor, workspace, run);
        assertThrows(
                ResponseStatusException.class,
                () ->
                        agent.execute(
                                actor,
                                workspace,
                                run,
                                Map.of("actionId", action, "approvalId", draft.get("id"))));
    }

    @Test
    void pauseRejectsUnreservedRequestsButFinishesPreviouslyReservedOnes() {
        String event = campaign(2),
                reserved = enqueue(event, actor),
                pending = enqueue(event, actor + 1);
        service.reserve(reserved);
        service.pause(actor, workspace, event);
        assertEquals("ROLLBACK", service.reserve(pending));
        service.consume(reserved);
        assertEquals("SUCCEEDED", status(reserved, actor));
        assertThrows(ResponseStatusException.class, () -> enqueue(event, actor + 2));
        assertEquals(
                true,
                service.reconciliation(actor, workspace, event).get("databaseInvariantHolds"));
        assertThrows(
                ResponseStatusException.class,
                () -> service.reconciliation(actor + 1, workspace, event));
    }

    @Test
    void missingCompensationReceiptIsNotFalselyMarkedCompleted() {
        String event = campaign(1), id = enqueue(event, actor);
        service.reserve(id);
        jdbc.update("UPDATE trial_campaign SET remaining=0 WHERE id=?", event);
        service.consume(id);
        redis.delete(TrialInventory.keys(event));
        service.compensate();
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM trial_claim_request WHERE id=? AND"
                            + " release_pending=TRUE",
                        Integer.class,
                        id));
    }

    @Test
    void partialReceiptLossDoesNotRejectAnAmbiguousReservation() {
        String event = campaign(2), id = enqueue(event, actor);
        inventory.reserve(event, id, actor);
        redis.delete(TrialInventory.keys(event).get(2));
        assertThrows(IllegalStateException.class, () -> service.reserve(id));
        assertEquals("UNKNOWN", service.check(id));
        assertEquals(1, redisRemaining(event));
    }

    @Test
    void sharedUserLimitAppliesToAgentAndDirectButNotReceiptReplay() {
        String event = campaign(2), key = UUID.randomUUID().toString();
        var receipt = service.enqueue(actor, workspace, event, "DIRECT", key, null, null, null);
        redis.opsForValue().set("zhikexing:trial:rate:" + actor, "5", java.time.Duration.ofSeconds(10));
        assertEquals(
                receipt, service.enqueue(actor, workspace, event, "DIRECT", key, null, null, null));
        String next = campaign(2);
        assertEquals(
                429,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        service.enqueue(
                                                actor,
                                                workspace,
                                                next,
                                                "AGENT",
                                                "new-agent",
                                                null,
                                                null,
                                                null))
                        .getStatusCode()
                        .value());
        assertEquals(
                429,
                assertThrows(
                                ResponseStatusException.class,
                                () -> service.submit(actor, workspace, next, "new-direct"))
                        .getStatusCode()
                        .value());
    }

    @Test
    void changedApprovedArgumentsFailIntegrityCheck() {
        String event = campaign(2), run = run(), action = UUID.randomUUID().toString();
        var draft =
                agent.draft(actor, workspace, run, Map.of("campaignId", event, "actionId", action));
        String id = draft.get("id").toString();
        business.decision(
                actor, workspace, id, Map.of("decision", "APPROVED", "expectedVersion", 1));
        jdbc.update(
                "UPDATE agent_approval SET args=? WHERE id=?",
                json.write(Map.of("campaignId", event, "amountCent", 99)),
                id);
        assertEquals(
                409,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        agent.execute(
                                                actor,
                                                workspace,
                                                run,
                                                Map.of("actionId", action, "approvalId", id)))
                        .getStatusCode()
                        .value());
    }

    @Test
    void rocketMqHalfCommitConsumerAndRecoveryUseRealBroker() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("TRIAL_TEST_ROCKETMQ")));
        String event = campaign(2), id = enqueue(event, actor);
        var messaging =
                new TrialMessaging(service, json, new SimpleMeterRegistry(), "127.0.0.1:19876");
        messaging.start();
        try {
            messaging.recover();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!"SUCCEEDED".equals(status(id, actor)) && System.nanoTime() < deadline)
                Thread.sleep(100);
            assertEquals("SUCCEEDED", status(id, actor));
            assertEquals(1, remaining(event));
        } finally {
            messaging.stop();
        }
    }
}
