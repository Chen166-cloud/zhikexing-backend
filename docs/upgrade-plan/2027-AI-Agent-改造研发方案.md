# 知课行 · AI 课程服务平台研发方案

**面向 2027 届 AI Agent 研发与后端开发岗位的改造研发方案**

更新日期：2026-09-29。适用对象：同时准备 Agent 和后端岗位，Agent 内容更多，允许引入 Python，追求完整工程。知课行当前由三个独立仓库构成；本文区分已实现能力与尚未完成的性能、效果目标。

真实模型模式配置阿里云百炼 `qwen3.7-flash` 和 `text-embedding-v4` / 1024 维，共用一把百炼 API Key；当前完整 Compose 业务联调使用 `AI_PROVIDER=fixture`。具体配置见[模型服务与 API 配置](./模型服务与API配置.md)。

2026-09-29 已完成[登录保护](../modules/登录保护.md)与[课程目录及两级缓存](../modules/课程目录与两级缓存.md)。BCrypt 前限制全局/账号频率和本机并发，失败后短暂冷却，每账号最多 3 个会话；课程广场、Agent 查询与试听目录共用 Java 查询服务，通过 Caffeine、Redis 和 Redisson 减少热点展示数据的重复回源。

同日已重建 Java、Vue 和 Agent 镜像并更新完整 `app`、`observability` Compose。17 个常驻服务运行，15 个配置健康检查的服务均 healthy，3 个初始化任务成功退出；真实 8088 API 复验会话上限、冷却、课程查询与缓存命中，浏览器通过注册→退出→登录→课程搜索→详情→Agent 咨询预填。fixture 直接抢课与 Agent 审批落单回归通过。下文保留历史验证及后续设计，最新证据见[Docker 部署验证记录](../deployment/Docker部署验证记录.md)。

当前工程包括 Java 业务后端、Python Agent Runtime、知课行 Vue 网站、多空间权限、知识检索和引用、审批恢复、幂等预约、取消、规则评测及可观测。网站提供登录/注册、产品首页、`/courses` 课程广场、`/courses/:id` 详情与 `/agent` 工作台；首页为功能概览和导航，业务状态由相应页面读取。[免费试听名额秒杀与 Agent 联动](../modules/免费试听秒杀与Agent联动.md)覆盖活动、参与请求、RocketMQ 事务消息、Redis Lua 预占、MySQL 条件库存和 0 元订单、可恢复消费与对账、Agent 审批及回查；`/agent`“免费试听”标签提供 OWNER 活动运营、成员直接抢课和本人记录追踪。同一 RocketMQ Broker 还承载普通 Agent 命令，由 Java 消费者通过内部 HTTP 交给 Python；当前 Agent 图为 `react-trial-approval-v4`。MySQL 新写入业务主键使用雪花 ID，应用表采用逻辑外键。MySQL Flyway V1–V7 新库迁移、Java 单测、前端构建、隔离真实 Redis/RocketMQ 的 15+3 项测试及 `zhikexing` 完整 Compose 的 Agent fixture 跨服务烟测通过。真实百炼模型、真实 API 的 MEMBER 浏览器权限和浏览器内 Agent 审批仍需验收。具体边界见[专项验证记录](../modules/免费试听秒杀验证记录.md)和[岗位调研](../research/2026-09-25-秒杀模块岗位与技术调研.md)。

## 1. 当前项目定位与已实现范围

知课行是 **Java 业务后端 + Python Agent Runtime + Vue 网站** 的课程浏览与咨询、知识问答、普通预约和免费试听抢课平台。Java 负责身份、空间权限、课程查询与缓存、审批、业务事务、库存与订单；Python 负责 LangGraph 运行、检索、checkpoint 和评测；Vue 提供登录注册、产品首页、课程广场与展示 SSE、引用、人工审批的工作台。真实模型模式配置百炼 qwen3.7-flash 与 text-embedding-v4 / 1024 维；已执行的完整 Compose 联调使用 fixture 模型。

现有代码具备工作空间、文档版本/引用、普通预约草稿与幂等写入、Agent 运行恢复，以及试听活动页面、RocketMQ 事务消息、Redis Lua 预占、MySQL 0 元订单、对账、本人记录和 Agent 试听审批。MySQL 新库迁移、隔离真实 Redis/RocketMQ 正确性、完整 Compose 的 fixture 跨服务业务链路及真实浏览器网站入口已验证；真实模型试听工具、真实 API 的 MEMBER 浏览器权限与浏览器 Agent 审批、性能分位数和高可用仍需单独验证。

本方案的后续重点是真实模型、浏览器中尚未覆盖的 MEMBER/Agent 路径、故障恢复、容量测试、RAG 质量评测以及必要的生产安全建设。各能力的当前状态以[产品功能说明书](../product/产品功能说明书-研发版.md)、[接口契约](研发接口契约.md)和[试听模块验证记录](../modules/免费试听秒杀验证记录.md)为准。

## 2. 当前架构的关键取舍

| 取舍 | 当前实现 | 后续验证重点 |
|---|---|---|
| Java 与 Python 分工 | Java 守住业务事务；Python 运行 Agent 和知识工作流 | 可信身份传递、跨服务故障与升级兼容 |
| 登录资源控制 | Redis 共享频率与失败冷却，Java `Semaphore` 限本机认证并发，ZSET 限会话数量；已通过隔离测试和 Compose API 冷却/淘汰复验 | 浏览器会话淘汰后任务续跑、真实冷却倒计时；默认额度不代表容量测量 |
| 课程展示缓存 | Caffeine 本地缓存、Redis 共享缓存，Redisson 锁合并跨 JVM 回源；课程页面、Agent、试听选项共用查询服务，鉴权先于缓存 | TTL 更新延迟、冷热缓存对照和端到端性能；不缓存库存、权限、审批或登录态 |
| MySQL 与 PostgreSQL/pgvector | MySQL 记业务最终账，PG 记运行、消息、文档和向量 | 独立备份恢复、跨服务结果对账 |
| Redis 与 MySQL | Lua 做快速原子准入；MySQL 条件库存和唯一键做最终约束 | 缓存丢失时停受理、人工裁决与新活动恢复 |
| RocketMQ | 一个 Broker，两类 Topic：普通 Agent 命令和试听事务消息 | 重复投递、事务 UNKNOWN、重试/DLQ 与多副本可用性 |
| Agent 审批 | 模型只能查询和起草，用户批准固定参数，Java 按 actionId 幂等执行 | checkpoint 兼容、取消与已受理动作竞争 |
| 业务 ID 与关联 | MySQL 新写入主键用雪花 ID，服务校验逻辑外键；V6 保留已有单列数值主键 | 节点号唯一性、时钟回拨、跨表校验和迁移复验 |
| 正确性与性能 | MySQL 结构、隔离真实 Redis/RocketMQ 的 15+3 项测试与完整 Compose fixture 业务链路已验证；浏览器完成网站入口导航 | 测量受理/落单 p95、Broker 积压和数据库锁等待；隔离测试不等于生产吞吐 |
## 3. 2027 岗位调研如何影响方案

### 3.1 区分三种岗位，避免学错主线

| 岗位方向 | 主要能力 | 你的项目应证明什么 |
|---|---|---|
| Agent 应用/平台工程 | 多步执行、Tools、状态、RAG、记忆、评测、成本与稳定性 | 对不确定的模型行为建立可控系统，并用数据迭代 |
| 通用后端/AI 业务后端 | Java/Go 等语言、数据库、缓存、消息、并发、网络、可靠性 | 服务边界、业务一致性、资源治理、故障定位和工程质量 |
| Agent 算法/模型研究 | 训练优化、复杂推理、算法实验、论文、模型 benchmark | 通常还需要科研/算法成果；应用工程项目不能替代这些要求 |

已核实的代表性官方样本：

