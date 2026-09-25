# Spring Boot 4 版本与验证记录

核对日期：2026-09-25。Java 后端使用 JDK 21、Spring Boot 4.1.1、Spring AI 2.0.1、MyBatis-Plus Boot 4 starter 3.5.17、Flyway 和原生 RocketMQ client 5.5.1。消息配置使用 RocketMQ。具体依赖以 [pom.xml](../../pom.xml) 和锁定镜像为准。

Boot 4/Jackson 3、MyBatis 服务包名、RedisClient 与百炼兼容地址 `/v1` 已适配；Flyway V1–V7 管理业务结构，试听活动、参与请求、订单和审批工具类型均在 MySQL 中。新写入业务主键由应用雪花算法生成，应用表无自增列或物理外键，逻辑关联由服务层校验；Agent 运行主线为独立 Python Runtime。官方兼容依据：[Spring AI 入门与版本](https://docs.spring.io/spring-ai/reference/getting-started.html)、[MyBatis-Plus 安装说明](https://baomidou.com/en/getting-started/)。

当前 Java 在 JDK 21 下编译通过；雪花发号、试听协议、隔离 MySQL 8.4.8 的 Flyway V1–V7 新库迁移及业务集成通过。业务集成覆盖活动→请求→订单及 Spring AI JDBC 聊天记忆的雪花主键写入、读回和删除；聊天记忆使用适配 Linux MySQL 大小写敏感表名的小写 dialect。隔离真实 Redis/RocketMQ 的 15+3 项集成测试及 `zhikexing` 完整 Compose 的 Agent fixture 跨服务烟测通过，详见[试听模块验证记录](../modules/免费试听秒杀验证记录.md)。这些结果不等于真实模型效果、生产兼容性或吞吐量证明。
