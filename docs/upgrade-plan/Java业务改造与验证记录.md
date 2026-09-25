# Java 业务改造与验证记录

核对日期：2026-09-25。Java 业务主线使用 MySQL、Redis 和 RocketMQ；接口、状态与页面协作见[研发接口契约](研发接口契约.md)与[试听模块设计](../modules/免费试听秒杀与Agent联动.md)。

## 当前实现

- Java 校验登录用户、空间成员和资源归属；普通 Agent 任务用 MySQL 接收记录及 outbox，RocketMQ 普通命令由 Java 消费者经内部 HTTP 交给 Python。Python 持久化 Run/取消状态后才确认，重复交付按 runId/request_hash 收敛。
- 普通预约保留固定草稿、审批版本 CAS、运行取消禁写和 actionId 幂等执行；它是预约意向记录，不含时段库存。
- 免费试听的活动、参与请求、0 元订单和审批工具类型由 Flyway 管理；OWNER 在 `/agent`“免费试听”标签创建、发布、暂停活动并查看对账，成员浏览活动、直接参与且只能查看本人的最近 100 条记录与单条结果。
- 提交参与先保存请求并返回 HTTP 202；RocketMQ 半消息的本地事务回调用 Redis Lua 原子预占，已提交消息由消费者异步在 MySQL 条件扣库存并创建唯一订单。事务回查、恢复、补偿和对账处理重试与不确定状态。
- `PENDING/RESERVED` 不算成功；只有持久请求 `SUCCEEDED` 且有 `orderId` 才能向用户承诺名额。Redis 预占数据丢失时停止受理并对账。
- MySQL 业务表的新写入主键由应用雪花算法生成；`SNOWFLAKE_NODE_ID` 为每个写入节点配置 0–1022 的唯一值，1023 留给迁移与演示数据。Flyway V6 保留已有单列数值主键值，为原复合键及聊天记录补雪花代理主键；迁移后应用表没有自增列或物理外键。跨表关联在服务层校验，数据库唯一键、非空约束与事务性库存条件更新继续生效。

## 已验证与限制

JDK 21 下默认 `mvn -q test` 通过。MySQL 8.4.8 的 Flyway V1–V7 新库迁移、16 张应用业务表结构与业务链路验证通过；结构检查为 0 自增列、0 物理外键。业务集成还验证 Spring AI JDBC 聊天记忆以雪花主键写入、读回和删除，并适配 Linux MySQL 表名大小写。依赖真实 embedding API、Redis 和本地 PDF 的 `ZhikexingApplicationTests` 仅在显式设置 `LEGACY_AI_LIVE_TEST=true` 时启用，不计入默认测试的外部服务覆盖。隔离真实 RocketMQ/Redis 的 15+3 项专项回归和 `zhikexing` 完整 Compose 的 fixture 跨服务烟测通过；真实浏览器完成注册、登录、首页、工作台和试听页面导航，详见[试听模块验证记录](../modules/免费试听秒杀验证记录.md)。真实模型、真实 API 的 MEMBER 浏览器权限和 Agent 审批、容量及生产高可用尚未验收。