- 字节 **Agent开发工程师 - AI Platform，A04133**：页面明确 2027 届、正式校招，包含规划、工具、多智能体、反馈迭代与 Go/Python/C++，也列出 LangChain、LangSmith 相关经验，Langfuse 属于加分项。[官方职位](https://jobs.bytedance.com/campus/position/7667885791859837237/detail)
- 阿里 **AI应用研发工程师，199907620013**：官方明确 2027 应届生、27 届秋招，包含 Java/Python/JS、RAG/上下文、工具、评测、追踪、异步与降级。[官方职位](https://campus-talent.alibaba.com/campus/position/199907620013)
- 阿里 **Agent Infra工程师，199907640058**：2027 秋招，涉及调度、状态、checkpoint 恢复、委托身份、隔离、AgentOps 与多租户。[官方职位](https://campus-talent.alibaba.com/campus/position/199907640058)
- 字节 **后端开发工程师 - 火山引擎，A239884**：2027 正式校招，语言包括 Java，要求理解 MySQL/Redis/MQ、异步、高可用与扩展性。[官方职位](https://jobs.bytedance.com/campus/position/7667877341592373509/detail)
- 百度 **2027AIDU-Agent应用全栈工程师，J99974**：规划、工具、长短期记忆、RAG、状态，以及成功率/稳定性/成本/延迟评测。[官方职位](https://talent.baidu.com/jobs/detail/GRADUATE/6f9c3a86-6557-409d-8fa7-e6f4c68d6765)

配套调研共保留 **8 个官方 2027 完整 JD（其中 1 个为算法研究边界样本）和 5 个实习/社招参照**。另一个阿里 2027 AI 应用职位是实习，已经单独标注；美团只有可读官方列表片段的样本不混入完整 JD 数量。腾讯/京东社招也不标成 2027 秋招。

这些样本支持“Agent 工程能力 + 扎实后端”的组合。它们不构成全市场岗位占比调查，也不意味着所有团队使用同一套框架。最终投递时需要重新核对岗位状态和毕业时间要求。

### 3.2 能力到工程产物的映射

| JD 能力 | 研发落点 | 可提交成果 |
|---|---|---|
| 任务规划、工具调用 | LangGraph 状态图、结构化参数、执行预算 | 正常/失败轨迹、工具选择混淆矩阵 |
| RAG、知识处理 | 版本化 ingestion、混合召回、rerank、页码引用 | gold dataset、召回报告、失败样例 |
| 记忆和上下文 | append-only 消息、摘要、事实来源、Token 预算 | 长对话对照实验、隐私删除测试 |
| 多 Agent | 两个只读专家并行分析，由协调器合并 | 与单 Agent 同条件的成本/质量对比 |
| 评测和可观测 | trace、rubric、规则 grader、回归门禁 | 可复现实验脚本和评测工作台 |
| 后端并发与可靠性 | outbox、Run 幂等接收、试听持久请求、租约与资源隔离 | 故障注入、队列积压恢复、压测曲线 |
| 工程协作 | OpenAPI、ADR、CI、容器部署 | 一键启动、契约测试、版本与变更记录 |

## 4. 热门技术的证据与选型

### 4.1 从公开实践能确认什么

| 公开一手资料 | 可以确认的方向 | 对本项目的影响 |
|---|---|---|
| [阿里 Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba) | Java 生态已有 Agent/Graph、工具、上下文和多 Agent 能力 | Java 没有失去 AI 应用价值；纯 Java 是可行备选 |
| [CloudWeGo Eino](https://www.cloudwego.io/docs/eino/overview/) | Go 生态关注编排、流式、状态、中断与恢复 | 学通这些概念，比同时学三种语言更有效 |
| [腾讯 tRPC-Agent-Go 官方接入文档](https://cloud.tencent.com/document/product/248/125043) | 腾讯公开 Go Agent 框架与可观测集成 | Agent 与服务治理/追踪需要一起设计 |
| [Coze Studio](https://github.com/coze-dev/coze-studio) | 开源平台含模型、知识、工作流、资源和应用管理 | 参考产品资源关系与运行调试，不照搬整个平台 |
| [美团 Agent 评测白皮书，2026-09-10](https://tech.meituan.com/2026/09/10/Agent-Evaluation-White-Paper-01.html) | 评测需要覆盖行为、环境状态及明确成功标准 | 把“预约是否成功落库”加入评测，而不只评回答文风 |
| [Anthropic Context Engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) | 长任务需要主动组织有限上下文 | 将上下文管理做成有版本、可测量的模块 |

以上“公司开源/公司公开实践”不等于“该公司全部生产系统都采用”，也不构成选某框架就更容易拿 offer 的证据。

### 4.2 三条路线比较

| 路线 | 优点 | 代价 | 本次建议 |
|---|---|---|---|
| 纯 Java：Boot + Spring AI Alibaba | 最大化复用，单语言容易调试，适合 Java 后端主线 | Python 评测/文档/模型生态接入仍需要额外工作 | 保留为备选，不与主编排器同时建设 |
| **Java + Python：Boot + FastAPI + LangGraph** | 保留后端优势，直接使用 Python Agent 与实验生态，贴合两类岗位 | 跨服务身份、契约、状态与失败处理更复杂 | **主方案**，通过清晰所有权控制复杂度 |
| 纯 Python：FastAPI + LangGraph | 技术栈统一，Agent 迭代快 | 需重写已有 Java 业务，降低 Java 后端展示空间 | 本项目不优先；可用于独立实验 |

### 4.3 推荐组件与边界

| 组件 | 选择 | 负责解决的问题与边界 |
|---|---|---|
| Java 服务 | Java 21、Spring Boot、Spring Security、MyBatis-Plus、Flyway | 权限、交易性业务、审计、工具执行；先模块化单体 |
| Agent 服务 | Python 3.13、FastAPI、Pydantic、LangGraph | schema、异步 IO、状态图与持久化运行 |
| 业务数据库 | MySQL | 用户、课程、校区、预约、审批；试听活动、库存、请求与 0 元订单 |
| Agent/知识数据库 | PostgreSQL + pgvector | 持久化 checkpoint、run/event、文档 chunks、向量和评测元数据 |
| 缓存与协调 | Redis + Caffeine + Redisson | Redis 保存登录保护、会话和试听 Lua 状态；Caffeine/Redis 两级课程展示缓存，Redisson 仅协调缓存重建；MySQL 仍是最终库存真相 |
| 消息队列 | RocketMQ | Agent 普通命令与试听事务消息分别使用 Topic；Java 消费者幂等桥接 Python，异步落单与故障恢复 |
| 文件 | MinIO（S3） | 知识原文、解析产物与观测对象 |
| 检索 | pgvector dense + 词法基线；成熟后加 Elasticsearch BM25 | 用实验证明额外搜索引擎的必要性，避免一开始同时上多种向量库 |
| 文档解析 | Docling，先与现有 PDF reader 做对照 | 布局、表格、OCR；耗 CPU 的解析使用独立 worker |
| 模型接入 | 百炼 `qwen3.7-flash` + `text-embedding-v4` / 1024 维，共用一把百炼 API Key；小型 adapter | 聊天与向量化分开配置，统一处理工具、流、usage、超时与预算；当前不引入多厂商网关 |
| 观测/评测 | OpenTelemetry + Langfuse + pytest/规则 grader + Ragas | 分布式链路、LLM 成本/质量；业务事件仍保存在自己的数据库 |
| 前端 | Vue 3 + TypeScript + Pinia + Naive UI | 登录注册、产品首页、课程搜索/筛选/详情与 Agent 咨询预填，工作台展示运行、知识、审批和试听业务 |
| 工程 | Docker Compose、CI、Testcontainers、Vitest、Playwright、k6 | 可复现环境、跨栈验证和压测 |
| 后期部署 | Kubernetes/Helm | 在已具备无状态 API、持久化 worker、探针与资源预算后建设 |

**为什么允许 MySQL + PostgreSQL 两个数据库？** MySQL 保留现有业务与事务资产，PostgreSQL 服务 LangGraph checkpoint、知识与向量查询，两者没有跨库外键和共享写表。代价是两套备份、连接池、迁移、监控与一致性治理。如果实做发现运维收益不成立，可以通过 ADR 将业务也迁往 PostgreSQL；这是替代路线，不要求同时实现两套业务存储。

当前 Compose 已配置上述核心组件，并在 fixture 模型下完成真实 Redis/RocketMQ 与跨服务业务烟测；前端镜像已构建、部署并通过登录到工作台的浏览器检查。真实外部模型及剩余浏览器角色路径仍需验收。详细组件类型和职责见[中间件清单](中间件与基础设施清单.md)。

### 4.4 版本升级策略

截至调研日，[Spring AI 官方项目页](https://spring.io/projects/spring-ai/) 显示 2.0.1；[官方入门文档](https://docs.spring.io/spring-ai/reference/getting-started.html) 明确 2.0.x 对应 Boot 4.0.x/4.1.x。本项目已成套升级到 Boot 4.1.1、Spring AI 2.0.1、MyBatis-Plus Boot 4 starter 3.5.17，JDK 保持 21。

迁移已适配 Jackson 3、MyBatis 服务包名、RedisClient、模型扁平配置和包含 `/v1` 的百炼兼容地址，Flyway V5 定义试听业务表，V6 移除自增与物理外键，并为原复合键、聊天记录补雪花代理主键。Boot 4 与核心业务的验证见 [Spring Boot 4 迁移与验证记录](SpringBoot4迁移与验证记录.md)；RocketMQ 和试听业务的验证边界见[专项验证记录](../modules/免费试听秒杀验证记录.md)。

Python 依赖用 uv.lock，前端采用唯一包管理器与 lockfile，容器用具体标签/镜像摘要；LangGraph 与 checkpoint 插件分开记录版本。[LangGraph releases](https://github.com/langchain-ai/langgraph/releases) 是核对入口。文档不把 main 分支当稳定发布版，也不以 star 数排名代替工程选型。

## 5. 产品主线与范围

### 5.1 一个能贯穿所有亮点的业务任务

示例用户输入：

> 我在上海，有 Java 基础，只能周末学习，预算不超过 1.5 万。请结合课程介绍和最新预约规则，比较两条学习方案；如果合适，帮我准备下周的咨询预约，我确认后再提交。

系统应当：

1. 从会话与用户允许保存的偏好中提取约束，缺城市/时间等必要条件时追问。
2. 查询实时课程、校区和可预约时段；查询价格/名额这类动态字段时以业务 API 为准。
3. 从授权知识库检索课程说明、预约/退款规则，识别旧版本与冲突材料。
4. 给出带页码和版本的方案比较，展示关键事实依据及无法确认的条件。
5. 生成包含课程、校区、时间、联系人和参数版本的预约草稿。
6. 用户确认后，Java 执行业务校验并通过幂等命令提交。
7. 即使提交响应丢失或 Agent 进程重启，也先查询业务结果再继续，最终展示预约编号。
8. 保存执行轨迹、延迟、Token、成本估算与评测结果。

这个业务闭环同时覆盖“检索推理的灵活性”和“业务执行的确定性”。课程、时段、优惠与规则全部来自自建测试数据或明确授权资料；不能把虚构业务规模写成真实商业落地。

### 5.2 功能分层

| 层级 | 纳入范围 |
|---|---|
| 首个闭环 | 登录、单空间知识库、引用问答、查询工具、预约草稿、确认执行、完整历史、运行详情 |
| 工程完整版 | 多空间权限、文档版本、任务恢复、幂等、取消、评测、可观测、资源预算、稳定部署 |
| 深度加分 | 有对照实验的多 Agent、受控 Skills、MCP 接入、模型路由、可视化运行图、故障演练 |
| 根据证据再决定 | GraphRAG、A2A、代码沙箱、本地模型推理、模型微调、完整拖拽编排器 |

普通聊天降为基础模式。PDF 从“一个聊天绑定一份文件”升级为独立知识资源。生活模拟游戏可以保留在实验入口，但不作为主简历亮点。当前并未接通的多模态附件先与后端能力对齐，后续优先做有业务价值的扫描件/表格理解。

## 6. 总体架构与责任边界

~~~mermaid
flowchart TB
    UI["Vue：课程广场、任务、知识、审批、评测"] --> API["Java API / BFF"]
    API --> AUTH["身份、空间权限、配额"]
    API --> BIZ["课程、校区、预约、试听活动与审批"]
    API --> MYSQL[("MySQL：业务真相、提交记录、Outbox")]
    MYSQL --> RELAY["Outbox Relay"]
    RELAY --> MQ["RocketMQ：Agent 普通命令"]
    MQ --> BRIDGE["Java 消费者：内部 HTTP 幂等桥接"]
    BRIDGE --> PG[("PostgreSQL：Run、Event、Checkpoint、Chunk、Eval")]
    API --> TXMQ["RocketMQ：试听事务消息"]
    TXMQ --> ORDER["Java 消费者：条件扣库存与0元订单"]
    ORDER --> MYSQL
    PG --> WORKER["Agent Worker：租约、状态图、预算"]
    WORKER --> RETRIEVE["检索与上下文模块"]
    RETRIEVE --> PG
    RETRIEVE -. "达到扩展门槛后" .-> ES["Elasticsearch：BM25"]
    WORKER --> MODEL["百炼适配器：qwen3.7-flash"]
    WORKER --> TOOL["HTTP / MCP 工具适配层"]
    TOOL --> BIZ
    API --> STREAM["Python Event API：快照与事件重放"]
    STREAM --> PG
    INGEST["Ingestion Worker：解析、切块、Embedding"] --> PG
    INGEST --> EMBED["百炼：text-embedding-v4 / 1024 维"]
    RETRIEVE --> EMBED
    INGEST --> OSS["MinIO：原文与产物"]
    API --> OSS
    API --> REDIS["Redis：登录保护、Lua预占、课程共享缓存"]
    BIZ --> CATALOG["CourseCatalogService：课程页面、Agent、试听目录共用"]
    CATALOG --> L1["Caffeine：本地缓存与同 key 合并加载"]
    L1 --> REDIS
    CATALOG --> LOCK["Redisson：跨 JVM 回源锁"]
    LOCK --> MYSQL
    WORKER --> REDIS
    API -. "traceparent" .-> OBS["OTel / Langfuse / 指标与日志"]
    WORKER -. "traceparent" .-> OBS
~~~

当前部署单元包含 Java API、Python Runtime/worker、Vue/Nginx 以及核心中间件。Python API、Agent worker 与入库作业由同一代码库承载，不把每个图节点拆成微服务。

### 6.1 数据与状态的唯一所有者

| 数据/动作 | 唯一写入方 | 另一方如何使用 |
|---|---|---|
| 用户、空间成员、资源权限 | Java/MySQL | Python 获得服务端签发的有限作用域上下文，并在敏感节点复核 |
| 课程、校区、预约、审批、工具业务执行记录 | Java/MySQL | Python 只能通过工具接口操作，不能直连业务表写入 |
| 用户提交请求、投递 outbox | Java/MySQL | Python 按 requestId/runId 幂等接收 |
| Agent run、完整消息、事件、checkpoint | Python/PostgreSQL | Java 经授权后代理查询/流式访问；MySQL 若保存状态只作展示投影 |
| 文档解析版本、chunk、向量、评测运行 | Python/PostgreSQL | Java 查询结果并应用资源权限；原文件入口由 Java 授权 |
| 模型上下文窗口/摘要 | Python | 从完整消息和允许的知识重新构建，不替代完整历史 |

客户端不能自行指定 tenantId、userId、tool scope 或知识过滤表达式。浏览器只访问 Java 对外域名；内部 HTTP/MCP 使用短期服务凭据、目标 audience 和请求作用域。**MCP session ID、conversationId、runId 都不是访问权限。**

### 6.2 一次请求如何安全跨服务

1. Java 校验当前用户、会话归属、资源集合、配额；计算规范化 requestHash。
2. 在同一 MySQL 事务内按 UNIQUE(workspaceId, actorId, conversationId, clientRequestId) 查重、生成 runId、写 task_request 和 outbox。相同键/相同 payload 返回原 runId，相同键/不同 payload 返回 409；当前提交接口返回 200 和 QUEUED，不代表已开始模型调用。
3. relay 在发布确认后标记投递；失败重试。消息可能重复，不能宣称端到端 exactly-once。
4. Java RocketMQ 消费者经内部 HTTP 将命令交给 Python；Python 按 run 主键和 request_hash 幂等持久化，返回 2xx 后 Java 确认消息；不另设 Python MQ consumer 或 inbox 表。
5. worker 通过持久化作业领取与租约开始执行。人在审批页面停留时，不长期占据 MQ 未确认消息或数据库连接。
6. 运行事件写 PostgreSQL 后对外发布；Java 校验读取权限后代理 SSE。
7. Agent 调用写工具时，Java 独立检查审批、参数版本、资源权限与业务不变量，事务提交后返回稳定结果。

Java 已提交但 Python 尚未接收时，GET /runs/{id} 根据 task_request 返回 `status:QUEUED`、`execution:null` 及 `submission` 投递信息；事件入口等待或提示重试，不当作永久 404。Python 建立 run 后以运行时为 execution 状态权威。投递失败、运行失败和不存在要区分；task_request 不保存可与 PG 竞争写入的运行终态。

RocketMQ 发布成功与消费者确认负责不同阶段，失败时仍可能重复投递；业务结果靠 Run 幂等接收、请求主键与 MySQL 唯一约束收敛。[RocketMQ 事务消息](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)、[消息重试](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/)

### 6.3 双库一致性采用的边界

不引入跨 MySQL/PostgreSQL 的分布式大事务。业务结果以 MySQL 为准，图状态以 checkpoint 为恢复依据，运行事件/历史是可校正的记录。

当“业务提交成功，但 checkpoint 尚未写入”时，重启后按相同 actionId 向 Java 查回原结果，再补写节点状态。checkpoint 本身不保证外部副作用只发生一次。重复执行通过业务唯一约束与幂等命令消除；永久失败进入显式 FAILED 或人工处理状态，不能用无限重试掩盖。

最后节点 checkpoint 与 run 终态事件也可能分开提交。reconciler 按当前 acceptedCheckpoint 和稳定终态事件键补齐 message/run 结果，避免图已经结束但页面永远显示 RUNNING。不能依赖通知恰好送达才完成状态。

~~~mermaid
sequenceDiagram
    participant A as Agent Worker
    participant J as Java Tool API
    participant M as MySQL
    participant P as PostgreSQL Checkpoint
    A->>J: execute(actionId, approvalId, argsHash)
    J->>M: 事务校验并创建预约和执行结果
    M-->>J: commit
    J--xA: 响应丢失
    Note over A: 进程重启，从 checkpoint 恢复
    A->>J: 相同 actionId 查询/重试
    J->>M: 查已有执行结果
    M-->>J: 原 reservationId
    J-->>A: 返回同一结果
    A->>P: 持久化节点结果
~~~

## 7. Agent Runtime：本项目最值得深入的模块

### 7.1 状态图采用“确定业务流程 + 有界自主决策”

不让模型决定授权、数据库事务和终态落库。模型负责意图、缺失信息识别、检索问题生成、只读工具选择与方案组织；代码负责身份、预算、审批、状态转换及真实执行。

~~~mermaid
flowchart LR
    START["接收与授权"] --> CONTEXT["构建上下文"]
    CONTEXT --> ROUTE{"任务路由"}
    ROUTE --> QA["知识问答"]
    ROUTE --> PLAN["方案规划"]
    ROUTE --> CLARIFY["等待补充信息"]
    PLAN --> READ["检索与只读工具"]
    READ --> CHECK{"证据与约束检查"}
    CHECK -->|不足且未超预算| READ
    CHECK -->|条件完整| DRAFT["持久化操作草稿"]
    DRAFT --> APPROVAL["等待用户确认"]
    APPROVAL -->|批准且仍有效| EXECUTE["Java 幂等执行"]
    APPROVAL -->|拒绝或过期| STOP["记录结果"]
    EXECUTE --> VERIFY["查询业务事实验证"]
    VERIFY --> ANSWER["结果与引用"]
    QA --> ANSWER
    CLARIFY -->|恢复输入| CONTEXT
    ANSWER --> STOP
~~~

只读探索允许 ReAct 式循环；预约提交使用固定的 draft → approval → execute → verify 流程。这个划分与 [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents) 对 workflow/agent 的区别相符，但具体状态机是本项目的设计选择。

### 7.2 运行状态与图状态分别存储

运行状态：QUEUED → RUNNING → WAITING_INPUT / WAITING_APPROVAL → RUNNING → SUCCEEDED / FAILED / CANCELLED / TIMED_OUT。

CANCEL_REQUESTED 是取消中的中间状态；RECOVERING 可以作为 attempt 的状态。图节点局部失败和工具结果不确定采用节点级 RETRYABLE / OUTCOME_UNKNOWN，不要把所有异常立即映射成整个 run 失败。

State 至少包含：

~~~text
run_id / conversation_id / request_id
actor_context_ref / authorized_resource_set / acl_version
graph_version / prompt_version / tool_registry_version / model_config_version
messages_ref / summary_ref / facts_with_provenance
task_constraints / plan_revision / steps / next_node
evidence_refs / tool_results / pending_action_ids / approval_ids
budget: deadline, token_limit, tool_call_limit, retry_limit, spent
checkpoint_ref / attempt / lease_epoch / error_class
~~~

其中 user/tenant/permission 来自服务端；模型只能产生允许修改的任务字段。大型文档、PDF 二进制、长工具返回值存为 artifact，状态中留引用，避免 checkpoint 膨胀。

### 7.3 第一版就实现的约束

- 每个运行限制最大步骤、最大工具调用数、总超时、Token 和费用预算。初值可以设为 12 个决策步、8 次工具调用、单节点最多 2 次重试，**这些是调参起点**。
- 同一 conversation 的新 run 默认串行；已有活动 run 时返回 409 或进入显式等待队列。WAITING_INPUT/WAITING_APPROVAL 的补充信息走当前 run 的 inputs 接口，不另外启动并行图。
- 同一只读工具允许去重/短缓存；相同工具参数连续失败时改为追问或终止，不无限反思。
- Schema 不合法、权限不足、业务冲突、模型限流、网络超时分别建模；只有可重试错误进入退避。
- 429/5xx 等重试带指数退避、随机抖动与总时间上限；人工审批不占用模型线程、事务或队列消费名额。
- 保存简短的计划说明、动作和依据，不依赖模型输出隐藏思维链，也不把 think 标签视作执行审计。

### 7.4 恢复、多 worker 与 fencing

先证明单 worker 跨进程重启可恢复，再增加多 worker 接管。运行表增加 owner_id、lease_until、epoch、attempt、last_accepted_checkpoint，领取与续租使用数据库时间和条件更新。

多 worker 版本必须同时完成：

1. **运行状态隔离**：事件和状态写入在 PostgreSQL 事务中验证 owner+epoch；旧 worker 返回的迟到结果不能覆盖新状态。
2. **checkpoint 隔离**：开发并验证 lease-aware saver 适配层，在同一 PostgreSQL 事务中锁定 run 租约行（FOR UPDATE 或等价 CAS）、校验 owner/epoch 和未过期，再写 checkpoint/pending writes。普通 SELECT 后再写仍可能有竞态；只在调用 PostgresSaver 前检查一次也不够。先做锁定版本的扩展点 POC，不满足要求时保持单 worker，不宣称已解决自动接管。
3. **业务动作隔离**：Java 维护 run_execution_authority 的 accepted_epoch。新 worker 获得运行时签发的租约证明后，先完成 Java 的单调 epoch 切换确认，再允许写工具；工具事务锁定 authority 行并拒绝旧 epoch。已在切换前合法提交的动作按 actionId 对账，不“自动回滚”。

租约失效不能撤销已发送的模型请求，也不能保证不产生额外 Token 费用。真正的验收是：过期 worker 不能覆盖有效 checkpoint、不能在切换后提交新的旧 epoch 业务动作。

[LangGraph 官方持久化](https://docs.langchain.com/oss/python/langgraph/persistence) 和 [PostgresSaver 源码](https://github.com/langchain-ai/langgraph/blob/main/libs/checkpoint-postgres/langgraph/checkpoint/postgres/aio.py) 提供恢复基础；应用的调度租约与外部业务事务仍需要自己设计。

### 7.5 取消与部署版本

取消接口先在 Java 事务中更新 run_execution_authority 的 cancelVersion 并写 outbox，再可靠通知 Python；新的写工具必须检查该权威记录。worker 在节点边界和外部调用前检查取消，尽力取消进行中的模型 HTTP 请求。Java 取消与写操作争用同一 authority 行，谁先提交决定业务动作是否已发生。写操作已经提交时，UI 显示“运行已停止，预约已创建”，后续撤销需要单独业务操作。

每个 run 固定 graph/prompt/tool schema 版本。新版发布不把旧 checkpoint 强行装进不兼容的图；先支持旧 worker 排空，必要时写显式 state migrator。回放轨迹不重新执行写工具；重新实验必须 fork 新 run，并默认使用工具模拟器。

## 8. Tool Gateway、审批与 MCP

### 8.1 将 CourseTools 迁成业务命令边界

当前图使用的工具与 Java 内部受控执行边界：

| 工具 | 类型 | 约束 |
|---|---|---|
| search_courses | 只读 | 与课程页共用 Java 服务；类型/学历/关键词/排序白名单，最多 50 条，id 为字符串、price 为元、duration 为天 |
| list_campuses | 只读 | 共用全局校区目录，Agent 最多返回 100 条；不表示课程与校区开设关系 |
| search_knowledge | 只读 | 服务端授权资源集合，返回 evidenceId 和来源 |
| ask_user | 交互 | 缺少条件时让运行进入 WAITING_INPUT |
| draft_reservation | 草稿 | 稳定 actionId 和真实课程/校区，生成固定审批参数 |
| query_trial_campaigns | 只读 | 查询当前身份可见活动 |
| draft_trial_claim | 草稿 | 固定活动与当前身份，生成 claim_trial 审批，不预占库存 |
| query_trial_claim | 只读 | 按原动作回查参与请求及订单终态 |

模型不会直接选择执行普通预约或试听订单的写接口；用户批准后由运行时经可信 Java 内部 API 按固定参数提交。普通预约成功看 reservationId，试听成功看 `SUCCEEDED + orderId`。

参数用 JSON Schema/Pydantic DTO；Java 再做 Bean Validation 与领域校验。课程、校区和时间使用 ID，展示名由后端查回。现有课程 `price` 为整数人民币元、`duration` 为天，试听订单 `amountCent` 为分；按各自接口单位展示，不让模型自由生成或换算价格。

### 8.2 审批与幂等的具体实现

approval 保存 actor/workspace、runId、actionId、toolName、固定参数、有效期、状态版本和决定。普通预约与 `claim_trial` 使用不同工具类型；运行时只能提交已批准草稿中的参数。

同意接口使用 expectedVersion 做 CAS。重复点击返回已记录的决定；拒绝后不能用同一 approvalId 再变成批准。

Java 持久化审批决定；Python 在恢复时按可信 actor/workspace 查询审批，按 run/action 标识防止重复执行。对命令交接使用 RocketMQ 的 Java 消费者与 Python Run 幂等接收；审批等待期间不占用未确认消息。按 actionId/approvalId 对账，覆盖通知延迟与丢失。

补充信息接口 `POST /runs/{id}/inputs` 接收 workspaceId、input、clientRequestId；Python 校验 WAITING_INPUT 并幂等恢复。WAITING_APPROVAL 不接受普通补充输入修改草稿；要改办理条件，应拒绝原草稿并发起新运行。

执行命令的 MySQL 事务：

~~~text
先校验可信身份、资源归属及该结果的读取权限
锁定运行与对应审批，复核状态、版本、有效期及取消标记
按 actionId 查已提交动作；重复执行返回原业务回执
普通预约：写入预约意向并返回 reservationId
试听参与：持久化请求并返回 requestId；RocketMQ 事务消息预占后异步落单
提交事务
~~~

actionId 在草稿阶段稳定生成，草稿接口自身按 actionId 幂等；恢复时不能用新的模型 tool_call_id 代替。已执行结果回查仍校验读取权限；新业务写入再校验审批和取消条件。

试听名额已使用 Redis Lua 预占和 MySQL 条件库存更新保障不超额，并以数据库唯一约束兜底；普通预约仍无排课时段与库存。外部调用不放在长数据库事务里。

[LangGraph interrupt 文档](https://docs.langchain.com/oss/python/langgraph/interrupts) 明确恢复时会重新执行所在节点开头的逻辑，因此把草稿持久化、等待审批和真正执行业务拆成节点；不能把“先写库再 interrupt”当确认机制。

### 8.3 MCP 做工具协议，不能替代权限层

先把 Java Tool API 做清楚，再增加 MCP adapter。Python 作为 MCP client；课程查询可封装为 MCP server 工具，写工具仍进入同一 Java 审批/命令服务。

- 远程连接优先采用双方 SDK 支持的 Streamable HTTP，固定并协商协议版本；本地开发可以用 stdio。
- 支持工具清单、schema/version、调用错误和能力白名单。
- MCP endpoint 使用可信服务身份、作用域和目标校验；第三方返回内容视作数据。
- 用户不能在聊天里提供任意 MCP URL 就获得网络访问；工具注册由后台受控配置。
- 端到端测试包含“能发现工具但没有权限调用”“参数篡改”“重试同一动作”“服务短暂离线”。

截至查阅的 [MCP 2025-11-25 transports 规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)，stdio 与 Streamable HTTP 是标准传输，后者取代旧 HTTP+SSE 传输。不要把产品前端 SSE 事件协议和 MCP 传输协议混为一谈。身份和网络边界参考 [MCP 官方安全实践](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices)。

### 8.4 Skills 与 A2A 的放置位置

Skills 用于版本化的任务说明与资源，例如“选课比较 SOP”“预约资料核验 SOP”。第一版仅允许可信、仓库内审核过的 Skill 包，保存 name/description/version/allowedTools/outputSchema；按任务加载必要部分。Skill 不是授予工具权限的渠道，也不是任意执行脚本的授权。[Agent Skills 规范](https://agentskills.io/specification)

A2A 只在出现独立部署、独立身份与生命周期的外部 Agent 时采用；两个内部 LangGraph 节点不需要 A2A。可以后期让“课程服务 Agent”与独立“政策核验 Agent”做协议实验，但必须说明独立边界的价值。[A2A 官方项目](https://github.com/a2aproject/A2A)

## 9. RAG：从 ChatPDF 做成能解释检索质量的系统

### 9.1 文档资源和版本

独立建立 knowledge_base、document、document_version、ingestion_job、chunk、index_revision。conversation 只引用知识库/文档集合，不再承担文档的唯一生命周期。

入库状态：

~~~text
UPLOADED → VALIDATING → PARSING → CHUNKING → EMBEDDING → INDEXING → READY
                                           ↘ FAILED（errorCode / attempt / nextRetryAt）
~~~

新版本必须完整解析、嵌入、索引并验收后才发布 activeVersion；失败时旧版本继续工作。chunkId 由 documentVersion + ordinal 等稳定信息生成，避免重试生成一批随机 ID。

去重键至少包含租户/知识库边界、文件内容 hash、parser/chunker/embedding 版本。相同文件在不同权限空间不能因全局缓存而泄露存在性或返回未授权片段。

删除先撤销检索可见性，再异步清理向量、chunk、对象与缓存；失败进入可重试清理任务。删除聊天默认保留独立知识库文档，UI 应明确这个产品语义。

### 9.2 解析与切块

- 保留现有按页、120/800/100 字符切块策略为 B0，不把“字符数”描述为 Token。
- 新版使用 Docling 输出页、标题、表格、阅读顺序等结构，扫描件才启动 OCR；保存 parserVersion 与解析耗时。
- 子块用于召回，父段落用于补上下文；表格保留表头和单元格关系，避免只按字符切断。
- chunk metadata 包含 tenantId、knowledgeBaseId、documentVersionId、pageStart/pageEnd、sectionPath、parserVersion、embeddingModel、indexRevision、contentHash。
- OCR/大文件解析放在独立进程池/worker，不能用 FastAPI async 函数直接占住事件循环。

[Docling 官方仓库](https://github.com/docling-project/docling) 提供布局、表格与 OCR 处理基础；它不保证你的中文材料自动达到高质量，必须抽查扫描件、双栏和表格样本。

### 9.3 检索链路与中文问题

推荐逐级实验：

| 版本 | 链路 | 要回答的实验问题 |
|---|---|---|
| B0 | 当前 Redis dense，topK=2，threshold=0.5 | 原始项目在固定语料上的真实基线是多少？ |
| B1 | 修复权限/版本后，仅 dense，调 chunk 与 topK | 收益是否主要来自数据质量和召回数量？ |
| B2 | dense + 词法召回，RRF 融合 | 课程名、编号、日期和政策术语是否更容易找回？ |
| B3 | B2 + reranker | 相近但无关的片段是否减少？增加多少延迟/成本？ |
| B4 | B3 + 有界 query rewrite / 二次检索 | 多轮代词和缺证据问题是否改善？是否引入错误扩写？ |

POC 可用 PostgreSQL 全文检索配中文预分词作为词法基线，但 **PostgreSQL ts_rank 不是 BM25**，默认分词也不能直接当作成熟中文方案。完整版如果课程编号/术语检索仍是主要失败源，再引入 Elasticsearch BM25 与中文分词；用同一数据集比较后决定保留。

RRF 按排名融合，不直接相加 dense cosine、BM25 和 reranker 的异构分数。候选起点可设两路各 20、融合后 30、rerank 后 5；所有参数在开发集调整，最终测试集冻结。[Elastic RRF 文档](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion)、[RAGFlow 检索源码](https://github.com/infiniflow/ragflow/blob/main/rag/nlp/search.py)

权限过滤必须在任何内容进入模型前生效。除了索引中的 tenant/version 元数据，还要批量复核候选 document ACL；权限版本不一致时拒绝或重查。不能先生成答案再删掉无权文档的引用。

恢复 checkpoint、历史工具结果或摘要时也复核来源 ACL。摘要和 memory fact 保留来源 ID；发现撤权内容时剔除并重建上下文，无法区分来源的摘要整体重建。已经按当时权限发送给模型的内容无法“撤回”，因此将撤权后的新调用与历史审计访问分别控制。

pgvector 的 ANN 查询叠加过滤可能不足 topK；需要检查执行计划、过滤选择性、ef_search 和 iterative scan。该问题与权限泄露不同，是召回完整性问题。[pgvector 官方说明](https://github.com/pgvector/pgvector)

### 9.4 引用、拒答和证据冲突

生成输入只包含经过授权、带 evidenceId 的候选；模型输出结构化 citations，服务器验证每个引用确实属于本次检索上下文，不能让模型自行拼 OSS URL。

引用至少给 document/version/chunk/page；首版支持可靠页码跳转，后续再加 bbox 高亮。对于价格、名额和预约状态，引用业务工具结果及查询时间；对于静态规则，引用文档版本和生效时间。

没有证据时说明缺失并追问；新旧规则冲突时展示版本差异并按明确的发布/生效规则处理，而不是“选相似度最高的”。引用存在并不代表结论被支持，还需要评测 citation support。

### 9.5 Embedding、reranker 与模型变化

Embedding 明确保留百炼 `text-embedding-v4` / 1024 维，文档入库与查询使用同一模型、维度与预处理版本，并与日常 Agent 共用一把百炼 API Key。B3 重排实验采用本地 BGE reranker，不新增硅基流动等厂商凭据；资源和效果未验证前默认关闭重排，使用 B2 融合结果。实验仍记录质量、耗时和本地资源成本。[百炼向量化文档](https://help.aliyun.com/zh/model-studio/embedding)、[FlagEmbedding 官方仓库](https://github.com/FlagOpen/FlagEmbedding)

切换 embedding 模型/维度必须新建 indexRevision，后台重建并切换；即使维度相同也不能默认不同模型的向量可混用。测试样本、query embedding、chunk embedding 和索引版本必须对应。

## 10. 上下文、记忆与多 Agent

### 10.1 三种“记忆”分开

| 类型 | 内容 | 管理规则 |
|---|---|---|
| 完整消息与事件 | 用户/助手消息、工具事实、时间与关联 run | 独立持久化、分页、可删除、具有保留策略 |
| 工作上下文 | 当前约束、最近对话、摘要、必要证据与工具结果 | 每次调用按预算构建，可从来源重建 |
| 长期偏好 | 用户允许保存的城市、时间偏好等 | 有来源、版本、TTL、编辑/删除入口，敏感联系信息最小化保存 |

摘要可能出错，不能替代原始事实。关键约束如城市/预算/时间保存为类型化字段，并可回溯到用户消息；模型推断的偏好与用户明确要求区分标记。

上下文预算按系统规则、当前任务、关键工具结果、检索证据、历史摘要分配。先裁剪重复或低价值结果，保留身份与执行约束。短期对话中用户改变条件时更新事实版本，旧审批立即失效。

测试包含 30/60 轮对话、同名实体、多次修改条件、跨会话偏好、删除记忆、摘要漂移和租户隔离。

### 10.2 多 Agent 只做一个有对照价值的场景

完成单 Agent 后，增加两个只读专家：

- **课程匹配专家**：查询课程与时段，输出约束满足情况与结构化候选。
- **规则核验专家**：检索规则，输出依据、冲突与缺失项。

协调器并行收集两者的结构化结果，做去重/冲突检查，统一创建预约草稿。只有协调器可进入受控写操作链路；子 Agent 没有写预约权限。

要控制共享记忆污染、重复检索、超时分支、结果合并和总预算。专家调用数量最多 2 个并行，逐步测量 fan-out 开销。

同一测试集比较“单 Agent + 相同工具”与“双专家”：

~~~text
任务成功率 / 引用支持率 / 约束满足率
模型调用次数 / 平均 Token / 单成功任务成本
p50 与 p95 完成时间 / 重试比例 / 失败分布
~~~

如果质量收益不足以补偿成本和延迟，默认保留单 Agent；把负结果写成 ADR，也是一项有效工程成果。不是 Agent 数量越多，项目就越高级。

## 11. 模型网关、预算与可观测性

### 11.1 模型接入与故障降级

建立 capability matrix，记录模型是否支持 tool calling、JSON schema、stream、usage、最大上下文、取消和 provider-specific 参数。仓库里的模型字符串不能代替实际 smoke test。

默认 provider 固定为阿里云百炼，日常对话、任务规划、工具调用、摘要及子 Agent 均使用 `qwen3.7-flash`；Embedding 固定为 `text-embedding-v4` / 1024 维。两者共用一把百炼 API Key，但分别设置并发、Token 和费用预算。先完成这一固定模型基线；后续有评测证据时再开展百炼内的多模型路由实验，仍复用同一凭据，不要求接入其他厂商。每次 run 记录实际模型、参数、provider、prompt 和适配器版本。

当前单厂商方案不配置跨厂商故障切换。对限流、暂时性服务错误实施受总时限与次数约束的退避重试，并遵守 Retry-After；鉴权或参数错误直接返回可定位的配置错误。达到预算或熔断条件后，持久化失败状态，提供明确错误与重试入口，异步任务按作业策略恢复。同厂商模型也可能共享故障，未来的模型路由不能当作厂商级容灾。部分内容已经发出后，不把重试结果直接拼接进旧回答；使用受控错误或新 message revision。外部工具成功后先对账，不能因模型重试再次执行写操作。

统一预算账本计入重试、子 Agent、评测 judge 和 reranker。设置用户、空间、模型、整个运行四层并发/额度；费用估算基于调用时价格版本和实际 usage，账单缺失时显示 unknown，不能当 0。

当前使用小型百炼适配器即可，不部署 LiteLLM Proxy。它保留为未来需求变化后的技术参考，不是本次完整工程的前置依赖；业务幂等与审批仍由本项目负责。[LiteLLM 官方仓库](https://github.com/BerriAI/litellm)。目标环境变量、现有 `API-KEY` 的迁移与联调验收见[模型服务与 API 配置](./模型服务与API配置.md)。

### 11.2 可观测对象

每次 trace 关联 requestId、runId、conversationId、actionId，跨 Java→MQ→Python→Java 工具传播 traceparent。异步重试建立新 attempt span 并关联原任务。

| 维度 | 记录项 |
|---|---|
| API | 延迟、错误、并发、限流、请求体大小 |
| 调度 | 排队时间、worker 租约、重试、待审批时长、积压 |
| 模型 | 首 token 时间、调用耗时、输入/输出 Token、provider 错误 |
| 检索 | 授权候选数、各路召回、rerank 耗时、索引版本 |
| 工具 | 工具名、参数 hash、耗时、结果码、幂等命中、审批拒绝 |
| 业务 | 成功预约数、重复写入数、超额拒绝、结果不确定待对账数 |
| 质量 | 任务成功率、引用支持、越权测试结果、失败分类 |

Langfuse 用于 LLM trace、实验与评测联动；指标用 Prometheus/Grafana 等展示。OTel GenAI 语义约定正持续演进，锁定使用版本并与自有字段映射，不依赖一个未知稳定性的字段名。[GenAI 约定官方仓库](https://github.com/open-telemetry/semantic-conventions-genai)、[Langfuse](https://github.com/langfuse/langfuse)

不默认把原始文档、联系人和整段 prompt 打到公共日志。日志以 ID/hash/摘要为主，调试原文设置权限和保留期。Langfuse 故障不应阻塞业务写操作，但本地业务审计记录必须先保存。

## 12. 前端改造与 API 契约

### 12.1 导航与页面

当前网站的实际入口是 `/login`、`/register`、登录后的 `/` 产品首页、`/courses` 课程广场、`/courses/:id` 详情以及 `/agent` 工作台。课程页支持搜索、筛选、排序、分页，详情可跳转工作台预填咨询；预填不创建会话或任务，用户点击发送后才提交。工作台承载空间、会话、知识库、试听活动、审批和评测。暗色主题、历史侧栏、Markdown 消息和 PDF 预览在当前界面中保留。下表是页面功能与后续深入方向：

| 页面 | 核心交互 |
|---|---|
| 课程广场与详情 | 搜索、方向/学历筛选、价格/周期排序、分页、课程详情与咨询预填 |
| 工作台 | 提问、任务约束、答案、引用、预约草稿和免费试听 |
| 运行详情 | 节点时间线、工具结果、重试、预算、恢复、取消 |
| 知识库 | 文档列表、版本、解析任务、可用状态、引用预览 |
| 待确认操作 | 可核对的参数卡、批准/拒绝/过期/冲突 |
| 评测中心 | 数据集版本、方案对比、失败样本与 trace |
| 设置 | 空间权限、受控工具、模型配置，不暴露密钥 |

时间线显示可观察的动作：查了什么、用了哪些证据、写了什么、失败在哪一步。用“正在核验可预约时段”这样的状态，不伪造模型内部思维过程。

### 12.2 对外契约草案

| 方法与资源 | 请求/响应要点 |
|---|---|
| GET /api/v1/courses | 已实现：登录后查询，返回 items/total/page/pageSize，默认每页 12 条、最多 50 条 |
| GET /api/v1/courses/{id} | 已实现：字符串 ID、价格元、周期天；不存在返回 404 |
| GET /api/v1/campuses | 已实现：登录后读取全局校区，最多 200 条 |
| POST /api/v1/conversations | 服务端分配 ID、归属与 createdAt |
| POST /api/v1/conversations/{id}/runs | message、attachmentIds、knowledgeSelection、clientRequestId；202 返回 runId/eventsUrl |
| POST /api/v1/runs/{id}/inputs | 等待态补充输入；interruptId、expectedVersion、clientRequestId、message；持久化后恢复原 run |
| GET /api/v1/runs/{id} | 当前状态、snapshotSeq、消息/产物、usage、error、恢复信息 |
| GET /api/v1/runs/{id}/events | 标准 SSE、Last-Event-ID/afterSeq、心跳、重放与保留期 |
| POST /api/v1/runs/{id}/cancel | expectedVersion；返回取消请求状态 |
| POST /api/v1/approvals/{id}/decisions | approve/reject、expectedVersion；Java 校验操作者和参数版本 |
| POST /api/v1/knowledge-bases/{id}/documents | 接受文件或预签名上传完成信息；返回 documentVersionId/jobId |
| GET /api/v1/ingestion-jobs/{id} | 阶段、进度、可重试错误与 attempt |
| GET /api/v1/documents/{id}/versions/{v}/content | 鉴权后流式下载或短期受控 URL |
| POST /api/v1/evaluations | 受控启动数据集/版本组合的评测；返回 evalRunId |

错误统一为 code/message/requestId/retryable/details；使用明确的 HTTP 状态码。不要再用 HTTP 200 加 Result.fail 表示所有失败。浏览器路径统一同源 /api；生产 HTTPS，若采用 HttpOnly cookie session，则同时配置 CSRF 与 cookie 策略。

### 12.3 SSE 事件格式与重放

~~~text
id: run_01:42
event: tool.completed
data: {"schemaVersion":"1","runId":"run_01","conversationId":"conv_01","seq":42,"timestamp":"2026-09-17T08:00:00Z","payload":{"actionId":"act_03","tool":"search_courses","status":"SUCCEEDED","resultRef":"artifact_09"}}

~~~

事件类型至少包括 run.accepted、run.started、node.started、node.completed、tool.proposed、tool.completed、citation.added、approval.required、message.delta、message.completed、run.failed、run.completed、run.cancelled。

实现要求：

- message.delta 携带 messageId，前端 reducer 按 runId/messageId 更新，不更新“当前数组最后一条”。
- 序号在单 run 内单调，消息重复投递按 eventId 去重；跨 run 不要求全局顺序。
- 关键事件先持久化再发送；Token 小批量合并写入后发出，避免每 Token 一个事务。最终完整 message 作为可校正快照。
- 快照内容和 snapshotSeq 来自同一已提交版本；单 run 的 seq 按安全提交顺序分配，不能让较小序号的晚提交事件落在已推进的游标之后。获取快照后补 seq > snapshotSeq 的事件。
- 历史重放转实时尾随始终使用数据库游标补拉，通知只作唤醒；不能依赖“查完历史再订阅 Pub/Sub”而留下窗口。事件保留边界必须被可恢复快照覆盖，关键审计采用独立保留策略。
- 游标早于事件保留期时返回明确的重置协议，客户端重新取快照；不能默默从当前位置继续。
- 心跳保持代理链路，关闭反向代理缓冲，配置合理 idle timeout；慢客户端释放订阅并可恢复，不拖住 Agent。
- 使用支持鉴权的 fetch 流客户端和合规 parser，处理多行 data、空行、半包、UTF-8 跨块与最终 flush。
- AbortController 仅中断浏览器订阅；取消任务必须调用 cancel API。切换页面默认允许任务后台继续。

### 12.4 针对当前前端的落地修改

1. 合并 main/router/vite 的 JS/TS 双份入口，删除模板页面与清单自依赖；统一 lockfile。
2. API 由 OpenAPI schema 生成类型；建立 auth/conversation/run/document stores，停止逐页复制请求逻辑。
3. PDF 两条上传路径共用一个函数，先检查业务成功，再订阅 ingestion 状态；READY 之前不开放问答。
4. “预约成功”由 tool_execution/业务查询事件驱动，去掉匹配模型文本的正则。
5. PDF 先用稳定页码定位；需要高亮时采用受控 PDF.js 文本层，而非假设原生 iframe 提供 chunk 高亮。
6. Delta 按 30–60ms 等实验窗口批量渲染；只对完成的代码块高亮，选择性加载代码语言，保留 DOMPurify。
7. 长答案尊重用户滚动位置；历史与运行详情分页加载。

当前前端 `npm run build`（含类型检查）通过，Vue/Nginx 镜像已部署健康。历史 Playwright 检查覆盖桌面、手机窄屏、深色主题，以及真实 `8088/agent` 的 OWNER 活动创建/发布、直接抢课、0 元订单、对账、刷新恢复和暂停；MEMBER 展示使用模拟 API 验证。2026-09-29 新增课程 mock API 检查覆盖分页/筛选/重试、空结果、404、401 和主题；真实 8088 浏览器完成注册、退出、登录、课程搜索、详情和 Agent 咨询预填，预填无 API 写请求，错误与警告均为 0。真实 API 的 MEMBER 权限、浏览器内 Agent 审批、会话淘汰后任务续跑及真实模型流程仍需验收。

## 13. 后端深度：要有可解释的性能与一致性

### 13.1 并发、资源隔离与背压

不要在 LLM 等待期间持有 MySQL 事务/连接。为普通查询、LLM、文档解析、Embedding、评测分别设置连接池/并发预算，评测任务不能挤占在线预约。

Java 21 虚拟线程可以作为 IO 场景的实验选项，但不自动增加数据库或模型配额。Python async 适合网络 IO，CPU 解析另用进程/worker。先记录基线线程/连接/队列指标，再选择实现。

容量估算用 L ≈ λW：假设请求进入速率 2 次/秒、平均任务持续 15 秒，则平均约 30 个在途任务；还要区分在模型调用、排队和等待审批的比例。这个例子不是项目实测 QPS。

超出限额返回 429/排队状态与 retryAfter；排队要有最大长度和最大等待时长。不能依靠无限线程/无限 asyncio task 消化压力。

### 13.2 数据库与缓存

- 为 conversation(user/createdAt)、task_request(clientRequestId)、tool_execution(tenant/actionId)、approval(status/expiry)、reservation(slot/status)、outbox(status/nextRetryAt) 设计索引。
- 标题在首次用户消息后生成/截取一次，按需更新，去掉“读标题列表时遍历所有会话更新”的 N+1 写入。
- 已实现的全局课程目录由 `CourseCatalogService` 统一查询；缓存默认首页、详情、Agent 默认列表、固定课程选项与校区，不缓存任意搜索/筛选/其他分页。全局展示数据可共用缓存，但登录、运行和空间成员校验仍在读取前执行。
- Caffeine 默认 10 秒、最多 1000 项，合并同 JVM 同 key 加载；Redis `catalog:v1:*` 默认 60 秒。Redisson `RLock` 等待最多 1 秒，拿锁后再次检查 Redis，再查询 MySQL 并回填；watchdog 续租，finally 释放。
- 默认课程展示允许约 60 + 10 秒更新延迟，另加查询耗时；Agent 单 run 30 秒结果缓存还可能叠加。锁超时或 Redis 冷缓存故障返回 503，本地命中可使用至过期。库存、权限、审批、会话继续实时校验，数据库约束保障业务正确性。
- Redisson 使用独立客户端，沿用现有 Jedis 连接配置，不新增 Redis 服务。Prometheus 记录本地命中、首次共享缓存查询与回源计数；14 项课程测试和完整 Compose 命中验证通过，尚未进行性能对比，详见[课程目录与两级缓存](../modules/课程目录与两级缓存.md)。
- 文档下载优先受控流式读取或短期签名 URL，避免每请求多份完整 byte[]；并发内存峰值要实测。

### 13.3 消息与补偿

Java outbox、Python Run 幂等接收、试听持久请求的唯一约束，以及有限重试、DLQ、恢复器和对账状态都应可见。重放沿用原 runId/requestId/actionId；人工点击“重试”不是生成新的业务事实。

分别注入“DB commit 后发送前宕机”“broker confirm 丢失”“consumer 持久化后 ACK 前宕机”“ACK 后 worker 宕机”。解释每一步谁有责任恢复，比只画一个 MQ 图标更能证明后端能力。

### 13.4 权限与公开部署准备

权限采用“空间成员角色 + 资源 ACL + 操作作用域”，第一版可先做 owner/member/viewer。检索、下载、引用、事件订阅、审批、评测数据访问都检查同一套资源归属。

重点回归跨空间 runId/documentId/approvalId 猜测、撤权后恢复、恶意文档诱导工具调用、排序字段注入和会话串流。模型的同意/拒绝不是服务端授权结果。

把配置字面凭据迁到环境/部署 Secret，收敛 CORS，关闭生产提示词 debug 日志。新增任意代码执行时必须专门建设沙箱与网络限制；没有该需求就不开放 shell 工具。

## 14. 数据模型与代码组织

### 14.1 数据所有权

| 所有者 | 当前对象 | 约束 |
|---|---|---|
| Java/MySQL | 用户、空间成员、课程、校区、普通预约、审批、任务接收及 outbox | 身份与业务写入只由 Java 判定；新写入主键为雪花 ID，关联由服务层校验 |
| Java/MySQL | 试听活动、参与请求、库存和 0 元订单 | Flyway V1–V7；最终订单以本地事务、条件库存和唯一约束为准；无物理外键 |
| Python/PostgreSQL | 会话、Run、消息、事件、持久作业和 LangGraph checkpoint | Run 主键与 request_hash 幂等接收；不直接写试听业务表 |
| Python/PostgreSQL + pgvector | 文档、版本、片段、向量与评测 | 检索使用授权可见的完整版本 |
| MinIO | 原文和对象产物 | 数据库保存对象引用；经授权读取 |
| Redis | 登录频率、失败冷却、会话索引与 Token；试听预占与限流；课程共享缓存和 Redisson 锁 | 认证检查失败时停止认证；试听库存丢失后停受理并对账；课程缓存遵循短 TTL，不代替业务最终状态 |

详细字段、索引和状态以实际迁移、源码、[产品功能说明书](../product/产品功能说明书-研发版.md)及[试听模块设计](../modules/免费试听秒杀与Agent联动.md)为准。待建设的长期记忆、排课时段和支付不得列为现有业务表。
### 14.2 目录演进

当前保持 Java、Python、Vue 三个独立仓库；Java 仓库的 Compose 使用可配置相对路径构建另外两个镜像：

~~~text
zhikexing-backend/   # Java、Flyway、Compose、模块文档
  src/main/java/com/chy/zhikexing/auth/
  src/main/java/com/chy/zhikexing/catalog/
  src/main/java/com/chy/zhikexing/agent/
  src/main/java/com/chy/zhikexing/trial/
  src/main/resources/db/migration/V5__trial_flash_sale.sql
  deploy/rocketmq/
zhikexing-agent-runtime/                  # FastAPI、LangGraph、PG/pgvector
  zhikexing_agent/
  tests/
zhikexing-web/ # Vue 课程广场、工作台与审批卡
  src/features/courses/
  src/features/agent/
~~~

不要机械把所有 Controller 拆目录。先按业务能力抽 service 接口和 DTO，控制跨模块依赖；跨仓库契约按 schemaVersion 发布，CI 校验兼容性。

建议保留至少 8 篇 ADR：双语言、双数据库、单编排器、消息投递、checkpoint/副作用边界、检索引擎、记忆策略、多 Agent 是否默认启用。每篇写问题、候选方案、选择原因、代价、验证数据与撤销条件。

## 15. 评测：把“我做得更好”变成可复现证据

### 15.1 数据集建设

先建 50 个手工审阅的冒烟任务，再扩展至约 300 个正式样本。用自建课程、规则文档、预约时段与模拟用户建立可分发 fixtures；包含旧版/新版规则、表格、扫描件和互相矛盾的资料。仓库根个人简历不作为默认对外评测语料。

建议 300 个样本按主要类型互斥划分，另附多轮/表格/安全等辅助标签：

| 主要类型 | 数量 | 成功判定 |
|---|---:|---|
| 单文档事实问答 | 80 | 必要事实正确，引用支持结论 |
| 多文档、多约束比较 | 50 | 所有硬约束被考虑，无遗漏依据 |
| 版本与证据冲突 | 30 | 正确识别冲突，使用适用版本或说明无法确定 |
| 无答案/需澄清 | 30 | 合理拒答或追问，不虚构结果 |
| 预约业务办理 | 60 | 正确查询、有效审批、正确且唯一的业务状态 |
| 权限与提示注入 | 30 | 不泄露、不越权、不绕过审批 |
| 故障恢复场景 | 20 | 恢复后状态与业务结果一致 |

按文档族、业务模板和任务场景分组拆分为开发 180、验证 60、最终留出测试 60，避免同一题改写进入不同集合。小类别的测试样本仍较少，所以分类指标附分母和不确定性；持续积累后扩大独立测试集。

每条样本保存 input、初始数据库/文档版本、允许工具、必要事实、gold evidence、目标业务状态、禁止行为、grader、来源与授权说明。允许多条合法执行路径，不要求模型照着一条固定工具序列运行。

### 15.2 评测不是只让另一个模型打分

1. **规则与状态 grader**：预约是否唯一、参数是否正确、是否有批准记录、是否越权、是否调用禁止工具、终态是否与数据库一致。
2. **检索 grader**：人工标注相关证据，计算 Recall@k、MRR/nDCG，按文档类型分层。
3. **回答 grader**：事实正确、约束满足、引用支持、必要信息完整；能规则化的先规则化。
4. **LLM judge**：处理开放性表述，使用明确的二元 rubric；首版复用百炼 `qwen3.7-flash` 和同一 API Key，独立记录 judge 配置与费用。生成与评审使用同一模型可能产生偏差，必须依靠规则、业务状态及人工标注校准，不能把自评分当独立证据。后续可选用同一百炼账户内的其他模型做对照，固定 judge/版本并记录人机分歧。

Ragas 可用于部分检索/回答指标，但其分数不是项目真实业务成功率。[Ragas 指标文档](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/)

要检查最终环境状态而不只检查“助手说成功了”；保留 task、完整事件、grader 与 outcome 的关联。[Anthropic Agent evals](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)、[美团评测实践](https://tech.meituan.com/2026/08/07/Agent-Evaluation.html)

### 15.3 实验矩阵与公平比较

| 实验 | 固定项 | 改变项 |
|---|---|---|
| E01 检索升级 | 文档、问题、embedding、生成模型 | B0/B1/B2/B3/B4 检索链路 |
| E02 chunk 策略 | 语料与检索器 | 字符块、结构块、父子块 |
| E03 记忆策略 | 长对话任务与模型 | 最近窗口、窗口+摘要、类型化事实 |
| E04 Agent 编排 | 同一工具、任务、预算上限 | 单 Agent、双专家 |
| E05 模型策略 | 测试集、工具权限和 grader | 固定 `qwen3.7-flash` 的预算/重试策略；后续可选百炼内多模型路由，不新增厂商 Key |
| E06 可靠性 | 工具模拟器和业务 fixture | 故障位置、重复消息、并发程度 |

当前旧项目没有的功能标为 N/A，并单独记录覆盖范围；不能把它“无法运行多 Agent 恢复测试”算成 0% 后宣传提升。迁移 baseline 与算法消融分开：前者可以改变框架/存储，后者尽量一次只改一个变量。

随机性较高的任务每个配置至少重复 3 次。报告单次平均成功率，以及同一任务 3 次全部成功的比例；不能把“3 次中挑最好一次”称为系统稳定成功率。最终测试只在版本冻结后运行，数据量允许时给置信区间。

### 15.4 验收目标及精确定义

**以下仅是首轮设计目标，实际达不到时按失败分布改进，不能把目标填进简历。**

| 指标 | 定义 | 初始门槛/目标 |
|---|---|---|
| Recall@5 | 每题前 5 个证据覆盖的标注相关证据比例，再求宏平均 | ≥ 0.85，附完整样本数与分类型结果 |
| Citation support | 被引用结论确实得到所引片段支持的比例，人工抽查校准 | ≥ 0.90 |
| 任务成功率 | 满足该任务全部硬性 rubric 的运行数 / 合格测试运行数 | ≥ 0.85，且比匹配 baseline 有可解释收益 |
| 无证据场景错误断言率 | 无答案场景中仍给出确定错误结论的次数 / 此类任务数 | ≤ 5%，附误拒答率，避免靠全部拒答取巧 |
| 工具参数有效率 | schema/业务字段正确的工具提案数 / 工具提案总数 | ≥ 0.98；服务端拒绝错误参数不算模型选对 |
| 权限/审批专项 | 所有恶意/越权测试的硬性不变量 | 当前测试集全部通过，注明仅是已测范围 |
| 重复业务效果 | 故障重试中同 actionId 产生的额外预约数 | 测试中 0，不能称生产“永不重复” |
| 崩溃恢复 | kill 后重新调度并恢复到可执行状态 | 本地受控环境目标 ≤ 30s，不含外部模型完成时间 |
| 断线重放 | 已持久化业务事件在重连后完整且不重复 | 专项全部通过 |
| 非模型 API p95 | 受控数据量、20/50/100 RPS 梯度下的业务读接口 | 首轮目标 < 200ms，必须记录硬件与实际负载 |
| 首次可见状态 | 请求接受到前端展示排队/开始事件 | mock provider 下 p95 < 1s |
| 模型首 Token / 完成时间 | 分别记录 provider 调用、排队与整个任务 | 不预设跨模型统一数值；按场景报告 p50/p95 |
| 单成功任务成本 | 全部成功与失败尝试的模型/检索成本合计 / 成功任务数 | 与质量、延迟一起比较，不能只报低价请求成本 |

### 15.5 评测门禁与反馈

每次修改 prompt/tool/graph/retrieval 配置，先跑不计费的逻辑/契约测试，再跑 50 条小样本回归；发布前跑冻结版本的完整评测。关键安全/业务不变量失败则阻止发布。

用户差评可生成待标注案例，但不能自动把用户原文和联系人加入公共数据集。开发者对失败按“解析、召回、推理、工具选择、权限、业务冲突、模型/网络、前端”分类，再决定改哪个模块。

## 16. 测试与故障演练清单

### 16.1 测试分层

| 层 | 工具 | 必须覆盖 |
|---|---|---|
| Java 单元/集成 | JUnit、Testcontainers | 审批 CAS、幂等、库存条件更新、ACL、outbox、迁移 |
| Python 逻辑/集成 | pytest、async 测试、隔离 PG | 图路由、预算、schema、持久化恢复、租约、授权边界 |
| 契约 | OpenAPI/JSON Schema、录制 fixture | Java/Python/前端相同事件、错误和工具定义 |
| 前端单元 | Vitest | SSE parser、reducer、重复事件、UTF-8、状态转换 |
| 端到端 | Playwright + mock provider | 登录→文档→提问→引用→审批→回执；刷新/断线/拒绝 |
| LLM 效果 | eval harness + 固定数据集 | 实际模型调用、质量/费用、失败归因 |
| 性能/故障 | k6、受控 kill/代理故障 | 并发、排队、恢复、背压、慢消费者 |

旧 `ZhikexingApplicationTests` 仅在显式设置 `LEGACY_AI_LIVE_TEST=true` 时执行，默认不调用真实模型或写向量索引。2026-09-29 `mvn verify` 完成 42 项通过、22 项未启用的外部集成/旧入口测试跳过；登录保护 22 项与课程目录 14 项专项测试按各自隔离环境验证，具体环境和命令见模块文档。

### 16.2 十个必须可复现的故障场景

1. 用户 B 以用户 A 的 conversationId 上传，写入被拒绝，A 的检索结果不被污染。
2. 新文档版本 embedding 失败，旧版本仍可查询，新版本不显示 READY。
3. Java 预约 commit 后、Python checkpoint 前 kill worker，恢复后返回原预约号。
4. 同一批准请求重复发送、同 actionId 改参数、过期后批准，均按契约返回而不越权写入。
5. 等待审批时重启所有应用，审批仍存在，批准后恢复原 run。
6. 新 worker 接管后让旧 worker 返回，不能覆盖 checkpoint/事件，旧 epoch 写动作被拒绝。
7. broker confirm 丢失及 consumer ACK 前后宕机，作业不丢且业务效果不重复。
8. SSE 连续断开、重连和重复事件，最终消息一致；切会话不串流。
9. 用户撤权/取消后恢复 run，不能继续新检索或写工具；已完成的副作用被如实展示。
10. 模型 429/超时、无效 JSON、工具错误、恶意文档，循环和费用不越过配置上限。

### 16.3 压测要拆开测

非模型 API 用固定数据规模测试；SSE 用确定性的 mock provider 测 50/100/200 条连接；真实模型以配额允许的 5/10/20 等并发梯度小规模测试。真实模型测试必须设置硬性预算和停止条件。

每轮记录 CPU/RAM、机器规格、数据库规模、版本、预热、持续时间、错误率、p50/p95/p99、队列深度和连接池。长任务需要足够长的稳态窗口，初值可 15 分钟。不能把 mock 模型下的吞吐写成真实大模型吞吐。

## 17. 部署、资源预算与完整工程

### 17.1 当前 Compose 与规划的部署层级

| 层级 | 组成 | 用途 |
|---|---|---|
| Compose 默认核心 + `app` | Vue/Nginx、Java、Python Runtime、MySQL、PG/pgvector、Redis、RocketMQ、MinIO | 2026-09-29 已重建三个应用镜像，登录、课程和 fixture 试听闭环复验通过 |
| 再启用 `observability` | 上一层 + Langfuse、ClickHouse、Prometheus、Grafana、OTel Collector | 当前已启动，健康入口通过，Prometheus 5 个抓取目标正常并显示缓存指标；外部模型轨迹和观测看板完整业务联动仍需核对 |
| research（规划） | 观测层 + 经过实验论证的 Elasticsearch 等可选组件 | 检索、吞吐或多模型对照，不属于当前必需部署 |

Langfuse 自托管不是“只起一个容器”：需要配套 PostgreSQL、ClickHouse、Redis、对象存储等基础设施，按官方版本部署并隔离数据库/用户。[Langfuse 部署文档](https://langfuse.com/self-hosting/deployment/docker-compose)、[架构说明](https://langfuse.com/handbook/product-engineering/architecture)

容量仅作为启动规划：不本地跑大模型时，core 可先按 4–8 vCPU/8–16 GB 内存分配实验资源；全量观测、搜索、OCR 可从 8 vCPU/16–32 GB 起评估。实际需求取决于 JVM heap、ES heap、OCR 模型、并发和文档量，必须用容器内存指标验证，不承诺低配全开。

GPU 不是完成主项目的前提。Agent 与 Embedding 使用百炼托管接口并共用一把模型 API Key；可选本地 reranker 的 CPU/内存与延迟单独测量，不作为首版启动前提。这里的单 Key 仅指 AI 模型服务，OSS AccessKey、数据库与其他基础设施凭据仍分别管理。费用控制按“fixture 测试→小样本→冻结全量”递进，费用 = 模型输入/输出 + embedding + judge + 重排计算资源 + 存储/运行资源，使用实际价目与账单版本，不给未经核实的月费用承诺。

### 17.2 CI/CD、版本和恢复

- PR：静态检查、类型检查、单元/契约测试、隔离数据库迁移测试、核心 mock E2E。
- 发布：不可变镜像、schemaVersion、向后兼容迁移、配置校验、数据库备份、冒烟与灰度。
- migration 采用 expand-contract：先加新表/字段和兼容读，再迁移，再移除旧路径；不把数据库回滚等同于直接降代码版本。
- 运行：liveness 检查进程健康，readiness 检查关键依赖与接受新任务能力，worker 优雅退出并停止领取新作业。
- Kubernetes 阶段按队列积压、活动任务/并发和资源利用扩容；只按 CPU 扩容可能不能反映 IO 等待。
- 写备份恢复 runbook，并实际恢复一个测试环境。单机 Compose 不是高可用部署，部署了 K8s 也不自动证明数据库和队列高可用。

### 17.3 更深方向的进入条件

| 可选方向 | 什么时候做 | 交付证据 |
|---|---|---|
| 代码执行沙箱 | 明确增加用户数据分析/文件生成工具 | 非 root、资源/时长/文件/网络限额、无宿主凭据与 Docker socket；攻击样本验证 |
| gVisor | 需要执行不可信代码且已有 Linux 实验环境 | runsc 隔离与普通容器的边界/性能对照；Windows 本机不直接假定可运行 |
| 本地 vLLM | 有预算/硬件且想补推理服务理解 | 固定模型的 TTFT、吞吐、batch、KV cache、显存与质量报告 |
| 小型 LoRA/SFT | 失败分析证明模型格式/领域能力是主要瓶颈 | 数据来源、训练/验证/测试分离、训练成本、与 prompt/RAG 基线比较 |
| GraphRAG | 多跳实体关系问题成为主要失败源 | 相同语料下比混合检索多解决哪些问题及索引成本 |
| 可视化编排 | 已有稳定图 DSL、版本与回放 | 编辑→校验→发布→旧版本运行的完整语义 |
| Temporal 等持久工作流 | 出现天级流程、大量定时器和补偿需求 | 与 LangGraph 划清“业务长流程”和“Agent 决策图”的所有权 |

沙箱与推理可参考 [gVisor 官方文档](https://gvisor.dev/docs/)、[vLLM 官方项目](https://github.com/vllm-project/vllm)。它们在主业务未闭环前不应挤占审批、恢复和评测的时间。

## 18. 后续研发计划

### 18.1 已有工程基础

Java 21/Boot 4.1.1、Python 3.13/FastAPI/LangGraph、Vue 3 组成三仓库应用。MySQL 保存业务真相，PostgreSQL/pgvector 保存运行与知识数据，Redis 负责登录保护、共享会话和试听原子准入；Caffeine/Redis 缓存课程展示，Redisson 协调缓存重建。同一 RocketMQ Broker 承载 Agent 普通命令与试听事务消息。课程广场、咨询预填、免费试听活动、0 元订单、审批与本人记录均已实现。基础中间件与业务验证、登录保护 22 项、课程目录 14 项专项测试通过；完整 Compose 已于 2026-09-29 重建复验，最新范围见[开发与验证记录](开发与验证记录.md)。

### 18.2 下一批工程任务与验收

| 优先级 | 工作 | 可复核的完成条件 |
|---|---|---|
| P0 | 部署恢复与版本演练 | 在已通过的完整 Compose 业务烟测基础上执行备份/恢复、重启后数据核对和配置迁移；保留无敏感信息的操作记录 |
| P1 | 登录浏览器边界补测 | 部署、真实 API 会话上限/冷却与浏览器重新登录已通过；补真实浏览器冷却倒计时、会话淘汰后重新登录和已提交任务续跑 |
| P0 | 真实模型与剩余浏览器路径验收 | 在独立测试账号与合成数据中由真实模型查活动、起草，浏览器中由用户批准并查询 Java 订单；补真实 API 的 MEMBER 权限、跨设备和网络故障 |
| P0 | 灾备演练 | Broker、Redis、MySQL、Python 分别故障；验证 UNKNOWN 回查、请求恢复、预占补偿、对账和禁止缓存丢失后的自动重建 |
| P1 | 端到端自动化 | 将已通过的 Java 真实中间件测试和 Compose fixture 烟测纳入稳定 CI；把已通过的真实浏览器 OWNER 路径固化为可重复脚本，再补 MEMBER/Agent 路径与统一报告 |
| P1 | 容量与性能 | 用独立环境测受理、预占、落单及课程查询；固定数据量和实例数对照冷/热缓存，记录回源量、p50/p95/p99、锁等待、Broker 积压与资源；没有测量前不写提升百分比 |
| P1 | 前端稳健性 | 在真实后端验证跨设备记录恢复、网络超时的原键重试、活动开抢时间与服务端权限；补回归与无障碍检查 |
| P2 | RAG 与 Agent 质量 | 建版本化 300 样本集，对检索/引用/审批/工具选择进行人工标注、消融和成本分析；实验数据与模型配置一起归档 |
| P2 | 高可用与安全 | Broker 副本、ACL/TLS、Redis 隔离、worker fencing、审计留存及恢复演练；多副本上线需单独验证 |

时间不限时也应按真实证据推进：在完整 Compose 业务烟测与浏览器 OWNER 抢课路径通过后，补真实模型和剩余浏览器角色/Agent 路径，再量化性能与质量，最后考虑 Elasticsearch、MCP、受控 Skills、多 Agent 或本地推理。每增加一种组件都应解释解决了什么已测量的问题。

## 19. 面试展示与技术证据

演示顺序：知课行登录页 → 产品首页 → 课程广场搜索/筛选 → 课程详情 → Agent 咨询预填 → `/agent` 工作空间和知识引用 → 普通预约审批与 actionId 幂等 → “免费试听”创建/发布 → 直接抢课与本人记录 → Agent 起草 claim_trial → 人工批准 → 回查订单终态和对账。当前可展示 MySQL V1–V7 迁移、隔离中间件/登录/缓存测试、完整 Compose fixture 跨服务烟测、真实浏览器课程流程和缓存命中指标；故障恢复、外部模型与剩余浏览器路径按各自验收范围展示。

**AI Agent 岗表述**：独立 FastAPI/LangGraph Runtime 负责知识检索、证据、审批暂停与恢复；将试听参与建模为有固定 actionId 的人工审批工具，跨运行查询 Java 最终结果。明确运行完成、审批执行和订单成功是不同状态。

**后端岗表述**：Java 21/Boot 4.1.1 服务中以 MySQL 持久请求、Redis Lua 预占和 RocketMQ 事务消息构成异步抢课链路；消费者以条件库存和唯一键创建 0 元订单，使用事务回查、重试、补偿和只读对账处理不确定状态。同一 Broker 还承载 Agent 普通命令，Java 消费者桥接 Python HTTP 幂等接收。

登录模块可补充：通过 Redis Lua 与 `Semaphore` 在 BCrypt 前限制请求频率和认证并发，加入失败冷却与 ZSET 会话上限；10 项流程和 12 项真实 Redis 测试覆盖拒绝顺序、冷却恢复与会话淘汰竞态。完整说明见[项目演示与面试证据](项目演示与面试证据.md)。

课程模块可补充：将课程页面、Agent 工具和试听目录统一到 Java 查询服务，用 Caffeine + Redis 两级缓存承接热点展示读取，Redisson 锁合并跨实例缓存重建；两个独立 JVM 冷缓存测试验证合计一次回源，完整 Compose 已观测到两级命中。该结论说明正确性与复用范围，尚不包含响应时间或吞吐提升比例。

简历只写已验证内容，注明隔离真实中间件的并发规模、完整 Compose 使用 fixture 模型，以及真实浏览器目前覆盖 OWNER 直接抢课路径。真实百炼模型、真实 API 的 MEMBER 权限与浏览器内 Agent 审批、生产级容量和高可用尚未验收，不能用设计目标替代。演示脚本、岗位措辞及证据入口见[项目演示与面试证据](项目演示与面试证据.md)、[试听模块验证记录](../modules/免费试听秒杀验证记录.md)。
## 20. 配套调研、审计和阅读顺序

建议先读本文第 1、5、6、18、19 节确认产品和投入，再按研发模块读其余章节。

- [2027 岗位调研：8 个官方 2027 完整 JD 与 5 个实习/社招参照](2027岗位调研.md)
- [技术资料与开源项目阅读清单](技术资料与开源阅读清单.md)
- [模型服务与 API 配置：百炼单 Key 决策、迁移与验收](./模型服务与API配置.md)
- [中间件与基础设施清单](./中间件与基础设施清单.md)

研究结论的边界：招聘样本不是市场普查；官方开源不等于公司全部采用；main 分支与在线文档可能更新；静态风险不等于已经复现；目标架构与指标不等于现有实现。研发时按锁定版本、实际实验和真实业务约束继续修订。
