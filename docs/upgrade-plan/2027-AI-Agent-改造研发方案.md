# 从 Spring AI 交互原型到可恢复的业务 Agent 平台

**面向 2027 届 AI Agent 研发与后端开发岗位的改造研发方案**

调研日期：2026-09-17。项目基线：后端 46fa3cc；前端 246d2d0。适用对象：同时准备 Agent 和后端岗位，Agent 内容更多，允许引入 Python，追求完整工程。

模型选型更新：2026-09-18，用户已确定日常 Agent 使用阿里云百炼 `qwen3.7-flash`，Embedding 保留 `text-embedding-v4` / 1024 维，两者共用一把百炼 API Key。具体配置、迁移边界与验收见[模型服务与 API 配置](./模型服务与API配置.md)。

原始方案交付时完成了代码审计、公开资料调研和研发设计。本文中的性能、效果和可靠性目标仍是**待验证的验收目标**；实际完成情况请以随后补充的验证记录为准，不能把目标数字作为项目成绩。

**实施进度补记（2026-09-18）：** 核心工程版已完成实际开发、联调与 Docker 部署。独立 Python 服务见 [intelligent-agent-runtime 仓库](https://gitee.com/chy66666/intelligent-agent-runtime.git)，通过内部 HTTP 业务工具及 RabbitMQ 命令与原 Java/Vue 项目联动。已落地多空间权限、知识版本与引用、审批恢复、幂等预约、取消、规则评测、可观测及备份恢复；多 worker、300样本正式效果基准、OCR/reranker和MCP/多Agent实验仍有独立验收条件。实际数据以[开发与验证记录](./开发与验证记录.md)及[研发接口契约](./研发接口契约.md)为准。

## 1. 先给结论

建议将项目升级为 **“企业知识与业务办理 Agent 平台”**，第一条完整业务线沿用已有课程、校区、预约和 PDF 数据，做成**有依据的课程咨询、方案比较与预约办理助手**。

主架构采用 **Java 业务后端 + Python Agent Runtime + Vue 工作台**：

- Java 负责身份、空间权限、课程与预约、审批、工具执行、幂等、事务与对外 API。
- Python 负责 Agent 状态图、检索、上下文、模型与工具选择、运行恢复、评测和实验。
- Vue 展示答案、文档引用、执行步骤、审批卡、恢复状态和质量报告。

你的技术栈不是整体过时。Java 21、Spring Boot、MySQL、Redis、Vue 都可以保留；Spring AI 1.0.0 确实需要评估升级，但“换成最新版本”本身不足以形成有说服力的项目。真正的改造重点是：

| 当前形态 | 目标形态 | 面试中能拿出的证据 |
|---|---|---|
| 模型流式回答 | 有状态、受预算约束的任务执行 | 单次任务的状态、事件、工具结果和失败恢复录像 |
| PDF 向量问答 | 带权限、版本和引用的知识检索 | 固定评测集、召回与答案指标、消融实验 |
| 模型调用方法写预约表 | 审批绑定参数、幂等执行、结果回查 | 重复调用和宕机注入后只产生一笔业务结果 |
| 聊天窗口充当历史 | 完整消息记录、工作记忆和长期记忆分离 | 长对话、重启、记忆删除的回归用例 |
| 多个独立聊天页面 | 统一任务工作台 | 断线重连、切会话不串流、引用定位 |
| 能启动的个人 Demo | 可部署、可测试、可观测的工程 | CI、迁移脚本、压测报告、运行手册 |

主方案不追求把所有热门框架都装进来。LangGraph 是唯一主编排器；Spring AI 保留为旧接口适配和迁移基线。完整工程中，Agent 与后端能力约按 **6∶4** 分配设计和展示篇幅。

## 2. 现有项目的真实状况

审计已覆盖后端全部 56 个生产 Java 文件、1 个测试类、配置、Mapper XML 与 README，以及前端业务页面、路由、服务、组件和构建配置。以下结论主要来自静态代码；不能当作已在运行环境复现的漏洞或性能数据。

### 2.1 已有基础应该保留

| 层 | 仓库中可确认的实现 | 判断 |
|---|---|---|
| Java | Java 21、Boot 3.5.3、Spring AI 1.0.0、MyBatis-Plus 3.5.10.1 | 有良好的 Java 迁移起点 |
| Web | Spring MVC 返回 Flux 字符串 | 支持流式内容，但不是完整 WebFlux 架构，也不是标准 SSE |
| AI | 普通聊天、课程客服、PDF 问答、生活模拟游戏 | 四个应用入口，尚无持久化 Agent 任务运行时 |
| Tools | 课程查询、校区查询、预约插入 | 有真实业务动作，可以继续深入 |
| RAG | OSS 原文件、按页解析、段落切块、Redis 向量检索 | 可保留为 baseline；有中文句边界及重叠处理 |
| 记忆 | JDBC 的 20 条消息窗口，游戏采用内存窗口 | 上下文已有基础，完整历史需要独立设计 |
| 身份 | Redis Token 登录、会话归属检查 | 已有鉴权；需要补齐上传路径和异步边界 |
| 前端 | Vue 3、Vite 6、Markdown、DOMPurify、PDF 预览；Pinia/Naive UI 已安装但未形成业务状态/组件体系 | 足够承载目标产品，需真正使用类型与状态治理，无需重写 React |

版本依据：[后端 pom.xml](../../pom.xml#L6)、[前端 package.json](https://gitee.com/chy66666/web-intelligent-integrated-interaction-platform/blob/master/package.json#L1)。前端 package 声明包含范围版本，不能把它们当成生产环境实际安装版本。MySQL、Redis 服务端的实际运行版本未核验。

### 2.2 必须优先解决的问题

**P0 表示进入新版业务链路前必须修复；P1 表示首个可公开演示版本应解决。**

| 优先级 | 代码依据与发现 | 改造动作 |
|---|---|---|
| P0 | [PDF 上传入口](../../src/main/java/com/chy/ai/controller/PdfController.java#L40) 没有校验会话所有者；[切块元数据](../../src/main/java/com/chy/ai/service/impl/FileServiceImpl.java#L140) 仅用传入的 chatId 关联向量 | 上传前查权限；服务端生成 document/version ID；检索强制空间与文档权限；补双用户污染测试 |
| P0 | [PDF 过滤表达式](../../src/main/java/com/chy/ai/controller/PdfController.java#L83) 拼接 chatId；[排序字段](../../src/main/java/com/chy/ai/tools/CourseTools.java#L34) 使用模型传入字符串 | 使用结构化过滤、ID 格式校验、排序枚举。这里是可推导的注入风险，尚未做利用复现 |
| P0 | [预约工具](../../src/main/java/com/chy/ai/tools/CourseTools.java#L45) 直接把模型参数写库 | 先创建待确认操作，再由服务端审批与命令执行；加入归属、业务校验和幂等 |
| P1 | [身份回退逻辑](../../src/main/java/com/chy/ai/service/impl/FileServiceImpl.java#L249) 在取不到用户时默认用户 1；ThreadLocal 未完整处理异步流生命周期 | 缺少身份直接拒绝；跨线程显式传递不可变身份对象；补并发/异步隔离测试 |
| P1 | [密码实现](../../src/main/java/com/chy/ai/util/PasswordEncoder.java#L21) 是加盐单次 MD5 | 使用 BCrypt/Argon2，并支持旧密码登录成功后渐进迁移 |
| P1 | [20 条 memory](../../src/main/java/com/chy/ai/config/IiipConfiguration.java#L45) 被[历史接口](../../src/main/java/com/chy/ai/controller/ChatHistoryController.java#L43) 直接读取 | 独立保存完整消息；窗口和摘要仅是模型输入视图 |
| P1 | [同步文件管线](../../src/main/java/com/chy/ai/service/impl/FileServiceImpl.java#L57) 串联 OSS、DB、解析、Embedding、向量写入 | 改为可重试 ingestion job；新版本 READY 后再切换；补偿旧对象和索引 |
| P1 | [删除会话](../../src/main/java/com/chy/ai/service/impl/IiipChatRecordServiceImpl.java#L93) 只删记录与 memory | 定义消息、知识库、对象、向量各自生命周期，不能仅靠删除聊天行 |
| P1 | [流式接口](../../src/main/java/com/chy/ai/controller/ChatController.java#L31) 为 text/html 内容流 | 建立标准 SSE 事件协议、runId、seq、恢复游标、错误及结束事件 |
| P1 | 前端 PDF 上传路径只检查 HTTP 成功，后端 Result.fail 仍可返回 HTTP 200 | 统一错误契约；“已上传”与“已索引、可问答”分开显示 |
| P1 | 前端流式更新依赖当前页面消息数组，切换会话后旧流可能更新新会话 | 按 runId/messageId 更新固定记录；切页释放订阅；保留后台任务 |
| P1 | 前端有图片/音频/视频附件入口，[后端聊天接口](../../src/main/java/com/chy/ai/controller/ChatController.java#L32) 却只接 prompt/chatId | 先按实际能力关闭或限制入口；新增能力后再开放 |
| P1 | [测试类](../../src/test/java/com/chy/ai/IiipApplicationTests.java#L21) 没有断言，部分测试真实调用模型并写向量库 | 建隔离测试配置、测试数据和 mock provider；默认 CI 不触发真实计费接口 |

额外核对了本机 Spring AI 1.0.0 依赖源码：MessageWindowChatMemory 会裁剪后调用 saveAll，JDBC 实现会替换对应会话的消息。因此旧消息会从该存储中淘汰，而不只是“不再放入 prompt”。游戏使用内存 memory，历史却查 JDBC，也是需要修复的实际错位。

当前项目也不是“毫无安全措施”：正常聊天/历史已检查所有权，PDF 上传已有大小上限，Markdown 使用了 DOMPurify，模型密钥主要通过环境变量配置。方案只针对核实的缺口，不把已有能力抹掉。

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
| 后端并发与可靠性 | outbox/inbox、幂等、租约、资源隔离 | 故障注入、队列积压恢复、压测曲线 |
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
| Agent 服务 | Python 3.12 起步，FastAPI、Pydantic、LangGraph、uv | schema、异步 IO、状态图、依赖锁定；Python 小版本以集成测试结果最终冻结 |
| 业务数据库 | 保留 MySQL | 复用用户、课程、校区、预约数据和后端知识体系 |
| Agent/知识数据库 | PostgreSQL + pgvector | 持久化 checkpoint、run/event、文档 chunks、向量和评测元数据 |
| 缓存 | Redis | 限流、短缓存、临时通知；不作为唯一任务真相或业务幂等凭据 |
| 消息队列 | RabbitMQ | 跨语言任务投递、文档入库通知、重试/死信；不传逐 Token 流 |
| 文件 | 保留 OSS，定义 ObjectStorage 接口 | 原文、解析产物、报告；本地开发可替换兼容存储 |
| 检索 | pgvector dense + 词法基线；成熟后加 Elasticsearch BM25 | 用实验证明额外搜索引擎的必要性，避免一开始同时上多种向量库 |
| 文档解析 | Docling，先与现有 PDF reader 做对照 | 布局、表格、OCR；耗 CPU 的解析使用独立 worker |
| 模型接入 | 百炼 `qwen3.7-flash` + `text-embedding-v4` / 1024 维，共用一把百炼 API Key；小型 adapter | 聊天与向量化分开配置，统一处理工具、流、usage、超时与预算；当前不引入多厂商网关 |
| 观测/评测 | OpenTelemetry + Langfuse + pytest/规则 grader + Ragas | 分布式链路、LLM 成本/质量；业务事件仍保存在自己的数据库 |
| 前端 | Vue 3 + TypeScript + Pinia + Naive UI | 统一执行工作台，保留现有界面资产 |
| 工程 | Docker Compose、CI、Testcontainers、Vitest、Playwright、k6 | 可复现环境、跨栈验证和压测 |
| 后期部署 | Kubernetes/Helm | 在已具备无状态 API、持久化 worker、探针与资源预算后建设 |

**为什么允许 MySQL + PostgreSQL 两个数据库？** MySQL 保留现有业务与事务资产，PostgreSQL 服务 LangGraph checkpoint、知识与向量查询，两者没有跨库外键和共享写表。代价是两套备份、连接池、迁移、监控与一致性治理。如果实做发现运维收益不成立，可以通过 ADR 将业务也迁往 PostgreSQL；这是替代路线，不要求同时实现两套业务存储。

推荐并不意味着每个组件从第一天就部署。第一版用受控内部 HTTP 接入运行时；可靠投递阶段再加入 RabbitMQ。全文后续给出完整工程的目标形态。

### 4.4 版本升级策略

截至调研日，[Spring AI 官方项目页](https://spring.io/projects/spring-ai/) 显示 2.0.1；[官方入门文档](https://docs.spring.io/spring-ai/reference/getting-started.html) 明确 2.0.x 对应 Boot 4.0.x/4.1.x。本项目已成套升级到 Boot 4.1.1、Spring AI 2.0.1、MyBatis-Plus Boot 4 starter 3.5.17，JDK 保持 21。

迁移已适配 Jackson 3、MyBatis 服务包名、RedisClient、模型扁平配置和包含 `/v1` 的百炼兼容地址，并用 Flyway V4 保留旧聊天内容及顺序。独立副本的真实 Boot 启动、MySQL/Redis、注册登录、HTTP 业务接口、配置绑定和 SDK HTTP 协议验证均通过，详见 [Spring Boot 4 迁移与验证记录](SpringBoot4迁移与验证记录.md)。真实模型、Rabbit 消费和前端闭环以总体验收记录为准。DeepSeek 仅保留在旧版审计记录中，不作为目标配置的启动依赖。纯 Java 备选仍须按 [Spring AI Alibaba 官方兼容表](https://java2ai.com/docs/versions/) 配套 BOM。

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
    UI["Vue 工作台：任务、知识、审批、评测"] --> API["Java API / BFF"]
    API --> AUTH["身份、空间权限、配额"]
    API --> BIZ["课程、校区、预约、审批与工具执行"]
    API --> MYSQL[("MySQL：业务真相、提交记录、Outbox")]
    MYSQL --> RELAY["Outbox Relay"]
    RELAY --> MQ["RabbitMQ：Run / Ingestion 命令"]
    MQ --> INBOX["Python Inbox：先持久化接收"]
    INBOX --> PG[("PostgreSQL：Run、Event、Checkpoint、Chunk、Eval")]
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
    INGEST --> OSS["OSS：原文与产物"]
    API --> OSS
    API --> REDIS["Redis：限流与短缓存"]
    WORKER --> REDIS
    API -. "traceparent" .-> OBS["OTel / Langfuse / 指标与日志"]
    WORKER -. "traceparent" .-> OBS
~~~

部署单元先是 **Java API、Python API、Python worker、Vue 静态站点**。Python API、Agent worker、ingestion worker 可以来自同一代码库和镜像，通过启动命令/队列隔离职责；不要将每个图节点拆成微服务。

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
2. 在同一 MySQL 事务内按 UNIQUE(workspaceId, actorId, conversationId, clientRequestId) 查重、生成 runId、写 task_request 和 outbox。相同键/相同 payload 返回原 runId，相同键/不同 payload 返回 409；返回 202 不代表已开始模型调用。
3. relay 在发布确认后标记投递；失败重试。消息可能重复，不能宣称端到端 exactly-once。
4. Python 消费者在 PostgreSQL 事务内写 inbox、agent_run、待执行作业，再 ACK；重复 requestId 返回已有 run。
5. worker 通过持久化作业领取与租约开始执行。人在审批页面停留时，不长期占据 MQ 未确认消息或数据库连接。
6. 运行事件写 PostgreSQL 后对外发布；Java 校验读取权限后代理 SSE。
7. Agent 调用写工具时，Java 独立检查审批、参数版本、资源权限与业务不变量，事务提交后返回稳定结果。

Java 已提交但 Python 尚未接收时，GET /runs/{id} 根据 task_request 返回 submission.status=ACCEPTED_PENDING_DISPATCH、execution=null；事件入口等待或提示重试，不当作永久 404。Python 建立 run 后以运行时为 execution 状态权威。投递失败、运行失败和不存在要区分；task_request 不保存可与 PG 竞争写入的运行终态。

RabbitMQ 的 publisher confirm 与 consumer ACK 保证的对象不同，失败时仍可能出现重复投递。[官方可靠性文档](https://www.rabbitmq.com/docs/reliability)、[确认机制文档](https://www.rabbitmq.com/docs/confirms)

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

建议第一组工具：

| 工具 | 类型 | 约束 |
|---|---|---|
| search_courses | 只读 | 类型/学历/城市/排序枚举，分页上限，返回有限字段 |
| list_campuses | 只读 | 地域筛选，禁止无界返回 |
| get_available_slots | 只读 | 返回 slotId、余量、resourceVersion |
| search_knowledge | 只读 | 服务端授权资源集合，返回 evidenceId 和来源 |
| draft_reservation | 草稿 | 接收预先持久化的 actionId，生成 canonical args 和待审批展示内容 |
| submit_reservation | 写 | 需要有效 approvalId、argsHash、actionId、epoch |
| get_reservation_by_action | 只读 | 超时后对账，返回真实业务状态 |
| cancel_reservation | 写，后期 | 独立审批、状态前置条件，不能作为异常兜底偷偷调用 |

参数用 JSON Schema/Pydantic DTO；Java 再做 Bean Validation 与领域校验。课程、校区和时间使用 ID，展示名由后端查回。金额用分等固定精度单位，不让模型自由生成价格。

### 8.2 审批与幂等的具体实现

approval 保存 actor/tenant、runId、actionId、toolVersion、规范化参数摘要、目标资源版本、有效期、状态版本和决定人。参数修改、价格/时段版本变化或权限变化都要重新验证；需要重新确认时创建新审批。

同意接口使用 expectedVersion 做 CAS。重复点击返回已记录的决定；拒绝后不能用同一 approvalId 再变成批准。

Java 在同一事务写审批决定与 approval.decided outbox；Python inbox 去重后持久化 resume job，再由 worker 恢复对应 interrupt。定期按 actionId/approvalId 对账，覆盖通知延迟与丢失；不能仅靠审批接口落库后的一次同步 HTTP 调用唤醒。

补充信息接口 POST /runs/{id}/inputs 接收 interruptId、expectedVersion、clientRequestId 和用户消息，先在 Java 持久化请求/outbox，再由 Python 校验等待状态并幂等 resume。WAITING_APPROVAL 期间修改条件会使 Java 中的旧审批失效，更新 planRevision 并重新规划/确认；RUNNING 时的新条件需显式中断或排队。resume job 和输入消息按稳定 ID 去重。

执行命令的 MySQL 事务：

~~~text
先校验可信身份、资源归属及该结果的读取权限
校验 run epoch
锁定 approval / execution authority
查找 UNIQUE(tenant_id, action_id)
  已完成且 args_hash 相同：返回原结果
  同 key 不同 args_hash：409 IDEMPOTENCY_CONFLICT
校验审批状态、过期时间、资源版本与当前权限
校验并扣减时段余量 / 创建预约
写 tool_execution.result 与业务 outbox
提交事务
~~~

actionId 在调用 draft_reservation 之前就由应用生成并持久化，草稿接口自身也按 actionId 幂等；这样草稿响应丢失不会生成多份操作。它跨恢复保持稳定，不能用模型重试时新生成的 tool_call_id 代替。已执行结果回查仍校验读取权限；新业务写入再校验当前审批/epoch/取消条件。幂等记录保留期至少覆盖运行恢复、业务重试和审计窗口。

如果新增预约名额，使用条件更新如“余量 > 0 且版本匹配”保障不超额；去重以数据库唯一约束兜底，Redis 锁不是最终保证。外部调用不放在长数据库事务里。

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

保留登录/注册、暗色主题、历史侧栏、Markdown 消息和 PDF 分栏。统一为：

| 页面 | 核心交互 |
|---|---|
| 工作台 | 提问、任务约束、答案、引用、预约草稿 |
| 运行详情 | 节点时间线、工具结果、重试、预算、恢复、取消 |
| 知识库 | 文档列表、版本、解析任务、可用状态、引用预览 |
| 待确认操作 | 可核对的参数卡、批准/拒绝/过期/冲突 |
| 评测中心 | 数据集版本、方案对比、失败样本与 trace |
| 设置 | 空间权限、受控工具、模型配置，不暴露密钥 |

时间线显示可观察的动作：查了什么、用了哪些证据、写了什么、失败在哪一步。用“正在核验可预约时段”这样的状态，不伪造模型内部思维过程。

### 12.2 对外契约草案

| 方法与资源 | 请求/响应要点 |
|---|---|
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

本次实际验证：22 个 Vue 文件编译检查通过，npm run build（含类型检查）通过；消息组件产物约 1,007.66 kB，gzip 334.38 kB，存在拆分/高亮依赖优化空间。npm ls 检出自引用依赖缺失与多余依赖；没有进行浏览器、真实模型的端到端验证。

## 13. 后端深度：要有可解释的性能与一致性

### 13.1 并发、资源隔离与背压

不要在 LLM 等待期间持有 MySQL 事务/连接。为普通查询、LLM、文档解析、Embedding、评测分别设置连接池/并发预算，评测任务不能挤占在线预约。

Java 21 虚拟线程可以作为 IO 场景的实验选项，但不自动增加数据库或模型配额。Python async 适合网络 IO，CPU 解析另用进程/worker。先记录基线线程/连接/队列指标，再选择实现。

容量估算用 L ≈ λW：假设请求进入速率 2 次/秒、平均任务持续 15 秒，则平均约 30 个在途任务；还要区分在模型调用、排队和等待审批的比例。这个例子不是项目实测 QPS。

超出限额返回 429/排队状态与 retryAfter；排队要有最大长度和最大等待时长。不能依靠无限线程/无限 asyncio task 消化压力。

### 13.2 数据库与缓存

- 为 conversation(user/createdAt)、task_request(clientRequestId)、tool_execution(tenant/actionId)、approval(status/expiry)、reservation(slot/status)、outbox(status/nextRetryAt) 设计索引。
- 标题在首次用户消息后生成/截取一次，按需更新，去掉“读标题列表时遍历所有会话更新”的 N+1 写入。
- 课程只读查询允许短 TTL 缓存，key 包含空间、查询条件和业务版本；实时名额/预约结果写路径不依赖缓存判断。
- 缓存击穿可用 singleflight/短租约，但数据库约束才保障业务正确性；无效身份不能回退默认用户。
- 文档下载优先受控流式读取或短期签名 URL，避免每请求多份完整 byte[]；并发内存峰值要实测。

### 13.3 消息与补偿

数据库 outbox/inbox 的唯一约束、有限重试、nextRetryAt、DLQ、人工重放和 reconciliation job 都应有可见状态。重放沿用原 eventId/actionId；人工点击“重试”不是生成新的业务事实。

分别注入“DB commit 后发送前宕机”“broker confirm 丢失”“consumer 持久化后 ACK 前宕机”“ACK 后 worker 宕机”。解释每一步谁有责任恢复，比只画一个 MQ 图标更能证明后端能力。

### 13.4 权限与公开部署准备

权限采用“空间成员角色 + 资源 ACL + 操作作用域”，第一版可先做 owner/member/viewer。检索、下载、引用、事件订阅、审批、评测数据访问都检查同一套资源归属。

重点回归跨空间 runId/documentId/approvalId 猜测、撤权后恢复、恶意文档诱导工具调用、排序字段注入和会话串流。模型的同意/拒绝不是服务端授权结果。

把配置字面凭据迁到环境/部署 Secret，收敛 CORS，关闭生产提示词 debug 日志。新增任意代码执行时必须专门建设沙箱与网络限制；没有该需求就不开放 shell 工具。

## 14. 数据模型与代码组织

### 14.1 核心表草案

这些是设计字段，不是本次已创建的表。实施时用 migration、约束和契约测试固化。

| 所有者 | 表/集合 | 关键字段与约束 |
|---|---|---|
| Java/MySQL | workspace、membership | workspaceId、userId、role、aclVersion；成员关系唯一 |
| Java/MySQL | conversation | id、workspaceId、ownerId、title、createdAt、status；服务端分配 ID |
| Java/MySQL | task_request | requestId、runId、conversationId、actor、requestHash、createdAt；workspaceId+actorId+conversationId+clientRequestId 唯一 |
| Java/MySQL | approval | actionId、runId、argsHash、toolVersion、resourceVersion、status、version、expiresAt、decidedBy |
| Java/MySQL | run_execution_authority | runId、acceptedEpoch、cancelVersion；多 worker 业务 fencing |
| Java/MySQL | tool_execution | tenantId、actionId、argsHash、status、resultJson、reservationId；tenantId+actionId 唯一 |
| Java/MySQL | reservation、reservation_slot | owner、courseId、campusId、slotId、status、version、capacity；业务唯一约束和条件更新 |
| Java/MySQL | business_outbox | eventId、aggregateId、aggregateVersion、payload、attempt、nextRetryAt、publishedAt |
| Python/PG | inbox、runtime_job | eventId 唯一、runId、status、nextRunAt；用于持久化接收与唤醒 |
| Python/PG | agent_run、execution_attempt | runId、状态/版本、owner、epoch、leaseUntil、budget、graphVersion、acceptedCheckpoint |
| Python/PG | run_event | runId、seq、type、payload、messageId、timestamp；runId+seq 唯一 |
| Python/PG | message、artifact | messageId、role、content、status、runId、revision、时间；artifact 存对象引用与 hash |
| Python/PG | framework checkpoint 表 | 使用锁定插件的 schema；不能手写不兼容结构，lease 适配独立测试 |
| Python/PG | document/version/job/chunk | tenant、ACL 引用、版本、状态、页码、hash、embedding/indexRevision |
| Python/PG | memory_fact | actor、factType、value、sourceMessageId、consent、version、expiresAt |
| Python/PG | eval_dataset/case/run/result | datasetVersion、fixtureVersion、配置 hash、结果、graderVersion、人工标注 |
| Python/PG | runtime_outbox | 运行/文档事件的可靠通知；与 MySQL outbox 区分所有者 |

同一个业务事实只归一个表/服务所有。完整历史在 Python/PG，Java 只保存会话元数据和授权；这意味着迁移时需要明确导入历史和代理查询，不能让两边都接收同一条消息的权威写入。

### 14.2 目录演进

不要求立即把现有两仓库合成 monorepo。建议保留后端仓库和前端仓库，后端仓库新增 Python 子目录及契约/部署资源，形成一个发布单元：

~~~text
后端仓库/
  src/main/java/com/chy/ai/
    identity/ conversation/ course/ reservation/
    approval/ toolgateway/ tasksubmission/ shared/
  agent-runtime/
    pyproject.toml / uv.lock
    app/api/ domain/ graphs/ nodes/ tools/ retrieval/
    context/ persistence/ workers/ evaluation/ observability/
    tests/
  contracts/openapi/ events/ tool-schemas/
  migrations/ fixtures/ evals/ benchmarks/
  deploy/compose/ helm/ monitoring/
  docs/architecture/ adr/ experiments/ runbooks/

前端仓库/src/
  features/workspace/ runs/ knowledge/ approvals/ evaluations/
  stores/ composables/ services/generated/ components/
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

当前仓库旧测试可能真实计费并写向量索引，应从默认 test profile 移到显式 opt-in 的 live profile。不要直接把它们接到每次 PR 的 CI。

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

### 17.1 三种部署 profile

| Profile | 组成 | 用途 |
|---|---|---|
| core | Vue/反向代理、Java、Python API/worker、MySQL、PG、Redis、OSS 接口 | 主业务演示；任务调度初期可 DB polling |
| reliable | core + RabbitMQ、多 worker | 可靠投递、租约接管、故障演练 |
| observability/search | 按需开启 Langfuse/OTel/指标、Elasticsearch | 全链路评测与检索对照 |

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

## 18. 研发顺序、工作量与迁移

### 18.1 按里程碑推进，不一次推倒重写

工时为单人有效研发的粗估，含主要测试/文档；会 Python/异步/数据库的程度影响很大。基础阶段约 **440–632 小时**，加约 20% 学习与返工缓冲约 **530–760 小时**。每周 20 小时约 27–38 周，每周 35 小时约 15–22 周；可选训练/沙箱等不含在内。

时间不限意味着可以做深，不意味着等完整平台做完才投递。调研日已经是 2026 年 9 月，应在首个里程碑后就用已完成的版本准备面试，后续持续更新。

| 阶段 | 依赖 | 预计工时 | 开发内容 | 退出条件 |
|---|---|---:|---|---|
| M0 基线与止损 | 无 | 16–24 | 固定旧版、整理配置与测试 fixture、修上传权限/过滤/假成功、归档完整消息方案；完成百炼单 Key 的聊天/Embedding 迁移与冒烟 | 双用户隔离、旧页面可用；仅提供百炼模型 Key 即可通过 AI 启动及联调检查，baseline 能安全重复跑 |
| M1 首个可展示闭环 | M0 | 48–64 | Python 最小状态图、Java 查询/草稿/审批/幂等写接口、Vue 运行详情、标准 SSE | 查询→引用→批准→预约回执；完整消息入库；50 条冒烟集 |
| M2 知识工程 | M1 | 64–88 | 异步 ingestion、版本切换、结构解析、权限、混合召回/rerank 实验、页码引用 | B0–B3 数据对比；失败不暴露半成品；删除语义一致 |
| M3 可靠运行时 | M1 | 72–104 | outbox/inbox、队列、checkpoint、审批/input 恢复、租约与 fencing、取消/对账 | 核心故障场景通过；多 worker 承诺以 saver POC 为前置 |
| M4 评测与观测 | 从 M1 开始，M2/M3 扩充 | 56–80 | 300 样本、规则 grader、人工校准、Langfuse/trace、实验工作台 | 固定测试集的真实报告，可点击失败样本 |
| M5 上下文与协作 | M2/M4 | 56–80 | 记忆策略、双专家实验、MCP、受控 Skills、模型预算策略；百炼内多模型路由为可选实验 | 同条件质量/成本/延迟对比；有理由决定哪些功能默认启用 |
| M6 后端与运维 | M3/M4 | 64–96 | 压测、池与限额调优、索引/缓存、Compose/CI、备份恢复、K8s 实验 | 可重建部署、容量报告、恢复演练和 runbook |
| M7 打磨与求职证据 | 各阶段持续整理 | 64–96 | 前端稳定性/包体、文档、架构/实验文章、演示视频、面试材料、可选开源贡献 | 陌生人能启动和验证；两版简历内容均有证据 |

M0+M1 约 64–88 小时就能交付第一条纵向切片。建议先把主要能力做窄做通，再扩到多空间/多 worker；不要在 M1 同时建设完整工具市场、拖拽编辑器和训练平台。

### 18.2 从旧系统到新系统的迁移步骤

1. 保存 baseline tag/代码快照，导出测试 fixture，不改写原历史。后端与前端采用新分支迭代。
2. 增加 /api/v1 契约与 feature flag，先接新课程 Agent 页面；旧聊天功能继续使用原业务接口，但模型配置一并切换到百炼 `qwen3.7-flash`。移除目标运行配置对 DeepSeek Key 的强制依赖，保留 `text-embedding-v4` / 1024 维；变量更名与适配器注入按[模型配置迁移清单](./模型服务与API配置.md)实施。
3. 新消息进入独立归档表。旧 JDBC memory 中仍存在的消息可以导入并标明 legacy；已被窗口删除的内容无法凭空恢复，也不要伪造时间戳。
4. PDF 从 chatId 绑定迁往 document/version；验证用户归属、原文 hash 和可用对象，再重建向量。旧向量不直接混到新索引。
5. 将 Java CourseTools 的写方法收敛到统一命令服务，再让 Python HTTP/MCP adapter 使用同一接口，杜绝两条写路径。
6. 小范围内部体验新链路，用相同 fixture 对比；确认运行/权限/错误契约后逐页迁前端。
7. 可靠投递、checkpoint 和评测稳定后，停止新增旧接口调用；保留只读历史兼容期，再删除不用依赖与旧页面。

Shadow 对比仅调用只读工具或模拟器，不能让新旧 Agent 都真实创建预约。迁移回退允许新接口关闭和旧版只读访问；已有业务结果仍以真实业务表为准。

### 18.3 第一批可以直接转成 issue 的任务

| ID | 任务 | 验收 |
|---|---|---|
| P-01 | 上传资源归属、ID 与过滤白名单 | 两账号交叉上传/查询失败且无污染 |
| P-02 | 统一错误协议和前端上传状态 | Result.fail 不再显示 READY |
| P-03 | 正确的历史模型与迁移 fixture | 60 轮后早期消息仍能读取；窗口只影响 prompt |
| P-04 | OpenAPI 与 SSE schema | 前后端类型一致，半包/UTF-8/重复事件通过 |
| P-05 | run 创建与幂等接收 | 重复请求只产生同一 run，相同键不同 body 返回冲突 |
| P-06 | 查询工具 DTO 与有限 ReAct | 参数白名单，预算停止，无界查询被拒绝 |
| P-07 | approval + tool_execution 事务 | 参数篡改、过期、重复点击、响应丢失测试通过 |
| P-08 | 文档版本化 ingestion | 中间失败时旧版本可用，新版可重试 |
| P-09 | PostgresSaver 与恢复 POC | 进程重启回到已持久化节点；无重复业务效果 |
| P-10 | 审批/input 唤醒与取消 | 等待过程中重启仍能恢复，取消状态可观测 |
| P-11 | 50 条初始评测和 B0 报告 | 数据/配置固定，真实记录成功与失败 |
| P-12 | 统一 Vue run store | 切页/刷新/重连不串消息，结果以业务事件为准 |

M3 的多 worker fencing 应单独建研究型 issue，先证明事务适配可行再承诺期限。复杂点提前做小实验，比最后才发现框架语义不支持更节省时间。

## 19. 如何把工程成果变成面试亮点

### 19.1 3–5 分钟演示

| 时间 | 演示 | 对应能力 |
|---|---|---|
| 0:00–1:15 | 带约束提问→检索与工具→两套方案→点击 PDF 页码 | Agent/RAG、证据与业务抽象 |
| 1:15–2:00 | 显示预约草稿→明确确认→真实预约号 | 审批、工具 schema、服务端权限 |
| 2:00–3:00 | 在预设故障点杀 worker 或模拟提交响应丢失→恢复→仍是一单 | checkpoint、幂等、事务边界 |
| 3:00–4:00 | 打开固定评测集的版本对比与失败案例 | 实验、质量/成本取舍与归因 |
| 4:00–5:00 | 两用户隔离/恶意资料测试，查看跨栈 trace | 后端安全、可观测与工程完整性 |

故障注入只能在显式 demo/test 环境开放，避免把 kill/篡改 fixture 的入口放进正常业务页面。准备预录视频和离线 mock provider，以应对现场网络不稳定；清楚标明录像与 mock。

### 19.2 两种简历写法，完成后再填写

项目名建议：**企业知识与业务办理 Agent 平台**。副标题可以保留原项目名，说明是在现有系统上持续研发。

**Agent 岗版本示例：**

> 基于 Java 与 Python 构建课程咨询和预约 Agent，设计有界执行图、上下文管理、混合检索和证据引用；通过持久化 checkpoint 与服务端审批支持任务中断恢复。构建 [N] 条版本化业务评测任务，对比 [策略 A/B]，任务成功率由 [实测 A] 提升至 [实测 B]，单成功任务成本为 [实测值]。

**后端岗版本示例：**

> 负责 Agent 业务后端及任务可靠性设计，采用 outbox/inbox、数据库幂等约束和审批参数绑定，处理重复投递及“业务已提交、运行时未保存”的恢复场景；通过 [N] 组故障注入验证同一动作无重复业务效果，并在 [硬件/负载] 下测得核心 API p95 [实测值]。

只保留自己已实现、理解且有报告支持的内容。不能把表中的目标换进方括号，更不能写真实用户数/生产流量/收益等未经发生的经历。

### 19.3 面试官可能深入追问的 12 个问题

1. 为什么用 Agent，哪些步骤必须是确定性 Workflow？
2. 为什么选 Java + Python，代价是什么，为什么不全部 Java？
3. checkpoint 能恢复什么？为什么仍可能重复调用写工具？
4. actionId 如何跨重试稳定？与 tool_call_id 有何区别？
5. 审批后参数或库存变了怎么办？取消时已经提交怎么办？
6. 为什么 Redis 锁或消息 ACK 不能单独保证业务幂等？
7. 多 worker 租约过期后，旧 worker 写 checkpoint 怎么处理？
8. 文档撤权、版本更新和向量索引切换怎样不暴露半成品？
9. dense、BM25、reranker 分别解决什么，哪些查询没有改善？
10. 多 Agent 比单 Agent 多花多少成本，什么时候应关闭？
11. 成功率的分母是什么？为何不能只让 LLM 自评？
12. 性能瓶颈在模型、排队、数据库还是渲染？如何从 trace 证明？

准备答案的方式是关联一张架构图、一段核心实现、一个失败案例和一份实测报告，而不是背框架术语。

### 19.4 最终应交付的工程资产

- 可部署的前后端与 Agent runtime、受控演示账号/fixture、环境样例与初始化脚本。
- OpenAPI、事件与工具 schema、数据库迁移、关键 ADR、权限与状态机设计。
- 数据集说明、baseline/消融报告、错误样例、性能与故障报告。
- CI 记录、可重复的测试命令、运行手册、备份恢复记录。
- 3–5 分钟演示视频、简短项目说明，以及能展示核心流程的截图。
- 一到两篇有代码与实验支撑的技术文章，例如“业务提交与 Agent checkpoint 不一致时如何恢复”“中文业务 RAG 的失败归因与消融实验”。

求职准备持续覆盖 Java 并发/JVM、MySQL 索引事务、Redis、MQ、HTTP/SSE、Python asyncio、数据结构算法。该项目能帮助解释真实问题，但不会替代基础笔试与面试准备。

## 20. 配套调研、审计和阅读顺序

建议先读本文第 1、5、6、18、19 节确认产品和投入，再按研发模块读其余章节。

- [2027 岗位调研：8 个官方 2027 完整 JD 与 5 个实习/社招参照](2027岗位调研.md)
- [后端现状审计：全量源码与准确位置](后端现状审计.md)
- [前端现状审计：契约、状态、构建验证与位置](前端现状审计.md)
- [技术资料与开源项目阅读清单](技术资料与开源阅读清单.md)
- [模型服务与 API 配置：百炼单 Key 决策、迁移与验收](./模型服务与API配置.md)
- [中间件与基础设施清单](./中间件与基础设施清单.md)

研究结论的边界：招聘样本不是市场普查；官方开源不等于公司全部采用；main 分支与在线文档可能更新；静态风险不等于已经复现；目标架构与指标不等于现有实现。研发时按锁定版本、实际实验和真实业务约束继续修订。
