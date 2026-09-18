# 模型服务与 API 配置

决策日期：2026-09-18。状态：已接入 Java 与独立 Python 服务，并通过真实聊天及 1024 维 Embedding 冒烟。整链路验证结果持续记录在[开发与验证记录](./开发与验证记录.md)。文档不保存真实密钥。

## 1. 已确定的选型

| 用途 | 服务与模型 | 配置约束 |
|---|---|---|
| 日常 Agent | 阿里云百炼 `qwen3.7-flash` | 对话、规划、工具调用、摘要及子 Agent 默认使用同一模型 |
| Embedding | 阿里云百炼 `text-embedding-v4` | 保持 1024 维；文档与查询使用同一模型、维度与预处理版本 |
| 凭据 | 一把百炼 API Key | Agent 与 Embedding 使用同一地域及相应工作空间权限的 Key；运行前验证两种模型均可访问 |
| 重排 | 可选本地 BGE reranker | 默认关闭，B3 实验验证收益与资源开销后再启用；不新增第三方模型 Key |
| 评测 judge | 首版复用 `qwen3.7-flash` | 单独统计评测费用，以规则、真实业务状态和人工校准补足同模型评审偏差 |

默认 AI 运行链路不依赖 DeepSeek、硅基流动、智谱或其他厂商凭据。后续多模型路由是百炼内的可选实验，不改变日常默认模型，也不作为核心版完成条件。LangGraph 多 Agent 实验可以让多个角色调用同一个模型，不要求多个模型厂商。

“一把 API Key”指 AI 模型服务。项目保留的阿里云 OSS 仍使用独立 AccessKey；数据库、消息队列和观测系统的凭据按各自用途管理，不能用百炼 Key 替代。

官方依据：[qwen3.7-flash 模型说明](https://help.aliyun.com/zh/model-studio/qwen3-7-flash)、[百炼向量化文档](https://help.aliyun.com/zh/model-studio/embedding)。模型权限、SDK 参数及接口能力以实施时的文档和实际冒烟结果为准。

## 2. 当前配置与目标配置

改造前后端以 DeepSeek 为聊天默认提供方，并有 `qwen3.7-max` 配置。改造后 Java 默认关闭旧 AI 入口，独立 Python 项目 [intelligent-agent-runtime](https://gitee.com/chy66666/intelligent-agent-runtime.git) 统一读取 `qwen3.7-flash` 和 `text-embedding-v4` / 1024 维。当前百炼 Key 可继续来自已有 Windows 环境变量 `API-KEY`。

规范环境变量名为 `DASHSCOPE_API_KEY`，Python 和 Java 保留 `API-KEY` 兼容读取。本机 Compose 通过当前进程将已有 Key 映射为规范名传入容器，没有删除系统中的旧变量，也无须申请第二把 Key。

以下配置已由现有程序读取；真实密钥只在服务端注入：

~~~dotenv
DASHSCOPE_API_KEY=<your-bailian-api-key>
AI_PROVIDER=bailian
AI_CHAT_MODEL=qwen3.7-flash
AI_EMBEDDING_MODEL=text-embedding-v4
AI_EMBEDDING_DIMENSIONS=1024
AI_RERANK_ENABLED=false
~~~

接入地域统一采用百炼北京地域。聊天与 Embedding 分别建立客户端，注入同一密钥；具体 base URL 与路径按所选 SDK 配置，避免重复拼接 `/v1`。保留独立的超时、并发、重试和预算设置，共用 Key 不代表共用一个不受限的调用队列。

密钥仅由服务端环境或部署 Secret 注入，不进入 Vue 的 `VITE_*` 变量、前端请求、Git、日志、trace 或评测数据。前端只调用本项目业务 API。

## 3. 实施阶段迁移清单

1. 保留旧版配置快照与隔离测试数据，创建目标环境配置模板；示例文件只含占位符。
2. 把现有百炼环境变量引用从 `API-KEY` 统一迁移到 `DASHSCOPE_API_KEY`；同步 Java、Python、Compose/CI 示例与启动说明，完成后检查旧变量引用。
3. 将所有仍需运行的聊天入口和 ChatClient/ChatModel 注入切换到百炼 `qwen3.7-flash`，检查 DeepSeek 自动配置、显式 Bean 与默认 provider，确保缺少 DeepSeek Key 不会阻止目标环境启动。
4. 保持 Embedding 模型与 1024 维不变，确认查询和文档入库读取同一配置。仅变更聊天模型不要求重建向量；若切块、预处理、模型、维度或存储格式变化，按新 indexRevision 迁移与验证，不能直接混用新旧向量。
5. 新建 Python 百炼适配器并复用同一 Key；工具执行仍通过 Java 的审批、鉴权与幂等接口完成。
6. Ragas/评测客户端显式指定百炼模型与 Embedding，避免工具库默认索取另一家模型 Key。规则 grader、业务状态检查和人工校准继续作为主要验收依据。
7. 默认关闭外部 rerank 和跨厂商路由；本地重排仅在实验 profile 中开启。记录实际模型、维度、prompt、适配器与计价版本。

## 4. 单厂商下的可靠性与成本

- 对限流和暂时性服务错误设置带抖动的退避、最大尝试次数及整个 run 的截止时间；遵守 Retry-After，鉴权或参数错误不盲目重试。
- 首版没有跨厂商备用。熔断或预算耗尽后保存运行状态并给出明确错误与重试入口；异步入库按作业策略恢复，旧 READY 文档版本继续可查。
- 已输出部分内容的流失败后结束当前消息或创建新 revision，不把重试内容直接接到旧回答。工具写入是否成功先查业务结果，再按幂等规则恢复。
- 分开统计在线 Agent、摘要、子 Agent、Embedding 和 judge 的 usage；所有重试和计费思考 Token 纳入总账，共用 Key 也能在应用内按用途归因。
- 费用采用调用时的地域、模型、上下文档位、缓存和计价版本计算；缺失 usage 显示 unknown，不当作零。模型定价快照与真实任务测量分开记录，不把厂商标价当作业务效果证明。

## 5. 验收条件

- 在独立测试进程仅提供一把百炼模型 Key，并提供必要的基础设施凭据，不配置 DeepSeek/OpenAI/其他模型厂商 Key，Java 与 Python 的目标 profile 可以启动。
- 真实小样本冒烟覆盖普通响应、流式结束、Function Calling、结构化参数与 usage；模型 Key 只由服务端使用。
- 文档与查询 Embedding 均得到 1024 维结果，固定检索样本可返回授权文档的正确引用。
- 通过模拟 401、429、暂时性错误、超时和流中断验证错误分类与有界恢复；重复执行不产生第二笔预约。
- 规则评测可离线执行；启用 LLM judge 时只需同一百炼 Key，报告明确同模型评审及人工校准情况。
- 默认 CI 使用 mock/fixture；真实计费调用作为显式启用、带预算的小样本检查，结果单独记录。

关联文档：[主研发方案](./2027-AI-Agent-改造研发方案.md)、[后端现状审计](./后端现状审计.md)、[中间件与基础设施清单](./中间件与基础设施清单.md)、[技术资料与开源阅读清单](./技术资料与开源阅读清单.md)。
