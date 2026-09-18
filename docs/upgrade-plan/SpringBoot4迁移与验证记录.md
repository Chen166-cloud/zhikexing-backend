# Spring Boot 4 迁移与验证记录

日期：2026-09-18。JDK 保持 21。

## 实际版本与兼容修改

后端从 Spring Boot 3.5.3 / Spring AI 1.0.0 升级到 **Spring Boot 4.1.1 / Spring AI 2.0.1**。Spring AI 官方明确支持 Boot 4.0 / 4.1；MyBatis-Plus 改用官方 Boot 4 starter 3.5.17。[Spring AI 兼容说明](https://docs.spring.io/spring-ai/reference/getting-started.html)、[MyBatis-Plus 安装说明](https://baomidou.com/en/getting-started/)。

- Web 与测试依赖使用 webmvc starter，Flyway 使用 Boot 4 对应 starter，保留 MySQL 驱动、AMQP 和 Prometheus。
- Agent JSON 使用 Jackson 3；MyBatis-Plus 的 IService / ServiceImpl 改为 spring.service 包。业务接口与查询逻辑保持原行为。[Boot 4 迁移说明](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)、[MyBatis-Plus 3.5.17 发布说明](https://github.com/baomidou/mybatis-plus/releases/tag/v3.5.17)。
- Spring AI 的向量 advisor 依赖改名，Redis 向量客户端采用 RedisClient，并由 Spring 在关闭时释放连接池。旧 AI 入口仍默认关闭，启用时可以完成应用启动。[Spring AI 升级说明](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)、[Redis 向量客户端](https://docs.spring.io/spring-ai/reference/api/vectordbs/redis.html)。
- 聊天和 Embedding 配置移除 options 层级，仍分别选 qwen3.7-flash、text-embedding-v4 / 1024，共用 DASHSCOPE_API_KEY。AI 2 使用官方 OpenAI SDK，默认兼容接口改为 `https://dashscope.aliyuncs.com/compatible-mode/v1`；自定义 DASHSCOPE_BASE_URL 也须包含 `/v1`。[OpenAI 兼容接口配置说明](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)。
- Flyway V4 为旧聊天表新增 sequence_id，按每个会话的 timestamp、原 id 回填顺序，再添加非空约束和索引。保留原消息正文、时间、主键；不重建表、不删除历史。V1/V2/V3 文件未修改，避免破坏已部署校验和。

## 分析 → 行动 → 观察

先在独立临时项目副本编译，依据真实错误依次修正 advisor 依赖名称、MyBatis 包名、RedisClient 类型；通过后再启动真实 Spring Boot 应用验证。没有用额外旧版兼容依赖掩盖迁移问题。

1. 默认关闭旧入口：在新建隔离 MySQL `iiip_boot4_verify_20260918` 先迁移至 V3，插入两条同秒旧消息，再由 Boot 启动执行 V4。Tomcat 11.0.24、业务随机 HTTP 端口与管理随机端口启动成功；真实 MySQL + Redis 16379 / DB15 + HTTP 请求的 **4 项测试全部通过，14.98 秒**。
2. 启用旧入口并使用扁平模型配置：在独立 `iiip_boot4_legacy_verify_20260918` 重复真实迁移和启动，增加模型绑定断言，**5 项测试全部通过，14.94 秒**。
3. 通过可控本机 HTTP 端点实际接收 AI 2 SDK 发出的聊天、Embedding 请求，核对 `/compatible-mode/v1/chat/completions`、`/compatible-mode/v1/embeddings`、模型名称、请求维度 1024 及 usage 解析，**2 项测试全部通过，2.674 秒**。这里返回固定协议数据，验证 SDK 兼容行为，没有调用百炼，不能作为模型质量或真实供应商可用性的证据。

HTTP 回归覆盖注册登录与 BCrypt、Redis token、中文工作空间、知识库代理、运行提交与重放、交接 fallback、取消后禁止 retry、未登录和内部鉴权、CORS、health、prometheus。数据库回归确认旧消息内容和稳定顺序保留，Spring AI 2 JDBC repository 可以新写入并正确读取消息。

Redis 测试仅创建并删除本轮账号 token，没有 flush 数据库。原项目中会调用模型及写向量的既有测试没有执行。新增临时测试源及对应 class 已删除；保留本记录与正式 V4 迁移。完整临时副本 `D:\develop\iiip-boot4-verify-20260918` 的递归删除被自动审批策略拒绝（仅返回 blocked by policy），因此普通源码副本、jar 和测试报告仍在该目录，不将其记录为已清理。真实 Python、RabbitMQ、模型和前端的最终容器闭环由总体验收记录说明。
