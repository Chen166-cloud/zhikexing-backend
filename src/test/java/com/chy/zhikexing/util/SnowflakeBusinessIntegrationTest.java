package com.chy.zhikexing.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chy.zhikexing.agent.AgentBusinessService;
import com.chy.zhikexing.agent.AgentJson;
import com.chy.zhikexing.config.SnowflakeChatMemoryConfiguration;
import com.chy.zhikexing.trial.TrialInventory;
import com.chy.zhikexing.trial.TrialService;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@EnabledIfEnvironmentVariable(
        named = "SNOWFLAKE_TEST_MYSQL_URL",
        matches = ".*zhikexing_snowflake_flyway_test.*")
class SnowflakeBusinessIntegrationTest {
    @Test
    void chatMemoryWriterPersistsAndReadsSnowflakeIds() {
        var dataSource =
                new DriverManagerDataSource(
                        System.getenv("SNOWFLAKE_TEST_MYSQL_URL"), "root", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        var repository =
                new SnowflakeChatMemoryConfiguration()
                        .snowflakeChatMemoryRepository(
                                dataSource, jdbc, new TransactionTemplate(manager), new SnowflakeIds(890));
        String conversation = "snowflake-memory-" + System.nanoTime();
        repository.saveAll(
                conversation, List.of(new UserMessage("试听课介绍"), new AssistantMessage("课程已找到")));
        var rows = jdbc.queryForList(
                "SELECT id,content FROM spring_ai_chat_memory WHERE conversation_id=? ORDER BY sequence_id",
                conversation);
        assertEquals(2, rows.size());
        assertTrue(((Number) rows.get(0).get("id")).longValue() > (1L << 53));
        assertEquals("试听课介绍", rows.get(0).get("content"));
        assertEquals(2, repository.findByConversationId(conversation).size());
        repository.deleteByConversationId(conversation);
        assertTrue(repository.findByConversationId(conversation).isEmpty());
    }

    @Test
    void campaignClaimAndConfirmedOrderUseSnowflakeReceipts() {
        var dataSource =
                new DriverManagerDataSource(
                        System.getenv("SNOWFLAKE_TEST_MYSQL_URL"), "root", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var ids = new SnowflakeIds(889);
        long actor = ids.nextLong(), course = ids.nextLong(), school = ids.nextLong();
        jdbc.update("INSERT INTO user_info(id,user_name,password) VALUES(?,?,?)", actor, "snowflake-test-" + actor, "unused");
        jdbc.update("INSERT INTO course(id,name) VALUES(?,'Agent试听')", course);
        jdbc.update("INSERT INTO school(id,name,city) VALUES(?,'线上','上海')", school);
        var business = new AgentBusinessService(jdbc, tx, new AgentJson(JsonMapper.builder().build()), ids);
        String workspace = business.createWorkspace(actor, "Snowflake测试空间", false).get("id").toString();
        var inventory = mock(TrialInventory.class);
        when(inventory.allow(actor)).thenReturn(true);
        when(inventory.reserve(anyString(), anyString(), eq(actor))).thenReturn("RESERVED");
        var trials = new TrialService(jdbc, tx, business, inventory, ids);
        String campaign =
                trials.create(
                                actor,
                                workspace,
                                Map.of(
                                        "title", "免费试听",
                                        "courseId", Long.toString(course),
                                        "schoolId", Long.toString(school),
                                        "capacity", 1,
                                        "startsAt", Instant.now().minusSeconds(10).toString(),
                                        "endsAt", Instant.now().plusSeconds(3600).toString()))
                        .get("id")
                        .toString();
        trials.publish(actor, workspace, campaign);
        var accepted = trials.submit(actor, workspace, campaign, "same-browser-retry-key");
        String request = accepted.get("requestId").toString();
        assertTrue(campaign.matches("[0-9]{18,19}"));
        assertTrue(request.matches("[0-9]{18,19}"));
        assertEquals(request, trials.claims(actor, workspace).getFirst().get("requestId"));
        assertEquals("COMMIT", trials.reserve(request));
        trials.consume(request);
        var confirmed = trials.result(actor, workspace, request);
        assertEquals("SUCCEEDED", confirmed.get("status"));
        assertTrue(confirmed.get("orderId").toString().matches("[0-9]{18,19}"));
        assertEquals(confirmed.get("orderId"), trials.claims(actor, workspace).getFirst().get("orderId"));
        assertEquals(request, trials.submit(actor, workspace, campaign, "same-browser-retry-key").get("requestId"));
    }
}
