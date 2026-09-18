# Docker 部署与运维

本文对应仓库根目录 `docker-compose.yml`。AI 模型统一使用百炼 `qwen3.7-flash` 和 `text-embedding-v4`（1024 维），只需要一个 `DASHSCOPE_API_KEY`。数据库、内部服务与对象存储凭据由初始化脚本分别随机生成。

## 1. 组成与端口

默认启动核心中间件，`observability` 增加观测服务，`app` 增加 Java、Python 和 Vue/Nginx。全部宿主端口绑定 `127.0.0.1`，服务之间使用 Compose DNS。原本机 MySQL 3306 和 Redis 6379 不受影响。

| 服务 | 宿主入口 | 容器内地址 | 数据职责 |
|---|---|---|---|
| MySQL | 13306 | mysql:3306 | 登录、权限、课程、预约、审批、outbox |
| PostgreSQL + pgvector | 15432 | postgres:5432 | Agent 状态、消息、知识、向量、评测 |
| Redis | 16379 | redis:6379 | 登录态、缓存 |
| RabbitMQ | AMQP 15673，管理 15672 | rabbitmq:5672 | 可靠消息投递基础设施 |
| MinIO | S3 19000，控制台 19001 | minio:9000 | 文档与 Langfuse 对象；不同 bucket 隔离 |
| Langfuse Web / Worker | Web 13000 | langfuse-web:3000 | 模型轨迹与评测观测 |
| Langfuse PostgreSQL / Redis | 不映射 | langfuse-postgres:5432 / langfuse-redis:6379 | 与业务库、登录态独立隔离 |
| ClickHouse | 不映射 | clickhouse:8123 / 9000 | Langfuse 分析存储 |
| Prometheus | 19090 | prometheus:9090 | 指标采集与保留 15 天 |
| Grafana | 13001 | grafana:3000 | 预置 Prometheus 数据源和 IIIP 看板 |
| OTel Collector | HTTP 14318，gRPC 14317，健康 13133 | otel-collector:4318 / 4317 | OTLP 接收与指标出口 |
| Java | 18080 | backend:8080 | 外部 API、认证授权、业务工具 |
| Python Runtime | 18000 | agent-runtime:8000 | 内部 Agent API 和持久化作业执行 |
| Vue / Nginx | 8088 | frontend:80 | 工作台、同源 API、SSE 反向代理 |

默认 `RABBITMQ_ENABLED=true`，Java outbox 向持久 exchange `iiip.agent` 投递，Python 消费 `iiip.agent.commands`；失败队列为 `iiip.agent.failed`。Java 等待 publisher confirm 后标记投递成功，Python 先持久接收再确认消息。设为 `false` 可显式使用 HTTP 投递模式，两个应用必须使用同一开关。模型遥测经 OTLP 接入 Langfuse，实际链路验证见验证记录。Elasticsearch、LiteLLM、vLLM、Temporal 属于方案按需扩展项，本部署不默认安装。

## 2. 环境要求与本机引擎

建议为完整应用与观测预留 8 vCPU、16 GB 内存和至少 30 GB 可用磁盘，首轮镜像下载需要网络。此值是启动规划，实际资源观察见部署验证记录。WSL 的 `df` 显示虚拟磁盘上限，不能代替宿主盘可用空间；启动前同时检查 PowerShell `Get-PSDrive -PSProvider FileSystem` 和 WSL 的 `df -h`。Linux 使用 Docker Engine 和 Compose 插件，Windows 可使用 Docker Desktop Linux 容器或 WSL 内独立 Docker Engine。

本次机器的 Docker Desktop 4.41.2 启动时遭遇遗留 `dockerInference` socket 访问错误。没有执行恢复出厂设置，也没有清理 Desktop 卷；实际部署使用现有 Ubuntu-22.04 / WSL2 中独立安装的官方 Docker Engine。首次下载镜像时 C 盘不足，经用户明确批准后使用官方 `wsl --manage --move` 将整个 Ubuntu-22.04 保留迁移到 `E:\WSL\IIIP-Ubuntu-22.04`；注册路径和可写挂载已复核。该引擎的镜像、网络、卷位于 Ubuntu 发行版中，与 Docker Desktop 分开。Windows 的裸 `docker` 命令仍连接 Desktop，管理本部署请使用下方 `-Wsl` 包装脚本。

