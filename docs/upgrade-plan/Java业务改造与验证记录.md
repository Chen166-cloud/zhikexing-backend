# Java 业务改造与验证记录

更新日期：2026-09-29。Java 业务主线使用 MySQL、Redis、RocketMQ，并通过 Caffeine 与 Redisson 加速课程展示查询。当前源码已更新到完整 Compose；接口、状态与页面协作见[研发接口契约](研发接口契约.md)与[试听模块设计](../modules/免费试听秒杀与Agent联动.md)。

## 当前实现

- 登录/注册共用 Redis Lua 全局限流与本机 `Semaphore` 并发槽；账号按数据库用户 ID 在 BCrypt 前检查频率和失败冷却。默认全局 20 次/秒、账号 10 次/分钟、每实例认证并发 4，10 分钟内失败 5 次后冷却 60 秒。
- 会话通过 Redis ZSET 限制每账号最多 3 个，原子创建与淘汰最早创建会话；续期、退出、昵称更新不会复活已失效 Token。24 小时空闲 TTL 保留，新 `login:v2:*` 命名空间使旧 Token 需重新登录。429/503 保留账号接口响应格式并携带 `Retry-After`，CORS 暴露该头，Vue 登录/注册页按秒倒计时。
- 课程广场支持名称搜索、方向/学历筛选、价格/周期排序、分页和详情；课程 ID 返回字符串，价格为人民币元、周期为天。课程页、Agent 内部工具和试听活动选项共用 `CourseCatalogService`，登录、运行与空间成员校验仍先于缓存访问。校区是全局目录，不表示课程与校区的开设关系。
- 课程默认首页、详情、Agent 默认列表和固定选项使用 Caffeine 本地缓存（10 秒、最多 1000 项）与 Redis 共享缓存（60 秒）。Caffeine 合并同 JVM 加载；Redisson `RLock` 协调跨 JVM 冷缓存回源，拿锁后二次检查 Redis，使用 watchdog 续租并在 finally 释放。任意搜索、筛选和其他分页直接查 MySQL；库存、审批、权限和会话不进入课程缓存。锁等待超时或 Redis 冷缓存不可用返回 503，已有本地缓存可使用至过期；详见[课程目录与两级缓存](../modules/课程目录与两级缓存.md)。
- Java 校验登录用户、空间成员和资源归属；普通 Agent 任务用 MySQL 接收记录及 outbox，RocketMQ 普通命令由 Java 消费者经内部 HTTP 交给 Python。Python 持久化 Run/取消状态后才确认，重复交付按 runId/request_hash 收敛。
- 普通预约保留固定草稿、审批版本 CAS、运行取消禁写和 actionId 幂等执行；它是预约意向记录，不含时段库存。
- 免费试听的活动、参与请求、0 元订单和审批工具类型由 Flyway 管理；OWNER 在 `/agent`“免费试听”标签创建、发布、暂停活动并查看对账，成员浏览活动、直接参与且只能查看本人的最近 100 条记录与单条结果。
- 提交参与先保存请求并返回 HTTP 202；RocketMQ 半消息的本地事务回调用 Redis Lua 原子预占，已提交消息由消费者异步在 MySQL 条件扣库存并创建唯一订单。事务回查、恢复、补偿和对账处理重试与不确定状态。
- `PENDING/RESERVED` 不算成功；只有持久请求 `SUCCEEDED` 且有 `orderId` 才能向用户承诺名额。Redis 预占数据丢失时停止受理并对账。
- MySQL 业务表的新写入主键由应用雪花算法生成；`SNOWFLAKE_NODE_ID` 为每个写入节点配置 0–1022 的唯一值，1023 留给迁移与演示数据。Flyway V6 保留已有单列数值主键值，为原复合键及聊天记录补雪花代理主键；迁移后应用表没有自增列或物理外键。跨表关联在服务层校验，数据库唯一键、非空约束与事务性库存条件更新继续生效。

## 已验证与限制

2026-09-29 登录保护：10 项 Mockito/MockMvc 流程测试和 12 项隔离 Redis 8.2.1 测试全部通过，覆盖 BCrypt 前拒绝、异常释放并发槽、共享额度、失败冷却及会话淘汰竞态；`mvn -q clean package` 通过。Vue 构建与 mock API 浏览器验证通过。完整 Compose 的真实 8088 API 进一步验证第 4 个会话淘汰最早 Token、退出失效、第 5 次密码错误触发 429 冷却和 `Retry-After`；浏览器完成注册、退出和重新登录，详见[登录保护](../modules/登录保护.md)。

课程目录新增 14 项测试全部通过：7 项真实 Redis/跨 JVM 缓存测试、4 项真实 MySQL 查询测试、3 项鉴权测试。两个独立 Java 进程同时访问冷缓存时合计只执行一次 loader；该结果验证锁协作，不是吞吐结论。`mvn verify` 为 42 项通过、22 项未启用的外部集成/旧入口测试跳过。前端生产构建、课程页面 mock API 检查及 Agent 14 项 fixture 回归通过。

同日重建 Java、Vue、Agent 三个镜像并启动 `app`、`observability`：17 个常驻服务运行，15 个配置健康检查的服务均 healthy，3 个初始化任务成功退出。真实 8088 入口验证课程登录鉴权、首页/搜索/详情/404，浏览器完成课程广场→搜索→详情→Agent 咨询预填，预填没有 API 写请求；Prometheus 已观测到两级缓存命中和回源计数，5 个抓取目标均正常。`AI_PROVIDER=fixture` 下重新通过直接抢课、同键幂等、Agent 审批落单与库存守恒检查，完整记录见[Docker 部署验证记录](../deployment/Docker部署验证记录.md)。

2026-09-26 的基础验证继续保留：MySQL 8.4.8 上 Flyway V1–V7 新库迁移与业务链路通过，16 张应用业务表为 0 自增列、0 物理外键；Spring AI JDBC 聊天记忆使用雪花主键写入、读回和删除通过。隔离真实 RocketMQ/Redis 的 15+3 项专项回归及当时的 Compose fixture 烟测通过，详见[试听模块验证记录](../modules/免费试听秒杀验证记录.md)。依赖真实 embedding API、Redis 和本地 PDF 的 `ZhikexingApplicationTests` 仅在 `LEGACY_AI_LIVE_TEST=true` 时启用。

真实模型、真实 API 的 MEMBER 浏览器权限与浏览器内 Agent 审批、会话淘汰后的浏览器任务续跑、容量及生产高可用仍需验收。课程展示数据默认允许约 60 + 10 秒缓存延迟，Agent 的单 run 结果缓存还可能叠加；未提供 QPS、P95 或吞吐提升百分比。
