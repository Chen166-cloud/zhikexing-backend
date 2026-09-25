package com.chy.zhikexing.util;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** An isolated MySQL database verifies the complete Flyway chain, including legacy data. */
@EnabledIfEnvironmentVariable(
        named = "SNOWFLAKE_TEST_MYSQL_URL",
        matches = ".*zhikexing_snowflake_flyway_test.*")
class SnowflakeSchemaMigrationTest {
    @Test
    void allBusinessTablesHaveApplicationKeysAndLogicalReferencesOnly() {
        var dataSource =
                new DriverManagerDataSource(
                        System.getenv("SNOWFLAKE_TEST_MYSQL_URL"), "root", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        assertEquals(
                16,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE"
                            + " TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' AND"
                            + " TABLE_NAME<>'flyway_schema_history'",
                        Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE"
                            + " TABLE_SCHEMA=DATABASE() AND EXTRA LIKE '%auto_increment%'",
                        Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE"
                            + " TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL",
                        Integer.class));
    }
}