首次在新的 Ubuntu 环境安装引擎：

```powershell
wsl -d Ubuntu-22.04 -u root --exec bash /mnt/d/java/SpringAI/intelligent-integrated-interaction-platform/deploy/install-docker-ubuntu.sh
```

脚本按 [Docker 官方 Ubuntu 安装方式](https://docs.docker.com/engine/install/ubuntu/)配置官方 APT 源并启动 systemd 服务。已有 `dockerd` 时仅启动服务，不改装版本。恢复本次 WSL 引擎：

```powershell
./deploy/Start-WslEngine.ps1
./deploy/Compose.ps1 -Wsl version
```

微软明确说明 [systemd 服务不会保持 WSL 实例运行](https://learn.microsoft.com/en-us/windows/wsl/systemd)。`Start-WslEngine.ps1` 启动隐藏的普通 WSL 会话，避免最后一个构建/终端进程退出后发行版自动停止；Windows 重启后需重新执行。停止整套服务后，可在任务管理器关闭该 `wsl.exe` 会话；`wsl --terminate Ubuntu-22.04` 会同时停止该发行版里的其他工作，使用前先确认。

普通 Linux 在仓库根目录直接执行本文对应的 `docker compose ...` 命令；健康的 Docker Desktop 可去掉包装脚本的 `-Wsl`。

## 3. 首次启动

在后端仓库根目录执行：

```powershell
# 仅首次运行。已存在 .env 时拒绝覆盖，以保护当前数据库凭据。
./deploy/Initialize-Environment.ps1 -Wsl

# 结构校验不会输出插值后的密钥。不要把 docker compose config 完整输出贴到文档。
./deploy/Compose.ps1 -Wsl --profile observability --profile app config --quiet

# 下载并启动全部核心与观测中间件。
./deploy/Compose.ps1 -Wsl --profile observability pull
./deploy/Compose.ps1 -Wsl --profile observability up -d --wait
./deploy/Compose.ps1 -Wsl --profile observability ps
```

PowerShell 脚本要求 PowerShell 7。`.env` 已被 Git 忽略；脚本只写随机基础设施凭据，不复制模型 Key。前端、Java、Python 是三个独立项目。`FRONTEND_PATH` 指向实际前端仓库，WSL 使用 `/mnt/d/develop/web-intelligent-integrated-interaction-platform`，Docker Desktop 使用 `D:/develop/web-intelligent-integrated-interaction-platform`。`AGENT_RUNTIME_PATH` 指向独立 Python 项目，WSL 使用 `/mnt/d/develop/intelligent-agent-runtime`，Docker Desktop 使用 `D:/develop/intelligent-agent-runtime`。换机器时同步修改这两行；Python 项目需要自己的 Dockerfile 和依赖锁文件。

使用当前终端的 `DASHSCOPE_API_KEY`。若原系统仍使用 `API-KEY`，在当前进程做一次映射，不把密钥写进命令或文档：

```powershell
$env:DASHSCOPE_API_KEY = [Environment]::GetEnvironmentVariable('API-KEY')
./deploy/Compose.ps1 -Wsl --profile app --profile observability up -d --build --wait
```

如果已经配置了 `DASHSCOPE_API_KEY`，直接启动即可。包装脚本通过 WSLENV 转发当前进程的该变量；基础设施初始化不需要模型 Key。离线、确定性研发验证可显式设置 `$env:AI_PROVIDER='fixture'`，正式使用应恢复 `bailian`；fixture 结果不可记作真实模型效果。

`.env` 可配置 Runtime 运行限制：`AGENT_COST_LIMIT_CNY=0.10`、`AGENT_TOKEN_LIMIT=40000`、`AGENT_RUN_TIMEOUT_SECONDS=240`、`AGENT_MAX_PARALLEL=4`。费用限制在下一次模型调用前检查，单次调用可能超过剩余额度，并非云平台的硬计费上限。修改运行图、Prompt、工具 schema 或模型配置后，旧运行可能因版本不兼容拒绝续跑，应先结束或明确迁移存量运行再升级。

打开 `http://localhost:8088` 使用应用。Langfuse 初始账号为 `.env` 中 `LANGFUSE_ADMIN_EMAIL` 和 `LANGFUSE_ADMIN_PASSWORD`；Grafana 账号为 `admin`，密码对应 `GRAFANA_PASSWORD`；MinIO 对应 `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY`；RabbitMQ 账号 `iiip`，密码对应 `RABBITMQ_PASSWORD`。这些随机密码只在本机 `.env` 查看。

## 4. 数据初始化与持久化

Compose 项目名固定为 `iiip-agent`。每类服务使用专用命名卷，不挂载原 MySQL/Redis 数据目录。Java Flyway 管理业务迁移；PostgreSQL 初建卷执行 `deploy/postgres/001-vector.sql` 安装 vector 扩展，Python 负责运行时表结构。`minio-init` 创建 `iiip-documents` 和 `langfuse` 两个 bucket，重复启动保持幂等。

PostgreSQL、MySQL 的初始化密码变量仅对首次创建数据目录生效。已有数据后直接修改 `.env` 密码会造成认证失败；应先通过数据库管理命令轮换真实密码，再同步配置。不要用删除卷解决认证错误。

首次 Java 启动完成 Flyway 迁移后，可手动导入演示课程和校区。`deploy/demo-data.sql` 使用固定演示 ID，不会自动执行；只对本项目新建演示库使用：

```powershell
./deploy/Compose.ps1 -Wsl cp deploy/demo-data.sql mysql:/tmp/iiip-demo-data.sql
./deploy/Compose.ps1 -Wsl exec mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -uiiip iiip < /tmp/iiip-demo-data.sql'
```

该脚本不创建用户或预约。演示用户通过正常注册接口创建，实际预约需要用户在工作台批准。

暂停及恢复：

```powershell
./deploy/Compose.ps1 -Wsl --profile app --profile observability stop
./deploy/Compose.ps1 -Wsl --profile app --profile observability start
```

`down` 删除容器与网络但保留命名卷。日常操作不添加 `-v`，它会删除数据。升级前先备份，更新指定镜像版本后重建对应服务，观察迁移与健康状态；数据库迁移不能简单等同于镜像回滚。

运行图升级前还需保留原 Runtime 镜像。本机已经保留 `iiip-agent-runtime:graph-v1-20260918`，镜像 ID 见验证记录。恢复旧运行时，必须同时满足其模型、Prompt、工具和图版本配置；先排空不兼容的新运行，再通过 `AGENT_RUNTIME_IMAGE` 选取归档镜像，并使用 `up --no-build --pull never`，不要用 `--build` 覆盖归档标签。当前与归档镜像都只保存在本机，没有推送外部 registry。

## 5. 观测与 S3 配置

Langfuse 按[官方 Docker Compose](https://langfuse.com/self-hosting/deployment/docker-compose)及 [v4.38.0 配置](https://github.com/langfuse/langfuse/blob/v4.38.0/docker-compose.yml)部署 Web、Worker、PostgreSQL、Redis、ClickHouse、S3 依赖。业务 PG 与 Langfuse PG 为独立实例；Langfuse Redis 使用 `noeviction`，独立于登录态缓存。MinIO 使用官方配置当前采用的 Chainguard 镜像，并锁定实际拉取的 digest。

MinIO 是本地开发的 S3 兼容实现，不需要 OSS AccessKey。云环境业务文件可配置已验证的 S3 endpoint 或扩展 OSS adapter；阿里云 OSS 原生 SDK endpoint 不应直接作为 Langfuse S3 endpoint。模型 Key 的共用不代表数据库密码、OSS AccessKey 或内部 Token 可以合并。

Grafana 自动加载 IIIP 运行总览，涵盖抓取状态、Java 请求速率、JVM 堆内存和 RabbitMQ 队列。Prometheus 的 `/targets` 可以检查各 scrape endpoint；只启动中间件时 Java/Python 两项 DOWN 属于应用尚未启动，不能写成完整业务健康。Collector 通过 `/` 健康端口 13133 报告进程状态；当前 traces 输出为 basic 日志，长期模型轨迹由应用直接送 Langfuse。

部署到远程服务器时，按实际域名修改 `LANGFUSE_PUBLIC_URL`、`S3_PUBLIC_URL`，否则浏览器预签名地址会指向本机 localhost。Nginx 已关闭 SSE 缓冲、配置长连接超时，并通过 `$http_host` 保留浏览器入口端口。Java 的 `CORS_ORIGINS` 可完整替换默认白名单；默认允许 localhost 和 127.0.0.1 的 5173、5176、8088 端口。生产环境在统一入口配置 HTTPS，同时保持数据库与内部 Runtime 不对公网暴露。

## 6. 备份与恢复

在 WSL 的仓库根目录执行 `bash deploy/backup.sh`，脚本暂停三项应用写入，备份 MySQL、Agent PG、Redis，再停止 MinIO 和 RabbitMQ，备份它们的命名卷，退出时恢复对象存储、Broker 和应用。Broker 尚未进入 inbox 的消息不能仅靠数据库备份恢复。输出目录 `backups/<时间>`，包含 SHA256 校验文件；该目录不得提交。生产环境还需要异地副本、保留策略和加密存储。

本机 WSL 引擎由 root 管理，可直接从 PowerShell 执行；脚本退出前等待应用重新 healthy：

```powershell
wsl -d Ubuntu-22.04 -u root --cd /mnt/d/java/SpringAI/intelligent-integrated-interaction-platform --exec bash deploy/backup.sh
```

恢复必须先在新的 Compose 项目和新卷中演练，不能直接覆盖现有实例：

1. 为恢复环境准备新的 `.env`、项目名和宿主端口；先只启动空 MySQL、PG、MinIO、Redis。
2. 用 `docker compose exec -T mysql` 和 `mysql -uiiip iiip` 导入 `mysql.sql`，数据库密码通过容器内 `MYSQL_PWD` 提供。
3. 用 `docker compose exec -T postgres pg_restore -U iiip -d iiip_agent --no-owner` 导入 `agent.dump`；目标数据库必须为空。
4. 停止目标 MinIO/Redis，将对象快照与 `dump.rdb` 恢复到目标新卷；Redis 开启 AOF 时应按 Redis 官方持久化规则处理目标新目录，不能混用旧 AOF。`agent-data.tar.gz` 还保存 Runtime 本地 `/data`，应恢复到新的 `agent-data` 卷；标准部署使用 PostgreSQL + S3，本地卷不承担主业务持久化。
5. 停止恢复环境的 RabbitMQ，将同批次 `rabbitmq.tar.gz` 恢复到其新卷，保持 hostname `rabbitmq` 与原镜像版本一致，再启动 Broker 验证待处理消息。
6. 启动应用，核对文档 hash/读取、运行历史、审批与预约记录，运行只读验收。确认恢复结果后再规划正式切换。

观测历史额外备份 `langfuse-postgres` 与 ClickHouse。先暂停 `langfuse-web` / `langfuse-worker` 的写入，再用 `pg_dump -U langfuse -d langfuse -Fc` 备份关系库；ClickHouse 需配置专用备份磁盘后使用其原生 `BACKUP`/`RESTORE`，并与 `langfuse` bucket 快照保持同批次。业务恢复可以先于观测历史，具体恢复演练是否完成见部署验证记录。

## 7. 排障命令

```powershell
./deploy/Compose.ps1 -Wsl --profile app --profile observability ps -a
./deploy/Compose.ps1 -Wsl logs --tail 80 backend agent-runtime
./deploy/Compose.ps1 -Wsl --profile observability logs --tail 80 langfuse-web langfuse-worker
./deploy/Compose.ps1 -Wsl exec postgres psql -U iiip -d iiip_agent -c 'SELECT extname, extversion FROM pg_extension;'
```

健康检查失败先读取对应服务日志，不连续重启掩盖问题。镜像拉取失败先核验镜像是否存在及 registry 网络；此次实际发现旧 `minio/mc` 仓库拉取失败，已使用当前官方依赖替换。Docker Desktop 的 socket 初始化故障与本项目 Compose 无关，可按 [Docker 官方问题记录](https://github.com/docker/desktop-feedback/issues/448)诊断；WSL 独立引擎可继续管理现有部署。

实际版本、协议检查、资源用量和仍未覆盖的演练见同目录《Docker 部署验证记录》。
