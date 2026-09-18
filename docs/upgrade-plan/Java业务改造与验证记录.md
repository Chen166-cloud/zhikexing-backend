# Java 业务服务改造与验证记录

日期：2026-09-18。本记录只写已经执行的动作与观察，不把目标当成测试结果。

## 已实现

- `/api/v1` 登录保护、个人/团队工作空间与成员访问校验，内部工具复核 actor、workspace、run 归属。
- MySQL 事务保存任务请求和 outbox；相同 clientRequestId 同内容返回同一 runId，不同内容返回 409。后台有界批量投递、指数退避，重启后继续扫描。
- 预约草稿持久化，15 分钟有效期；审批使用 expectedVersion 比较更新；提交只读取已批准参数。run/action 唯一键与事务保证重复提交返回原预约。
- 取消和执行预约锁定同一运行记录；取消完成后禁止新草稿、提交、重试和补充输入。已提交的预约可继续通过动作编号查询。
- SSE 透传事件编号与消息类型，支持 after / Last-Event-ID；客户端写入失败关闭上游输入流。首次订阅允许等待 outbox 交接最多 5 秒。
- 百炼聊天与 Embedding 共用 DASHSCOPE_API_KEY（迁移兼容 API-KEY）；默认 qwen3.7-flash、text-embedding-v4 / 1024。移除 DeepSeek 依赖。
- 新密码使用 BCrypt，旧盐化 MD5 在成功登录时渐进升级；删除匿名身份回退用户 1；异步 servlet 释放线程时清理 UserHolder；CORS 限定配置的前端地址。
- 默认关闭旧 AI 入口并返回明确 410 迁移说明。启用旧入口时，PDF 上传检查会话归属与文件签名、向量包含 user_id、检索使用类型化过滤；旧课程排序使用白名单；旧直接写预约工具已经移除。
- Flyway V1 在目标库中按需创建旧业务表，V2 新建 Agent 表，不删改用户原数据库。

## 分析 → 行动 → 观察

1. 初次 Maven 编译暴露 DeepSeek 残留导入、HTTP 类型名称冲突；修正后编译通过。
2. 使用宿主 MySQL 9.4 创建独立 `iiip_agent_verify_20260918` 数据库。V1/V2 两份真实迁移成功；第二次运行验证版本和校验和，无重复执行。
3. 第一轮 10 项测试中，9 项通过，真实并发提交暴露 MySQL 默认 REPEATABLE_READ 快照问题：成员查询提前建立快照，获得运行锁后仍未看到先前已提交结果，第二个请求触发唯一键冲突。数据库事务回滚避免了重复预约，但响应没有满足幂等要求。
4. 将业务事务独立设置为 READ_COMMITTED，保留运行行锁。第二轮 10 项全部通过；增加并发审批、取消竞争、异步身份清理后，最终 **13 项全部通过，0 失败、0 错误，7.398 秒**。
5. `mvn -q -DskipTests package` 成功。真实 Spring Boot 应用连接隔离 MySQL 启动，HTTP 18080 与管理端口 18081 均成功监听，启动日志确认 Started IiipApplication。
6. 按用户要求删除新增临时测试源 `AgentBusinessVerificationTest.java` 与对应编译 class；保留原有测试文件。本次没有执行原有会真实调用模型并写向量库的测试。

## 已通过的 13 项回归

1. BCrypt/旧密码验证及匿名用户拒绝。
2. 工作空间隔离、个人空间幂等初始化。
3. 请求重放、JSON 键排序稳定、同键不同内容冲突。
4. 未批准不能提交、审批版本冲突、重复执行只一笔预约。
5. 同动作改参数被拒绝、他人审批不可读。
6. 取消后不能草稿/执行/重试。
7. 审批过期后不能执行。
8. 两线程同时执行同动作返回同一预约。
9. 内部 Token、运行归属检查及排序注入拒绝。
10. 旧 PDF 上传在写文件前检查会话所有权。
11. 两个并发审批决定只有一个版本更新成功。
12. 取消与执行竞争时，取消返回后不再新增预约。
13. 无登录请求和 servlet 异步交接均清除线程身份。

