package com.chy.ai.util;

import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class PasswordEncoder {
    private static final String CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int SALT_LENGTH = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordEncoder() {
    }

    public static String encode(String password) {
        return encode(password, randomSalt());
    }

    private static String encode(String password, String salt) {
        return salt + "@" + DigestUtils.md5DigestAsHex((password + salt).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean matches(String encodedPassword, String rawPassword) {
        if (!StringUtils.hasText(encodedPassword) || rawPassword == null || !encodedPassword.contains("@")) {
            return false;
        }
        String[] parts = encodedPassword.split("@", 2);
        if (parts.length != 2 || !StringUtils.hasText(parts[0])) {
            return false;
        }
        return encodedPassword.equals(encode(rawPassword, parts[0]));
    }

    private static String randomSalt() {
        StringBuilder salt = new StringBuilder(SALT_LENGTH);
        for (int i = 0; i < SALT_LENGTH; i++) {
            salt.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return salt.toString();
    }
}
