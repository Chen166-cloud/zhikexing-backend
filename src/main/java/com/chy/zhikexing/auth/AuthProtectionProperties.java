package com.chy.zhikexing.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProtectionProperties {
    private int globalAttempts = 20;
    private int globalWindowSeconds = 1;
    private int accountAttempts = 10;
    private int accountWindowSeconds = 60;
    private int maxConcurrent = 4;
    private int failureThreshold = 5;
    private int failureWindowSeconds = 600;
    private int cooldownSeconds = 60;
    private int maxSessions = 3;
}
