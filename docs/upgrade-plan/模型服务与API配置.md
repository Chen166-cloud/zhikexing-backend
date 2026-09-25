# 模型服务与 API 配置

更新日期：2026-09-25。当前 Java/Python 服务使用百炼模型配置，Vue 不持有模型凭据。具体运行状态以实际部署与 `/health` 输出为准；常用完整 Compose 尚未按新版重建验收。

| 用途 | 当前配置 | 边界 |
|---|---|---|
| Agent 聊天与工具选择 | 阿里云百炼 `qwen3.7-flash` | Python Runtime 负责模型调用；Java 复核业务权限和审批，不相信模型自然语言的业务结论 |
| Embedding | `text-embedding-v4`，1024 维 | 文档与查询使用一致配置；修改维度或预处理需重新建索引 |
| 凭据 | 一把 `DASHSCOPE_API_KEY` | 仅指模型厂商凭据；MySQL、RocketMQ、MinIO、观测系统仍使用各自凭据 |
| 可选重排与评测 | 本地 BGE reranker 默认关闭；评测优先规则和人工校准 | 启用前记录质量收益、资源与成本；不能把未测实验写成当前效果 |

```dotenv
DASHSCOPE_API_KEY=<your-bailian-api-key>
AI_PROVIDER=bailian
AI_CHAT_MODEL=qwen3.7-flash
AI_EMBEDDING_MODEL=text-embedding-v4
AI_EMBEDDING_DIMENSIONS=1024
AI_RERANK_ENABLED=false
```

服务端兼容读取本机已有的 `API-KEY` 环境变量；推荐在 Compose 当前进程映射成规范名。不要把 Key 写入 Git、Vue 的 `VITE_*` 配置、浏览器请求、日志或评测样本。聊天与 Embedding 在同一百炼地域运行，客户端路径应正确包含兼容接口 `/v1`。调用仍需单独设置超时、并发、有限重试和预算；共用 Key 不意味着无限配额。

测试使用显式 `AI_PROVIDER=fixture` 才会替换真实模型。试听模块的 Java/MySQL/Redis/RocketMQ 实际中间件集成及 Python fixture 跨服务演练已通过，但新增试听工具尚未完成真实模型选工具验收；普通业务已有真实模型的结果不能直接推广到新图。所有费用与用量应区分真实记录、估算和未知值。官方依据：[百炼 Qwen 模型说明](https://help.aliyun.com/zh/model-studio/qwen3-7-flash)、[百炼 Embedding 文档](https://help.aliyun.com/zh/model-studio/embedding)。

关联：[接口契约](研发接口契约.md)、[试听模块验证记录](../modules/免费试听秒杀验证记录.md)、[Docker 部署文档](../deployment/Docker部署文档.md)。
