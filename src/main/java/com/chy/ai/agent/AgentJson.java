package com.chy.ai.agent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Component
public class AgentJson {
    private final ObjectMapper mapper;

    public AgentJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON编码失败", e);
        }
    }

    public Map<String, Object> read(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON格式错误", e);
        }
    }

    public List<Map<String, Object>> readList(byte[] value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON列表格式错误", e);
        }
    }

    public String hash(Object value) {
        try {
            Object ordered = mapper.readValue(write(value), Object.class);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(write(sort(ordered)).getBytes(StandardCharsets.UTF_8)));
        } catch (JacksonException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(k.toString(), sort(v)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sort).toList();
        return value;
    }

    public static String required(Map<String, ?> body, String key, int max) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + "不能为空或超过长度限制");
        return text.trim();
    }

    public static ResponseStatusException error(HttpStatus status, String text) {
        return new ResponseStatusException(status, text);
    }
}
