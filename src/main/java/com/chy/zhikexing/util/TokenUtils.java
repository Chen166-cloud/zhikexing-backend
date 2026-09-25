package com.chy.zhikexing.util;

import org.springframework.util.StringUtils;

public class TokenUtils {
    private static final String BEARER_PREFIX = "Bearer ";

    private TokenUtils() {
    }

    public static String normalize(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return "";
        }
        String token = authorization.trim();
        if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return token.substring(BEARER_PREFIX.length()).trim();
        }
        return token;
    }
}
