# Java 业务改造与验证记录

更新日期：2026-09-25。当前 Java 主线使用 MySQL、Redis 和 RocketMQ；具体交互及状态见[研发接口契约](研发接口契约.md)与[试听模块设计](../modules/免费试听秒杀与Agent联动.md)。

## 当前实现

- Java 校验登录用户、空间成员和资源归属；普通 Agent 任务用 MySQL 接收记录及 outbox，RocketMQ 普通命令由 Java 消费者经内部 HTTP 交给 Python。Python 持久化 Run/取消状态后才确认，重复交付按 runId/request_hash 收敛。
- 普通预约保留固定草稿、审批版本 CAS、运行取消禁写和 actionId 幂等执行；它是预约意向记录，不含时段库存。
- 免费试听增加 Flyway V5 活动、参与请求、0 元订单和审批工具类型；OWNER 管理活动与对账，成员查询/参与且仅能查看本人的结果。
- 提交参与先保存请求并返回 HTTP 202；RocketMQ 半消息的本地事务回调用 Redis Lua 原子预占，已提交消息由消费者异步在 MySQL 条件扣库存并创建唯一订单。事务回查、恢复、补偿和对账处理重试与不确定状态。
- `PENDING/RESERVED` 不算成功；只有持久请求 `SUCCEEDED` 且有 `orderId` 才能向用户承诺名额。Redis 预占数据丢失时停止受理并对账。

## 已验证与限制

隔离真实 MySQL 8.4.8、Redis 8.2.1、RocketMQ Broker 5.5.0 下 Java 专项测试 19/19 通过；40 名合成参与者竞争 8 名额，重复消费后最终 8 笔订单。跨服务 fixture 联调也通过。完整结果与复现方式见[试听模块验证记录](../modules/免费试听秒杀验证记录.md)。常用完整 Compose 尚未重建，新链路未做真实模型与性能验收；本页不声称生产高可用。
