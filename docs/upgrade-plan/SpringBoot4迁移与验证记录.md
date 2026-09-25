# Spring Boot 4 版本与验证记录

更新日期：2026-09-25。当前 Java 后端使用 JDK 21、Spring Boot 4.1.1、Spring AI 2.0.1、MyBatis-Plus Boot 4 starter 3.5.17、Flyway 和原生 RocketMQ client 5.5.1。消息配置使用 RocketMQ，不依赖 AMQP starter。具体版本以 [pom.xml](../../pom.xml) 和锁定镜像为准。

Boot 4/Jackson 3、MyBatis 服务包名、RedisClient 与百炼兼容地址 `/v1` 已适配；Flyway V5 增加试听活动、参与请求、订单和审批工具类型。Spring AI 相关旧入口默认关闭，当前 Agent 主线是独立 Python Runtime。官方兼容依据：[Spring AI 入门与版本](https://docs.spring.io/spring-ai/reference/getting-started.html)、[MyBatis-Plus 安装说明](https://baomidou.com/en/getting-started/)。

当前 Java 专项验证在 JDK 21 下编译/打包通过；真实 MySQL 8.4.8、Redis 8.2.1、RocketMQ Broker 5.5.0 的 19 项试听与普通命令桥接回归全部通过。完整复现条件见[试听模块验证记录](../modules/免费试听秒杀验证记录.md)。常用完整 Compose 尚未按新版重新构建验收；这里的隔离结果不等于生产兼容性或吞吐量证明。