## 验证边界

此处数据库回归没有调用模型或运行 Python；模型可用性、端到端 RAG、前端交互和 SSE 重连结果以总体验收报告为准。数据库测试使用 MySQL 9.4；Flyway 提示其声明的已测试版本上限低于 9.4，实际迁移及事务测试通过。部署配置应使用固定 MySQL 8.4 镜像并继续验证。

临时启动进程已停止，避免 Windows 锁定 jar 干扰重新打包。后续联调环境由独立 Python 项目和部署配置提供。

## RabbitMQ outbox 增量验证（2026-09-18）

新增 `RABBITMQ_ENABLED` 明确选择传输：默认 `false` 使用 HTTP，设为 `true` 使用 RabbitMQ。不会在消息服务器失败时自动切换 HTTP。

- durable direct exchange `iiip.agent`，routing key `commands`，durable queue `iiip.agent.commands`。
- 死信 exchange `iiip.agent.dlx`，routing key `commands.failed`，queue `iiip.agent.failed`。生产者与消费者须使用相同队列参数。
- 信封为 `{id,path,actorId,workspaceId,payload}`，id 是稳定 outboxId，actorId 是字符串。消息持久化；每次发送使用独立 confirm 相关编号。
- 开启 correlated publisher confirms 与 mandatory returns。只在 broker ack 且没有退回消息时记为 DELIVERED；nack、退回、超时都保留重试。确认语义依据 [Spring AMQP CorrelationData 官方说明](https://docs.spring.io/spring-amqp/api/org/springframework/amqp/rabbit/connection/CorrelationData.html)。
- Flyway V3 增加 `lease_owner` / `lease_expires_at`。领取仅使用短数据库事务，网络发送在事务外执行；45 秒租约过期可重领，晚到确认不能结束别人的新租约。同一运行的前序消息未完成时，不领取后序取消消息。

在新建的隔离 MySQL 数据库 `iiip_outbox_verify_20260918` 中，V1/V2/V3 迁移成功；8 项新增测试全部通过，0 失败、0 错误，4.870 秒。覆盖：发送期间没有数据库事务、确认失败重试仍使用原消息编号、过期租约恢复、晚到确认拒绝、多个 dispatcher 保持同 run 顺序、持久消息与独立确认编号、broker nack/mandatory return、confirm 超时。租约部分使用真实 MySQL；确认部分使用 Spring RabbitTemplate mock，不能代替真实 RabbitMQ 端到端验收。

新增临时测试 `AgentOutboxVerificationTest.java` 及其编译 class 在完成后删除。Broker ack 仅表示消息接收成功，不表示 Python 已执行任务；消费端仍须持久化幂等处理，允许至少一次投递。真实容器、消费者、死信队列和界面观测的结果由总体联调报告补充。

## 浏览器联调后的 CORS / HTTP 代理与幂等修正（2026-09-18）

真实 API 复现：新建验证账号后，注册和工作空间成功，但 POST 知识库返回 Python 422（缺少 body）。随后在本机真实 Uvicorn 隔离端点对比：JDK 默认客户端发送 POST 后，服务端收到 `length=0`、空 body，并报告 Unsupported upgrade request；显式 HTTP/1.1 的生产代理保留完整中文 JSON。`AgentRuntimeClient` 已固定 HTTP/1.1，避免 h2c 升级兼容问题。

CORS 白名单集中在 `application.yaml`：默认允许 localhost / 127.0.0.1 的 5173、5176、8088 六个来源。`CORS_ORIGINS` 可完整替换默认列表。临时 MockMvc 使用实际 MVC 配置验证六个来源的预检和 POST 均通过，其它域名及 5177 端口被拒绝，自定义白名单可以替换默认值。

同时修复：校验后用于 outbox 的会话/请求编号统一规范化；重复取消只产生一条取消消息；草稿重放使用原审批显示名称，不因课程改名而破坏幂等；拒绝会被 MySQL 自动转换的非数字课程/校区编号；审批版本必须是正整数；已执行动作的重复调用仍须匹配原审批编号。

该轮新增 8 项测试全部通过，0 失败、0 错误，5.814 秒。其中 HTTP 请求体使用真实 Uvicorn，业务回归使用隔离 MySQL。临时测试源、相关编译 class 及 Uvicorn 测试进程已清理。Agent Java 包统一排版，保留中文关键边界注释；最终 Maven 打包与 Docker 重建分别验证。

## 提交路由与异步交接查询验证（2026-09-18）

实际容器请求发现 `/api/v1/runs` 的 POST 同时匹配 submit 与泛化代理，导致运行提交 500。代理现按 Python 契约分别注册 GET、POST、DELETE；运行提交和 multipart 文档上传各自只保留一个入口，其它方法组合返回 405，JSON 文档上传返回 415。

已授权的 `GET /api/v1/runs/{id}` 在执行服务返回 404 时，读取 MySQL 中同一用户的 task_request 和创建 outbox，返回 `status: QUEUED`、`execution: null`、`submission`，并禁止缓存。submission 包含投递编号、状态、尝试次数、稳定错误码、下次重试时间、租约到期时间、取消受理标记和说明。DELIVERED 只说明 Java 的发送已确认，不代表消费者接收或执行成功；Python 返回真实运行记录时直接使用其状态。未授权、未知 run、其它资源 404 和上游 403/503 均不会触发回补。

HTTP outbox 独有 `X-Command-Delivery: true`，普通代理不接受客户端伪造该头。Python 可以据此把已异步接受任务的会话冲突持久化为 FAILED；同一 runId 不同 payload 的冲突仍保持 409。HTTP 非成功投递保存 HTTP_409 等状态码，Rabbit 拒绝/退回使用 BROKER_NACK / BROKER_UNROUTABLE，不记录上游响应正文或凭据。

新建独立 `iiip_submission_verify_20260918` 并执行 V1/V2/V3，使用真实 MockMvc 路由、真实 MySQL 和本机 HTTP 服务完成 **9 项测试，0 失败、0 错误，4.454 秒**：提交/列表路由、multipart 新版本上传、禁用非法方法、待投递回补、租约/投递确认/取消事实、同会话两次异步提交后第二次 HTTP 409 可见、未知及他人 run 拒绝、其它错误保持原义、可信投递头隔离。测试中的 HTTP 服务是可控故障注入端点，不代表真实 Python 冲突持久化和 Rabbit 消费已通过端到端验收。临时测试源和编译 class 在测试后删除。

运维可只读查看 `agent_outbox` 的 id、run_id、path、status、attempts、last_error、next_attempt_at、lease_expires_at，结合 Rabbit 管理界面的 `iiip.agent.commands` / `iiip.agent.failed` 队列数量定位交接问题。不要把 DELIVERED 当作消费成功，也不要为诊断而清空、消费或重新投递队列。具体业务失败以运行终态事件为准。

## 课程名称空白归一化（2026-09-18）

真实 MQ 流程发现，用户输入 `Python与AI应用演示课程` 与数据库名称 `Python 与 AI 应用演示课程` 仅空格不同，原 LIKE 查询却返回空结果。课程工具现对名称和绑定关键词使用相同的 MySQL 空白归一化表达式后做包含匹配，涵盖普通空格、换行、制表符、全角空格和不换行空格；返回及存储仍使用原课程名，没有修改业务表。

在独立 `iiip_course_verify_20260918` 执行 V1–V4 后，通过真实 MySQL + MockMvc 验证 10 项：7 种完整名称/部分关键词及空白变体命中、不匹配词返回空结果、SQL 注入样式文本作为绑定参数无匹配、原名称保持不变。全部通过，新增测试源及 class 在测试后删除。本轮 Java 改动仅限课程查询；模型重复空结果的处理由 Python 运行时单独验证。
