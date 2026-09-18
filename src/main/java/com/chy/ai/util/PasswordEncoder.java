package com.chy.ai.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class PasswordEncoder {
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);
    private PasswordEncoder() {}
    public static String encode(String password) { return BCRYPT.encode(password); }
    public static boolean needsUpgrade(String encoded) { return encoded != null && !encoded.startsWith("$2"); }
    public static boolean matches(String encoded, String raw) {
        if (encoded == null || raw == null) return false;
        if (encoded.startsWith("$2")) return BCRYPT.matches(raw, encoded);
        // 旧密码只用于验证，登录通过后升级为 BCrypt。
        String[] parts = encoded.split("@", 2);
        if (parts.length != 2) return false;
        String legacy = parts[0] + "@" + DigestUtils.md5DigestAsHex((raw + parts[0]).getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(encoded.getBytes(StandardCharsets.UTF_8), legacy.getBytes(StandardCharsets.UTF_8));
    }
}
