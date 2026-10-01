package com.chy.zhikexing.catalog;

import java.util.*;
import java.util.function.Supplier;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class CourseCatalogService {
    /** price 为人民币元，duration 为天；id 使用字符串避免浏览器精度丢失。 */
    public record Course(String id, String name, String type, Integer edu, Long price, Integer duration) {}
    public record Campus(String id, String name, String city) {}
    public record CourseOption(String id, String name) {}
    public record CoursePage(List<Course> items, long total, int page, int pageSize) {}

    public record Filter(String keyword, String type, Integer edu, String sortBy, boolean ascending) {
        public Filter {
            keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
            type = type == null || type.isBlank() ? null : type.trim();
            sortBy = sortBy == null ? "id" : sortBy;
            if (!Set.of("id", "price", "duration").contains(sortBy))
                throw badRequest("排序字段只允许 id、price、duration");
            if (edu != null && (edu < 0 || edu > 4)) throw badRequest("学历范围为 0 至 4");
            if ((keyword != null && keyword.length() > 100) || (type != null && type.length() > 50))
                throw badRequest("查询条件过长");
        }

        boolean isDefault() {
            return keyword == null && type == null && edu == null && sortBy.equals("id") && ascending;
        }
    }

    private static final String COLUMNS = "id,name,type,edu,price,duration";
    private static final RowMapper<Course> COURSE_ROW = (r, n) -> new Course(
            r.getString("id"), r.getString("name"), r.getString("type"),
            r.getObject("edu", Integer.class), r.getObject("price", Long.class),
            r.getObject("duration", Integer.class));
    private final JdbcTemplate jdbc;
    private final CatalogCache cache;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    public CourseCatalogService(JdbcTemplate jdbc, CatalogCache cache, ObjectMapper json, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.json = json;
        this.metrics = metrics;
    }

    public CoursePage page(Filter filter, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50) throw badRequest("页码必须大于 0，每页为 1 至 50 条");
        Supplier<CoursePage> query = () -> {
            var where = where(filter);
            long total = sql("page_count", () -> jdbc.queryForObject("SELECT COUNT(*) FROM course" + where.sql(),
                    Long.class, where.params().toArray()));
            return new CoursePage(queryCourses(filter, pageSize, ((long) page - 1) * pageSize),
                    total, page, pageSize);
        };
        // 首版只缓存默认首页；任意关键词、筛选和分页保留数据库查询语义。
        return filter.isDefault() && page == 1 && pageSize == 12
                ? cached("courses:home", new TypeReference<>() {}, query) : query.get();
    }

    public Course course(long id) {
        if (id <= 0) throw badRequest("课程编号必须大于 0");
        Course course = cached("course:" + id, new TypeReference<Course>() {}, () ->
                sql("course", () -> jdbc.query("SELECT " + COLUMNS + " FROM course WHERE id=?", COURSE_ROW, id)
                        .stream().findFirst().orElse(null)));
        if (course == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "课程不存在");
        return course;
    }

    /** 保持 Agent 的最多 50 条结果和原有条件查询方式。 */
    public List<Course> search(Map<String, Object> body) {
        var filter = new Filter(text(body, "keyword"), text(body, "type"),
                body.get("edu") == null ? null : Integer.valueOf(body.get("edu").toString()),
                text(body, "sortBy"), !Boolean.FALSE.equals(body.get("ascending")));
        Supplier<List<Course>> query = () -> queryCourses(filter, 50, 0);
        return filter.isDefault() ? cached("courses:agent", new TypeReference<>() {}, query) : query.get();
    }

    public List<CourseOption> courseOptions() {
        return cached("courses:options", new TypeReference<>() {}, () -> sql("options", () -> jdbc.query(
                "SELECT id,name FROM course ORDER BY id LIMIT 500",
                (r, n) -> new CourseOption(r.getString("id"), r.getString("name")))));
    }

    public List<Campus> campuses() {
        return cached("campuses", new TypeReference<>() {}, () -> sql("campuses", () -> jdbc.query(
                "SELECT id,name,city FROM school ORDER BY id LIMIT 200",
                (r, n) -> new Campus(r.getString("id"), r.getString("name"),
                        Objects.toString(r.getString("city"), "")))));
    }

    private List<Course> queryCourses(Filter filter, int limit, long offset) {
        var where = where(filter);
        var params = where.params();
        params.add(limit);
        params.add(offset);
        String order = filter.sortBy() + (filter.ascending() ? " ASC" : " DESC")
                + (filter.sortBy().equals("id") ? "" : ",id ASC");
        return sql("courses", () -> jdbc.query("SELECT " + COLUMNS + " FROM course" + where.sql()
                + " ORDER BY " + order + " LIMIT ? OFFSET ?", COURSE_ROW, params.toArray()));
    }

    private <T> T sql(String query, Supplier<T> statement) {
        metrics.counter("catalog.sql.queries", "query", query).increment();
        return statement.get();
    }

    private record Where(String sql, List<Object> params) {}

    private Where where(Filter filter) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (filter.type() != null) {
            sql.append(" AND type=?");
            params.add(filter.type());
        }
        if (filter.edu() != null) {
            sql.append(" AND edu<=?");
            params.add(filter.edu());
        }
        if (filter.keyword() != null) {
            sql.append(" AND REGEXP_REPLACE(name, '[[:space:]]+', '')")
                    .append(" LIKE CONCAT('%', REGEXP_REPLACE(?, '[[:space:]]+', ''), '%')");
            params.add(filter.keyword());
        }
        return new Where(sql.toString(), params);
    }

    private <T> T cached(String key, TypeReference<T> type, Supplier<T> loader) {
        return json.readValue(cache.get(key, () -> json.writeValueAsString(loader.get())), type);
    }

    private static String text(Map<String, Object> body, String key) {
        return body.get(key) == null ? null : body.get(key).toString();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
