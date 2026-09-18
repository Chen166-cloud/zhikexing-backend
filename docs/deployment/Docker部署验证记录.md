# Docker 部署验证记录

日期：2026-09-18。以下结果来自本机真实执行和读取反馈，不是研发方案目标值。应用链路的独立功能测试见研发验证报告。

## 环境与迁移

- Windows 主机：32 逻辑 CPU、约 32 GB RAM。
- 实际引擎：Ubuntu-22.04 / WSL2，Docker Engine 29.8.1，Compose 5.5.1；引擎可见约 15.43 GiB RAM。
- Docker Desktop 4.41.2 原本启动失败：`dockerInference` socket `ERROR_CANT_ACCESS_FILE`；自动审批拒绝了删除该 socket 的命令，实际没有执行删除或恢复出厂设置。
- 改用独立 WSL Engine 后，首次拉取遇到 C 盘空间不足，ext4 进入 `emergency_ro`。立即停止发行版；获得用户明确批准后，通过官方 `wsl --manage Ubuntu-22.04 --move` 将发行版迁至 `E:\WSL\IIIP-Ubuntu-22.04`。
- 迁移前确认源 VHD 路径为 `C:\Users\Chen\AppData\Local\wsl\{4bb9ef0d-756e-4c6f-b0bc-fc05569b9940}\ext4.vhdx`，目标为 E 盘的新专用目录。首次因 VHD 句柄占用失败；确认无运行发行版后 `wsl --shutdown`，再迁移成功。
- 迁移后注册路径与 VHD 存在性吻合，C 盘恢复约 14.35 GB 可用空间，Ubuntu 根挂载恢复 `rw`。没有删除用户既有数据库、Docker Desktop 卷或 Ubuntu 数据。

## 中间件真实结果

| 组件 | 版本 | 验证与结果 |
|---|---|---|
| MySQL | 8.4.8 | 容器 healthy；使用项目账号成功连接和 `SELECT VERSION()` |
| PostgreSQL / pgvector | 17.10 / 0.8.5 | healthy；创建临时 `vector(1024)` 表、写入向量、维度返回 1024、自身余弦距离 0；事务回滚 |
| Redis | 8.2.1 | healthy；PING=PONG；带过期时间 SET/GET 内容一致，验证键已删除 |
| RabbitMQ | 4.3.6 | healthy；管理 API 创建持久临时队列，发布持久消息 routed=true，消费内容一致；临时队列已删除 |
| MinIO | Chainguard 镜像 digest 锁定 | healthy；两个 bucket 初始化成功；真实对象上传、读取和删除成功 |
| Langfuse PostgreSQL / Redis | 17.10 / 8.2.1 | 两个独立实例 healthy，Web/Worker 完成数据库及后台迁移 |
| ClickHouse | 25.12 系列 | healthy；已建 Langfuse 分析表；从 `events_full` 读回两条合成验证 span |
| Langfuse Web / Worker | 4.38.0 | Web healthy，健康 API 200 且版本 4.38.0；Worker 持续运行并处理 OTLP 数据 |
| Prometheus | 3.14.0 | healthy；`/-/ready` 返回 200；Java、Runtime、自身、RabbitMQ、OTel 共 5 个抓取目标均 UP |
| Grafana | 13.2.2 | healthy；`/api/health` 返回 200；加载预置 Prometheus 数据源与 IIIP 看板 |
| OTel Collector | 0.161.0 | 健康端口 13133 返回 200；OTLP HTTP/gRPC 接收器启动；Prometheus 指标端点可抓取 |

实际拉取 digest 保存于[中间件镜像锁定](./中间件镜像锁定.json)。共 13 个持续运行的中间件容器，加 1 个执行成功后退出的 `minio-init`；数据库和缓存为不同使用方隔离，因此容器数量多于组件类型数量。

Langfuse 使用应用容器中的 OpenTelemetry exporter 向 `/api/public/otel/v1/traces` 发送合成 span，返回 `SUCCESS`，随后通过 ClickHouse 查询到 `docker-deployment-verification` 和 `docker-deployment-verification-v4` 各 1 条。没有调用大模型，也没有发送用户内容。两条验证观测保留用于查看本次部署是否贯通。

业务链路随后真实调用百炼。审批验收运行 `b97b9b1b-940e-4a78-bd4a-1bd10f292fad` 在 `events_full` 中查询到 5 条 `GENERATION`，模型均为 `qwen3.7-flash`，汇总输入 5458、输出 243、总计 5701 tokens。所有记录的 `input_length` / `output_length` 均为 0，模型用量已关联到运行，提示词和回答正文没有送入观测系统。该结果与前述合成部署探针分别验证。

