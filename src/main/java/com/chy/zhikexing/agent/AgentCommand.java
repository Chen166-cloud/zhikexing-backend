package com.chy.zhikexing.agent;

import java.util.Map;

/** Java 与 Python 共同使用的消息信封，id 在重试时保持不变。 */
public record AgentCommand(
        String id, String path, String actorId, String workspaceId, Map<String, Object> payload) {}
