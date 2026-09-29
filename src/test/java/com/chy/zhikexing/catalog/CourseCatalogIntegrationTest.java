package com.chy.zhikexing.catalog;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL 8 + Redis; fixture rows and cache keys are unique to each test. */
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_REDIS_PORT", matches = "[0-9]+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CourseCatalogIntegrationTest {
    private JdbcTemplate jdbc;
    private CatalogCacheTestRedis connection;
    private CourseCatalogService catalog;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final Set<String> cacheKeys = new HashSet<>();
    private final List<Long> courseIds = new ArrayList<>();
    private long firstId;
    private long campusId;
    private String marker;
    private String cacheNamespace;

    @BeforeAll
    void connect() {
        var environment = System.getenv();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(environment.get("CATALOG_TEST_MYSQL_URL"),
                environment.getOrDefault("CATALOG_TEST_MYSQL_USERNAME", "root"),
                environment.getOrDefault("CATALOG_TEST_MYSQL_PASSWORD", "catalog-test")));
        // V1's catalog columns, with the BIGINT identifiers introduced by V6.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS course (
                    id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL,
                    edu INT, type VARCHAR(64), price BIGINT, duration INT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS school (
                    id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(128) NOT NULL, city VARCHAR(64)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        connection = new CatalogCacheTestRedis(Integer.parseInt(environment.get("CATALOG_TEST_REDIS_PORT")),
                Integer.parseInt(environment.getOrDefault("CATALOG_TEST_REDIS_DATABASE", "15")));
    }

    @BeforeEach
    void seedCatalog() {
        firstId = 10_000_000_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(),
                1_000_000_000_000L);
        campusId = firstId;
        marker = "catalog_" + UUID.randomUUID().toString().replace("-", "");
        cacheNamespace = "test:" + UUID.randomUUID() + ":";
        var batch = new ArrayList<Object[]>();
        for (int i = 0; i < 20; i++) {
            courseIds.add(firstId + i);
            batch.add(new Object[]{firstId + i, marker + " Java \t课程 " + i, i % 5, marker,
                    1000L + i * 100, 20 - i});
        }
        jdbc.batchUpdate("INSERT INTO course(id,name,edu,type,price,duration) VALUES(?,?,?,?,?,?)", batch);
        jdbc.update("INSERT INTO school(id,name,city) VALUES(?,?,?)", campusId, marker, null);
        var cache = new CatalogCache(connection.redis, connection.redisson,
                new CatalogCacheProperties(), new SimpleMeterRegistry()) {
            @Override
            public String get(String key, Supplier<String> loader) {
                String scoped = cacheNamespace + key;
                cacheKeys.add("catalog:v1:" + scoped);
                cacheKeys.add("catalog:lock:v1:" + scoped);
                return super.get(scoped, loader);
            }
        };
        catalog = new CourseCatalogService(jdbc, cache, json);
    }

    @AfterEach
    void cleanFixtures() {
        for (long id : courseIds) jdbc.update("DELETE FROM course WHERE id=?", id);
        courseIds.clear();
        jdbc.update("DELETE FROM school WHERE id=?", campusId);
        connection.redis.delete(cacheKeys);
        cacheKeys.clear();
    }

    @AfterAll
    void disconnect() {
        if (connection != null) connection.close();
    }

    @Test
    void paginationPreservesWhitespaceInsensitiveSearchEducationAndPriceOrdering() {
        var filter = new CourseCatalogService.Filter("  " + marker + " Java课程  ",
                marker, 2, "price", false);
        var page = catalog.page(filter, 2, 5);
        assertEquals(12, page.total());
        assertEquals(2, page.page());
        assertEquals(5, page.pageSize());
        assertEquals(List.of(firstId + 10, firstId + 7, firstId + 6, firstId + 5, firstId + 2),
                page.items().stream().map(course -> Long.valueOf(course.id())).toList());
        assertTrue(page.items().stream().allMatch(course -> course.edu() <= 2));
        assertTrue(catalog.page(filter, 4, 5).items().isEmpty());
    }

    @Test
    void detailsKeepLargeIdsAsStringsAndCacheMissingCourses() {
        var course = catalog.course(firstId);
        Map<String, Object> encoded = json.readValue(json.writeValueAsString(course), new TypeReference<>() {});
        assertEquals(Long.toString(firstId), encoded.get("id"));
        assertEquals(1000L, course.price());
        long missingId = firstId + 50;
        var missing = assertThrows(ResponseStatusException.class, () -> catalog.course(missingId));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("null", connection.redis.opsForValue()
                .get("catalog:v1:" + cacheNamespace + "course:" + missingId));

        courseIds.add(missingId);
        jdbc.update("INSERT INTO course(id,name) VALUES(?,?)", missingId, marker + " newly inserted");
        // A cached miss still returns 404 until expiry, instead of querying every absent ID again.
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> catalog.course(missingId)).getStatusCode());
    }

    @Test
    void courseOptionsAndCampusesKeepTheirExistingJsonShape() {
        var options = catalog.courseOptions();
        assertTrue(options.stream().anyMatch(course -> course.id().equals(Long.toString(firstId))));
        List<Map<String, Object>> optionJson = json.readValue(json.writeValueAsString(options), new TypeReference<>() {});
        var option = optionJson.stream().filter(row -> Long.toString(firstId).equals(row.get("id"))).findFirst().orElseThrow();
        assertEquals(Set.of("id", "name"), option.keySet());

        var campuses = catalog.campuses();
        var campus = campuses.stream().filter(item -> item.id().equals(Long.toString(campusId))).findFirst().orElseThrow();
        assertEquals("", campus.city());
        Map<String, Object> campusJson = json.readValue(json.writeValueAsString(campus), new TypeReference<>() {});
        assertEquals(Set.of("id", "name", "city"), campusJson.keySet());
        assertEquals(Long.toString(campusId), campusJson.get("id"));
    }

    @Test
    void agentSearchKeepsItsCourseFieldsAndFilteringContract() {
        var courses = catalog.search(Map.of("type", marker, "edu", 1, "sortBy", "duration", "ascending", true));
        assertEquals(8, courses.size());
        assertEquals(Long.toString(firstId + 16), courses.getFirst().id());
        assertTrue(courses.stream().allMatch(course -> course.edu() <= 1));
        List<Map<String, Object>> encoded = json.readValue(json.writeValueAsString(courses), new TypeReference<>() {});
        assertEquals(Set.of("id", "name", "type", "edu", "price", "duration"), encoded.getFirst().keySet());
        assertInstanceOf(String.class, encoded.getFirst().get("id"));
        assertInstanceOf(Number.class, encoded.getFirst().get("price"));

        // One batch crosses the actual result cap; ordinary fixtures stay at 20 rows.
        var extra = new ArrayList<Object[]>();
        for (int i = 20; i < 51; i++) {
            courseIds.add(firstId + i);
            extra.add(new Object[]{firstId + i, marker + " extra " + i, marker});
        }
        jdbc.batchUpdate("INSERT INTO course(id,name,type) VALUES(?,?,?)", extra);
        assertEquals(50, catalog.search(Map.of("type", marker)).size());
    }
}