启用两端 RabbitMQ 后，联合验收完成真实百炼 RAG 的 10 项检查。对同一任务再次投递两条持久消息，均确认路由并被消费；模型调用用量保持不变，会话历史没有重复，验证 outbox / inbox 的重复投递处理。

最终 v3 部署后的真实百炼验收结果：

- RAG：运行 `e2e30f59-f5d2-45bf-b565-cc7a3320cea3` 的 10/10 项检查通过，图版本 `react-approval-v3`，总 token 为 2026，聊天费用估算为 0.0004586 元；该数值是聊天估算，不代表包含 Embedding 的完整账单。
- 取消：运行 `faf6a6ad-1c7c-4d09-8194-38bd3ac6f3dc` 的 5/5 项检查通过，关联 action 为 `7cd27ed1-9b50-5677-9c31-39a079bdad77`。真实 MySQL 按该运行核对：`writesBlocked=1`、动作记录数 0、预约记录数 0、取消命令数 1，取消命令投递状态为 `DELIVERED`。此前验收生成的两笔预约不受影响。

## 实际反馈与修复

1. `minio/mc` 公共仓库拉取失败。按当前 Langfuse 官方 Compose 改为 Chainguard MinIO，使用同一镜像内的 `mc` 初始化 bucket，并固定拉取 digest。
2. C 盘满时留下的两个 PG 镜像快照中出现 0 字节程序/入口文件，导致 `exec format error`。检查确认不是 CPU 架构错误；最终选用不同快照链的固定镜像 `pgvector:0.8.5-pg17` / `postgres:17.10-alpine`，两个数据库成功初始化。未改动其数据卷。
3. Langfuse 独立服务器默认使用容器 HOSTNAME，宿主可访问但容器内 127.0.0.1 健康检查失败；显式设 `HOSTNAME=0.0.0.0` 后 healthy。
4. PowerShell 高级参数会吞掉 Compose `-d`，WSL 默认 shell 还会提前展开内部 `$变量`；包装脚本已改为普通 `$args` 透传和 WSL `--exec`，后台启动、内部 Redis/MySQL 密码环境展开均已实测。
5. RabbitMQ 4.3 默认拒绝非持久、非独占临时队列；协议测试使用持久队列后发布/消费成功，不修改服务端弃用开关。
6. Langfuse 4 不再接受旧 `trace-create` ingestion 事件；改用 OTLP。实时 v4 数据另加 `x-langfuse-ingestion-version: 4` 请求头，依据[官方 OpenTelemetry 说明](https://langfuse.com/integrations/native/opentelemetry)。
7. 最后一个 WSL 构建/终端会话结束后，发行版自动停止，全部容器随下次命令重新启动，浏览器短暂 `ERR_CONNECTION_REFUSED`。日志未见 OOM；新增隐藏普通会话的 `Start-WslEngine.ps1` 保持发行版运行，遵循[微软说明](https://learn.microsoft.com/en-us/windows/wsl/systemd)的 systemd 生命周期边界。
8. Nginx 原本使用 `$host` 丢失入口端口，改成 `$http_host` 保留 `:8088`；真实浏览器注册与携带 Origin 的 OPTIONS 已通过。Java 同时统一六个本地开发/部署 Origin 白名单。

## 运行图镜像归档

运行图 v1 的已验证 Runtime 镜像保留为 `iiip-agent-runtime:graph-v1-20260918`，image ID 为 `sha256:01a5535d7aa3d13765eb318972c6f2f193b1da4483583a33764cf87d42f32195`。此标签来自当时实际运行容器，防止后续构建覆盖 `latest` 后丢失原图实现。

运行图 v2 已构建并另存为 `iiip-agent-runtime:graph-v2-20260918`，image ID 为 `sha256:2b68e9883533fdd188dc500e6c700acec842a4d2e4a6ead4264496b674663fd6`。

当前图版本为 `react-approval-v3`，镜像已构建并归档为 `iiip-agent-runtime:graph-v3-20260918`，image ID 为 `sha256:2609d078ea60d8ce84c74d9e3db96359bcc054b1f6a0013594cad4c97726a295`。三版镜像均保留在本地引擎。v3 对应 Python 37 项 PostgreSQL 回归通过后的源码，Java 同时包含已通过真实数据库测试的课程名称空白归一化。

## 一致性备份与独立恢复

实际执行 `deploy/backup.sh backups/20260918-restore-validation`，暂停应用写入后备份 MySQL、PG、Redis、MinIO、RabbitMQ 和 Runtime 本地卷，随后原应用恢复 healthy。六份数据文件的 SHA256 全部校验成功；备份目录被 Git 和 Docker build context 排除。

恢复使用独立 Compose 项目 `iiip-restore-verification`、新命名卷和不映射宿主端口的五个核心服务，没有覆盖原卷。结果如下：

- MySQL：课程 3、运行登记 5、审批 2、预约 2；API 验收 action `b245a982-f27b-5250-bef2-ac61373e5571` 对应预约 1、审批 EXECUTED，关联计数为 1；浏览器生成的预约 2 同时保留。
- PG：运行 5、文档 2、知识片段 3；上述 API 验收运行仍为 SUCCEEDED；恢复 checkpoints 33、checkpoint_blobs 53、checkpoint_writes 121，pgvector 0.8.5 可用。
- S3：两份活跃文档重新下载后的 SHA256 与 PG 保存的 content_hash 全部一致。
- Redis：RDB 恢复后的指定临时键读回 `snapshot-ok`。
- RabbitMQ：固定 hostname `rabbitmq` 后冷备份，新 Broker 从卷快照恢复 1 条持久消息，实际消费得到 `snapshot-ok`、delivery_mode=2。

恢复测试的五个容器、新卷、网络、临时脚本均已删除，原环境的验证键、队列和对象也已删除。备份中保留这些合成标记用于复核，原环境的用户、两笔预约和业务文档没有删除。此项覆盖本机独立卷恢复；跨机器灾备及 Langfuse/ClickHouse 观测历史恢复仍未验证。

## 资源观测与边界

在镜像构建期间的一次空闲业务采样，13 个中间件容器内存合计约 3.60 GiB；Langfuse Web/Worker 各约 0.76 GiB，ClickHouse 约 0.65 GiB，MySQL 约 0.45 GiB。该采样不是压力测试、容量承诺或生产负载数据。

全部中间件的健康与实际协议检查已通过。Java（Boot 4.1.1 / Spring AI 2.0.1）、独立 Python Runtime、Vue/Nginx 三项镜像已实际构建并启动，均 healthy；入口 8088 返回 HTTP 200，Flyway V1/V2/V3/V4 均成功，Runtime 自报真实百炼、1024 维 Embedding 与 PostgreSQL。两端已启用 RabbitMQ，实际命令队列持久化、消费者数为 1，失败队列为空。16 项运行容器的另一次采样合计约 4.18 GiB。业务链路联调结果另见研发验证报告。高可用、长期压测、跨机器灾备和 ClickHouse 原生备份恢复不在本轮已验证结果中。

新增临时缓存诊断脚本已删除。部署/初始化/备份脚本是交付的运维功能，保留以便重建环境；临时数据库、队列、键和对象按各项验证记录清理，不删除既有用户资源。

## 2026-09-19：跨机器路径配置验证

三个项目 README 已改为 Gitee 仓库地址和同级克隆说明。源码、构建与 CI 路径审计发现问题集中在初始化脚本覆盖了相对路径；现已保留 `../intelligent-agent-runtime` 和 `../web-intelligent-integrated-interaction-platform` 默认值，允许通过本机 `.env` 或初始化参数指定其他布局。WSL 使用实际路径转换与可选发行版配置。

| 验证方式 | 实际结果 |
|---|---|
| 原生 Compose 2.35 / WSL Compose 5.5 的真实 `config` 解析 | 同级默认目录、不同仓库目录名、包含空格的目录、自定义相对/绝对路径均正确解析 |
| 特殊字符与 WSL 路径 | `$`、`#`、单引号不被 dotenv 意外展开或截断；Windows 路径通过 wslpath 转换，默认/指定发行版均通过 |
| 隔离命令转发与进程 API 检查 | `--profile app up -d` 原样交给 Compose；启动脚本保持隐藏会话、去重和发行版选择 |
| 本地配置保护 | 现有 `.env` 的文件 hash 未改变；没有执行镜像构建或容器重启 |
| 文档与源码复核 | 三份 README 均包含三个指定仓库地址且无宿主项目绝对路径；205 处仓内/跨仓源码链接对应的本地目标存在 |

本轮 15 个临时配置/脚本副本及其隔离验证目录均已删除。上述验证检查路径和配置的可移植性，没有声称已经在另一台实体电脑完成全量部署；远程仓库是否已上传由后续提交/推送状态决定。
