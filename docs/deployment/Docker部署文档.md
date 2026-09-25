# 知课行 · AI 课程服务平台 Docker 部署与运维

本文对应 Java 仓库根目录 `docker-compose.yml`，部署知课行网站、Java 业务服务、Python Agent 运行时与中间件。真实模型模式使用百炼 `qwen3.7-flash` 和 `text-embedding-v4`（1024 维），只需要一个 `DASHSCOPE_API_KEY`；确定性联调可选择 `AI_PROVIDER=fixture`。数据库、内部服务与对象存储凭据由初始化脚本分别随机生成。

**验证边界（2026-09-26）**：`zhikexing` 完整 Compose 已在 WSL Docker Engine 中健康启动；`Verify-Compose.ps1` 经 Vue/Nginx 入口验证真实 MySQL、Redis、RocketMQ、Java/Python HTTP、试听事务消息、异步订单与 Agent fixture 审批落单。隔离真实中间件测试分别通过 15 项试听与 3 项 Agent 桥接用例。浏览器从注册进入首页、工作台和免费试听页面，控制台没有错误。真实模型、真实 API 下 MEMBER 浏览器权限、浏览器内 Agent 审批、备份恢复和容量指标仍需单独验证，详见[部署验证记录](Docker部署验证记录.md)。

## 1. 组成与端口

默认启动核心中间件，`observability` 增加观测服务，`app` 增加 Java、Python 和 Vue/Nginx。全部宿主端口绑定 `127.0.0.1`，服务之间使用 Compose DNS。原本机 MySQL 3306 和 Redis 6379 不受影响。

| 服务 | 宿主入口 | 容器内地址 | 数据职责 |
|---|---|---|---|
| MySQL | 13306 | mysql:3306 | 登录、权限、课程、预约、审批、outbox、试听活动/请求/订单 |
| PostgreSQL + pgvector | 15432 | postgres:5432 | Agent 状态、消息、知识、向量、评测 |
| Redis | 16379 | redis:6379 | 登录态、缓存、试听库存预占；AOF + noeviction |
| RocketMQ NameServer / Broker | 9876 / 10911、10909 | rocketmq-nameserver:9876 / rocketmq-broker:10911 | 普通命令与试听事务消息；Broker 5.5.0，client 5.5.1 |
| MinIO | S3 19000，控制台 19001 | minio:9000 | 文档与 Langfuse 对象；不同 bucket 隔离 |
| Langfuse Web / Worker | Web 13000 | langfuse-web:3000 | 模型轨迹与评测观测 |
| Langfuse PostgreSQL / Redis | 不映射 | langfuse-postgres:5432 / langfuse-redis:6379 | 与业务库、登录态独立隔离 |
| ClickHouse | 不映射 | clickhouse:8123 / 9000 | Langfuse 分析存储 |
| Prometheus | 19090 | prometheus:9090 | 指标采集与保留 15 天 |
| Grafana | 13001 | grafana:3000 | 预置 Prometheus 数据源和知课行看板 |
| OTel Collector | HTTP 14318，gRPC 14317，健康 13133 | otel-collector:4318 / 4317 | OTLP 接收与指标出口 |
| Java | 18080 | backend:8080 | 外部 API、认证授权、业务工具 |
| Python Runtime | 18000 | agent-runtime:8000 | 内部 Agent API 和持久化作业执行 |
| 知课行 Vue / Nginx | 8088 | frontend:80 | `/login`、`/register`、`/` 首页、`/agent` 工作台与“免费试听”标签；同源 API 与 SSE 反向代理 |

默认 `ROCKETMQ_ENABLED=true`。Java outbox 向 `zhikexing-agent-commands` 投递普通消息，同一 run 固定队列；Java 顺序消费者经 HTTP 将命令交给 Python，只有 Python 幂等接收并提交 Run/取消状态后返回 2xx 才确认。Python 不依赖 RocketMQ 原生库。免费试听使用 `zhikexing_trial_claims` 事务 Topic，生产组 `zhikexing_trial_tx_v1`，消费组 `zhikexing_trial_order_v1`；半消息成功后才执行 Lua 预占，事务回查根据共享持久记录决定提交/回滚/未知。普通命令重试耗尽进入 `%DLQ%zhikexing-agent-command-consumer`，试听为 `%DLQ%zhikexing_trial_order_v1`，由运维对账和恢复任务处理。设为 `false` 仅让 Agent outbox 使用 HTTP；试听不能静默降级成同步扣库存。模型遥测经 OTLP 接入 Langfuse，实际链路验证见验证记录。Elasticsearch、LiteLLM、vLLM、Temporal 属于方案按需扩展项，本部署不默认安装。


### RocketMQ 地址与运行边界

`rocketmq-init` 等待 Broker 健康后幂等创建 Topic/消费组，Java 等待初始化成功才启动。Broker 固定为 `apache/rocketmq:5.5.0` 的已核验摘要，原生 Java client 为 5.5.1；Apache 已发布 5.5.1 客户端，但核验时同版本 Docker 标签尚不存在，不能填写无法拉取的镜像。单 Broker + SYNC_FLUSH 是本地演示配置，不等于多副本高可用。

首次创建 `rocketmq-store`、`rocketmq-logs` 命名卷时，Docker 可能把卷根目录设为 `root:root`，Broker 镜像内的非 root 用户无法写入。一次性服务 `rocketmq-volume-init` 以 root 身份将这两个卷设为 `3000:3000`，成功退出后 Broker 才启动；Broker 本身继续使用镜像默认的非 root 用户。`rocketmq-volume-init` 在 `ps -a` 中显示 `Exited (0)` 属于正常状态。已有卷也会经过同一步骤，重建容器不要求删除历史消息卷。

默认 Broker 通告地址 `rocketmq-broker` 供 Compose 网络使用。若 Java 在宿主启动，必须把 `ROCKETMQ_BROKER_HOST` 改为宿主 Java 和容器都能访问的实际宿主地址，并确保 Broker 的 10911/10909 端口映射及防火墙匹配；不能只配置 NameServer 而忽略它返回的 Broker 地址。隔离集成测试应使用独立 Compose 项目/网络/卷和端口，不能复用现有数据库或 Redis 库存。

当前本地 Broker 不启用 ACL，端口仅绑定环回地址；上云前建立独立私有网络、TLS/ACL 与最小客户端权限。Redis `noeviction` 防止预占凭据被逐出，AOF everysec 仍可能在故障时丢失最近写入，不能承诺 Redis 不丢数据；活动库存丢失时停止受理并依据 MySQL 请求/订单对账，不自动用总名额覆盖重建。预占凭据不靠 TTL 直接回滚，回收必须检查持久化终态。

完整部署验收应分别检查普通命令投递与试听事务 Topic，确认 Python Run 接收、试听持久请求终态和 0 元订单；浏览器应在 `/agent`“免费试听”标签验证角色权限、直接抢课、本人记录恢复与 OWNER 对账。

## 2. 环境要求与引擎选择

建议为完整应用与观测预留 8 vCPU、16 GB 内存和至少 30 GB 可用磁盘，首轮镜像下载需要网络。此值是启动规划，实际资源观察见部署验证记录。WSL 的 `df` 显示虚拟磁盘上限，不能代替宿主盘可用空间；启动前同时检查 PowerShell `Get-PSDrive -PSProvider FileSystem` 和 WSL 的 `df -h`。Linux 使用 Docker Engine 和 Compose 插件，Windows 可使用 Docker Desktop Linux 容器或 WSL 内独立 Docker Engine。

根据自己的环境选择 Docker Desktop、Linux Docker Engine 或 WSL 内 Docker Engine。WSL 中的独立引擎与 Docker Desktop 的镜像、网络和卷分开；使用 WSL 引擎时通过 `Compose.ps1 -Wsl` 管理。实际验证范围见[部署验证记录](Docker部署验证记录.md)。

首次在新的 Ubuntu 环境安装引擎：进入所选 Ubuntu/WSL 终端，在已克隆的 Java 仓库根目录执行。路径由克隆位置决定：

```sh
sudo bash deploy/install-docker-ubuntu.sh
```

脚本按 [Docker 官方 Ubuntu 安装方式](https://docs.docker.com/engine/install/ubuntu/)配置官方 APT 源并启动 systemd 服务。已有 `dockerd` 时仅启动服务，不改装版本。在 Windows PowerShell 7 的 Java 仓库根目录启动 WSL 引擎：

```powershell
./deploy/Start-WslEngine.ps1
./deploy/Compose.ps1 -Wsl version
```

默认采用系统默认 WSL 发行版。若引擎位于其他发行版，先运行 `wsl --list --quiet` 查看名称，再设置 `$env:ZHIKEXING_WSL_DISTRIBUTION = '<实际发行版名称>'`；`Start-WslEngine.ps1` 也支持显式 `-Distribution`。`Compose.ps1` 通过环境变量选择发行版，其余参数保留给 Docker Compose。脚本使用 `wslpath` 转换当前仓库路径，不假设固定盘符或挂载点。

微软明确说明 [systemd 服务不会保持 WSL 实例运行](https://learn.microsoft.com/en-us/windows/wsl/systemd)。`Start-WslEngine.ps1` 启动隐藏的普通 WSL 会话，避免最后一个构建/终端进程退出后发行版自动停止；Windows 重启后需重新执行。停止整套服务后，可在任务管理器关闭该 `wsl.exe` 会话；终止整个发行版会同时停止其中其他工作，不能把它当作仅停止本项目的命令。

普通 Linux 在仓库根目录直接执行本文对应的 `docker compose ...` 命令；健康的 Docker Desktop 可去掉包装脚本的 `-Wsl`。

## 3. 首次启动

先按后端 [README](../../README.md#三个独立项目) 提供的 Gitee 地址克隆三个仓库，推荐放在同一个父目录。再在 Java 仓库根目录的 PowerShell 7 中执行：

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

PowerShell 脚本要求 PowerShell 7。`.env` 已被 Git 忽略；脚本只写随机基础设施凭据，不复制模型 Key。默认 `FRONTEND_PATH=../zhikexing-web`、`AGENT_RUNTIME_PATH=../zhikexing-agent-runtime`，两个构建上下文都相对于 Java 仓库根目录。按同级结构克隆，在 Windows Docker Desktop、WSL 或 Linux 上均无需把路径改成作者机器的地址。

如果自定义了克隆目录名或存放位置，可在首次初始化时传 `-FrontendPath` / `-AgentRuntimePath`，或编辑本机 `.env`。优先填写相对于 Java 仓库根目录的路径，例如 `../../frontend/web`。使用绝对路径时，Docker Desktop 填当前电脑路径，WSL Docker 填对应 Linux 可访问路径；这些个人配置不要提交。初始化脚本拒绝覆盖已有 `.env`，避免重置数据库密码。

Linux 开发者安装 PowerShell 7 后可运行 `pwsh ./deploy/Initialize-Environment.ps1` 完成同一随机凭据初始化，随后使用本文对应的原生 `docker compose` 命令；只有 Windows 的 WSL 包装方式需要 `-Wsl`。

接入百炼模型时，在当前终端配置 `DASHSCOPE_API_KEY`，不要把密钥写进命令或文档，然后启动完整应用：

```powershell
./deploy/Compose.ps1 -Wsl --profile app --profile observability up -d --build --wait
```

需要可重复的跨服务研发联调而没有模型 Key 时，可在同一个 PowerShell 终端显式选择 fixture，再执行完整应用启动命令：

```powershell
$env:AI_PROVIDER = 'fixture'
./deploy/Compose.ps1 -Wsl --profile app --profile observability up -d --build --wait
```

也可以在本机未提交的 `.env` 中持久设置 `AI_PROVIDER=fixture`，省略临时 PowerShell 赋值；若当前终端已设置不同的 `$env:AI_PROVIDER`，先执行 `Remove-Item Env:AI_PROVIDER -ErrorAction SilentlyContinue`，使 `.env` 生效。`Compose.ps1 -Wsl` 通过 WSLENV 只转发当前 PowerShell 进程中非空的 `DASHSCOPE_API_KEY`、`AI_PROVIDER`、`ROCKETMQ_ENABLED`、`AGENT_RUNTIME_IMAGE` 覆盖变量，避免空变量遮蔽 `.env`。fixture 仅替代 Python Runtime 的模型输出；MySQL、PostgreSQL、Redis、RocketMQ、MinIO 和 Java/Python HTTP 调用仍连接真实 Compose 服务。基础设施初始化不需要模型 Key。真实模型验收时将 `AI_PROVIDER` 设为 `bailian` 并配置 Key，fixture 结果不能记作真实模型效果。

启动后可用以下命令逐项核对容器、真实 Redis、RocketMQ Topic 和应用入口。`ps -a` 中 `rocketmq-volume-init`、`rocketmq-init`、`minio-init` 显示 `Exited (0)` 是预期的一次性任务状态；其余应用和常驻中间件应健康：

```powershell
./deploy/Compose.ps1 -Wsl --profile app --profile observability ps -a
./deploy/Compose.ps1 -Wsl exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
./deploy/Compose.ps1 -Wsl exec -T rocketmq-broker sh mqadmin topicRoute -n rocketmq-nameserver:9876 -t zhikexing-agent-commands
./deploy/Compose.ps1 -Wsl exec -T rocketmq-broker sh mqadmin topicRoute -n rocketmq-nameserver:9876 -t zhikexing_trial_claims
./deploy/Compose.ps1 -Wsl exec -T backend curl -fsS http://localhost:8081/actuator/health
Invoke-RestMethod http://127.0.0.1:18000/health
Invoke-WebRequest http://127.0.0.1:8088/agent
```

`redis-cli` 应返回 `PONG`，两个 Topic 路由应分别含非空 `queueDatas`，Java 健康应为 `UP`，Python 健康和前端页面应返回成功状态。这些命令检查服务可达；事务回调、Lua 预占、异步落单、Agent 审批与库存守恒由下文的真实业务烟测核对。

`.env` 可配置 Runtime 运行限制：`AGENT_COST_LIMIT_CNY=0.10`、`AGENT_TOKEN_LIMIT=40000`、`AGENT_RUN_TIMEOUT_SECONDS=240`、`AGENT_MAX_PARALLEL=4`。费用限制在下一次模型调用前检查，单次调用可能超过剩余额度，并非云平台的硬计费上限。修改运行图、Prompt、工具 schema 或模型配置后，旧运行可能因版本不兼容拒绝续跑，应先结束或明确迁移存量运行再升级。

打开 `http://localhost:8088/login` 登录知课行，进入 `/` 产品首页，再从导航进入 `/agent` 工作台；左侧“免费试听”标签提供活动浏览、直接抢课、本人结果追踪及 OWNER 管理。Langfuse 初始账号为 `.env` 中 `LANGFUSE_ADMIN_EMAIL` 和 `LANGFUSE_ADMIN_PASSWORD`；Grafana 账号为 `admin`，密码对应 `GRAFANA_PASSWORD`；MinIO 对应 `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY`。这些随机密码只在本机 `.env` 查看。

## 4. 数据初始化与持久化

Compose 项目名固定为 `zhikexing`。每类服务使用专用命名卷，不挂载本机其他 MySQL/Redis 数据目录。MySQL 数据库与用户均为 `zhikexing`；Java Flyway V1–V7 管理业务结构，V7 将聊天记录和 PDF 文件表统一命名为 `zhikexing_chat_record`、`zhikexing_pdf_file`。当前应用表无自增列、无物理外键，新写入主键使用应用雪花 ID，服务层校验逻辑关联；V6 对已有单列数值主键不重编，迁移库可能保留原值。PostgreSQL 数据库为 `zhikexing_agent`，初建卷执行 `deploy/postgres/001-vector.sql` 安装 vector 扩展，Python 负责运行时表结构。`minio-init` 创建 `zhikexing-documents` 和 `langfuse` 两个 bucket，重复启动保持幂等。

单 Java 写入节点的 `SNOWFLAKE_NODE_ID` 默认 0；多实例写入时，每个节点必须配置不同的 0–1022 编号并确认环境变量已透传到应用，1023 保留给迁移与演示数据。生成器在系统时钟回拨时拒绝继续发号，部署主机应保持时间同步。HTTP 中的雪花 ID 按字符串处理，避免浏览器数值精度损失。

PostgreSQL、MySQL 的初始化密码变量仅对首次创建数据目录生效。已有数据后直接修改 `.env` 密码会造成认证失败；应先通过数据库管理命令轮换真实密码，再同步配置。不要用删除卷解决认证错误。

首次 Java 启动完成 Flyway 迁移后，可手动导入演示课程和校区。`deploy/demo-data.sql` 使用预留节点 1023 的固定雪花演示 ID，不会自动执行；只对本项目新建演示库使用：

```powershell
./deploy/Compose.ps1 -Wsl cp deploy/demo-data.sql mysql:/tmp/zhikexing-demo-data.sql
./deploy/Compose.ps1 -Wsl exec mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -uzhikexing zhikexing < /tmp/zhikexing-demo-data.sql'
```

该脚本不创建用户或预约。演示用户通过正常注册接口创建，实际预约需要用户在工作台批准。

完整 Compose 以 `AI_PROVIDER=fixture` 启动且导入上述课程、校区后，可在 PowerShell 7 运行真实中间件专项烟测：

```powershell
./deploy/Verify-Compose.ps1
```

脚本固定使用 `http://127.0.0.1:8088` 的 Vue/Nginx 同源入口，检查前端首页 200，并通过反向代理执行用户注册、活动发布、幂等直接抢课、RocketMQ 异步落单、Agent 审批与执行；直接活动落单后会暂停，避免 fixture 在后续选错活动。两个活动都检查 `capacity = remaining + confirmedOrders` 和 Redis 库存状态。它会在演示库中保留合成账号、活动、申请和订单，输出仅含状态及业务 ID；若活动指向错误或中间件不可达会失败。实际运行结论见[部署验证记录](Docker部署验证记录.md)。

隔离并发与故障场景使用 `deploy/trial-test/compose.yml`。它的 MySQL、Redis、RocketMQ NameServer 和 Broker 分别映射 23306、26379、19876、20911/20909，Broker 通告 `127.0.0.1:20911`；可与主 Compose 同时运行。先启动测试栈并创建事务及普通 Topic，再运行集成测试，结束时仅清理隔离项目：

```powershell
./deploy/Compose.ps1 -Wsl -f deploy/trial-test/compose.yml up -d --wait
./deploy/Compose.ps1 -Wsl -f deploy/trial-test/compose.yml exec -T broker sh mqadmin updateTopic -n nameserver:9876 -c TrialTestCluster -t zhikexing_trial_claims -r 4 -w 4 -a +message.type=TRANSACTION
./deploy/Compose.ps1 -Wsl -f deploy/trial-test/compose.yml exec -T broker sh mqadmin updateTopic -n nameserver:9876 -c TrialTestCluster -t zhikexing-agent-bridge-test -r 4 -w 4 -a +message.type=NORMAL
$env:TRIAL_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:23306/zhikexing_trial_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:TRIAL_TEST_ROCKETMQ='true'
mvn -q '-Dtest=TrialIntegrationTest,RocketMqBridgeIntegrationTest' test
./deploy/Compose.ps1 -Wsl -f deploy/trial-test/compose.yml down
```

`down` 不加 `-v`，不会操作主项目 `zhikexing` 的卷。`target/surefire-reports` 中两类测试应分别为 15/15、3/3，且 failures、errors、skipped 均为 0。

暂停及恢复：

```powershell
./deploy/Compose.ps1 -Wsl --profile app --profile observability stop
./deploy/Compose.ps1 -Wsl --profile app --profile observability start
```

`down` 删除容器与网络但保留命名卷。日常操作不添加 `-v`，它会删除数据。升级前先备份，更新指定镜像版本后重建对应服务，观察迁移与健康状态；数据库迁移不能简单等同于镜像回滚。

运行图、Prompt、工具 Schema 或模型配置变化前，先完成在途任务或制定 checkpoint 迁移方案，并备份当前数据库与对象。需要回退时，应使用事先构建和核验的镜像及相容的数据库快照；当前部署文档不假设本机存在可用的归档 Runtime 镜像。`AGENT_RUNTIME_IMAGE` 可指定已实际保存的镜像标签。

## 5. 观测与 S3 配置

Langfuse 按[官方 Docker Compose](https://langfuse.com/self-hosting/deployment/docker-compose)及 [v4.38.0 配置](https://github.com/langfuse/langfuse/blob/v4.38.0/docker-compose.yml)部署 Web、Worker、PostgreSQL、Redis、ClickHouse、S3 依赖。业务 PG 与 Langfuse PG 为独立实例；Langfuse Redis 使用 `noeviction`，独立于登录态缓存。MinIO 使用官方配置当前采用的 Chainguard 镜像，并锁定实际拉取的 digest。

MinIO 是本地开发的 S3 兼容实现，不需要 OSS AccessKey。云环境业务文件可配置已验证的 S3 endpoint 或扩展 OSS adapter；阿里云 OSS 原生 SDK endpoint 不应直接作为 Langfuse S3 endpoint。模型 Key 的共用不代表数据库密码、OSS AccessKey 或内部 Token 可以合并。

Grafana 自动加载 知课行 运行总览，涵盖抓取状态、Java 请求速率、JVM 堆内存和 RocketMQ 消费积压。Prometheus 的 `/targets` 可以检查各 scrape endpoint；只启动中间件时 Java/Python 两项 DOWN 属于应用尚未启动，不能写成完整业务健康。Collector 通过 `/` 健康端口 13133 报告进程状态；当前 traces 输出为 basic 日志，长期模型轨迹由应用直接送 Langfuse。

部署到远程服务器时，按实际域名修改 `LANGFUSE_PUBLIC_URL`、`S3_PUBLIC_URL`，否则浏览器预签名地址会指向本机 localhost。Nginx 已关闭 SSE 缓冲、配置长连接超时，并通过 `$http_host` 保留浏览器入口端口。Java 的 `CORS_ORIGINS` 可完整替换默认白名单；默认允许 localhost 和 127.0.0.1 的 5173、5176、8088 端口。生产环境在统一入口配置 HTTPS，同时保持数据库与内部 Runtime 不对公网暴露。

## 6. 备份与恢复

在 WSL 的仓库根目录执行 `bash deploy/backup.sh`，脚本暂停三项应用写入，备份 MySQL、Agent PG、Redis，再停止 MinIO 和 RocketMQ Broker，备份它们的命名卷，退出时恢复对象存储、Broker 和应用。Broker 中尚未由 Runtime 持久接收的消息不能仅靠数据库备份恢复。输出目录 `backups/<时间>`，包含 SHA256 校验文件；该目录不得提交。生产环境还需要异地副本、保留策略和加密存储。

使用 WSL 独立引擎时，进入所选发行版的 Java 仓库根目录执行；需要 Docker 权限时使用 sudo，脚本退出前等待应用重新 healthy：

```sh
sudo bash deploy/backup.sh
```

恢复必须先在新的 Compose 项目和新卷中演练，不能直接覆盖现有实例：

1. 为恢复环境准备新的 `.env`、项目名和宿主端口；先只启动空 MySQL、PG、MinIO、Redis。
2. 用 `docker compose exec -T mysql` 和 `mysql -uzhikexing zhikexing` 导入 `mysql.sql`，数据库密码通过容器内 `MYSQL_PWD` 提供。
3. 用 `docker compose exec -T postgres pg_restore -U zhikexing -d zhikexing_agent --no-owner` 导入 `agent.dump`；目标数据库必须为空。
4. 停止目标 MinIO/Redis，将对象快照与 `dump.rdb` 恢复到目标新卷；Redis 开启 AOF 时应按 Redis 官方持久化规则处理目标新目录，不能混用旧 AOF。`agent-data.tar.gz` 还保存 Runtime 本地 `/data`，应恢复到新的 `agent-data` 卷；标准部署使用 PostgreSQL + S3，本地卷不承担主业务持久化。
5. 停止恢复环境的 RocketMQ Broker，将同批次 `rocketmq-store.tar.gz` 恢复到新的 `rocketmq-store` 卷，保持 brokerName/cluster 与镜像版本一致，再启动 Broker 核对 Topic、消费位点和事务半消息。
6. 启动应用，核对文档 hash/读取、运行历史、审批与预约记录，运行只读验收。确认恢复结果后再规划正式切换。

观测历史额外备份 `langfuse-postgres` 与 ClickHouse。先暂停 `langfuse-web` / `langfuse-worker` 的写入，再用 `pg_dump -U langfuse -d langfuse -Fc` 备份关系库；ClickHouse 需配置专用备份磁盘后使用其原生 `BACKUP`/`RESTORE`，并与 `langfuse` bucket 快照保持同批次。业务恢复可以先于观测历史，具体恢复演练是否完成见部署验证记录。

## 7. 排障命令

### 普通 Agent 命令进入 DLQ 后重驱

`agent_outbox.DELIVERED` 表示 Broker 接受消息，不表示 Python 已接收。Java 顺序消费者单条失败暂停当前队列 10 秒，最多重试 16 次；耗尽后进入 `%DLQ%zhikexing-agent-command-consumer`，不能依靠 outbox 自动重发已标记 DELIVERED 的行。

1. 在 Broker 日志、`mqadmin consumerProgress -g zhikexing-agent-command-consumer` 和 DLQ 中找到消息 key（原 outbox id），结合 run 的提交状态判断是否确实未接收；不要根据模型未开始就断定投递丢失。
2. 修复 Python 不可达、Token 不一致、数据格式或权限问题。若消息本身不合法，保留隔离记录，不直接改它的 actor/workspace/payload 绕过校验。
3. 使用 `deploy/rocketmq/redrive-agent-command.sql`，仅替换已核实的 `@command_id`；先在目标演示库确认查询到的 run/path，再执行单行状态重置。`redriven_rows` 必须为 1，0 表示未匹配 DELIVERED 行，不应扩大 WHERE 条件。
4. dispatcher 仍用原 command id/run id/payload 发布；Python 基于 Run 主键和 request hash 幂等处理，已执行的业务依旧由 action 唯一约束保护。对已取消 run 的重放不会撤销取消标记。
5. 通过 Run/取消状态查询和事件核验结果。原 DLQ 条目可能保留至 Broker 保留期，不把“旧 DLQ 仍可查询”误当成重驱再次失败；登记原消息 key 和本次处理结论。

历史命令恢复不要使用全 Topic offset reset，也不要批量把全部 DELIVERED 改成 PENDING。试听事务消息由持久请求表的 PENDING/RESERVED 扫描重试与独立补偿处理，不使用此普通命令重驱脚本。

```powershell
./deploy/Compose.ps1 -Wsl --profile app --profile observability ps -a
./deploy/Compose.ps1 -Wsl logs --tail 80 backend agent-runtime
./deploy/Compose.ps1 -Wsl --profile observability logs --tail 80 langfuse-web langfuse-worker
./deploy/Compose.ps1 -Wsl exec postgres psql -U zhikexing -d zhikexing_agent -c 'SELECT extname, extversion FROM pg_extension;'
```

健康检查失败先读取对应服务日志，不连续重启掩盖问题。镜像拉取失败先核验镜像是否存在及 registry 网络；此次实际发现旧 `minio/mc` 仓库拉取失败，已使用当前官方依赖替换。Docker Desktop 的 socket 初始化故障与本项目 Compose 无关，可按 [Docker 官方问题记录](https://github.com/docker/desktop-feedback/issues/448)诊断；WSL 独立引擎可继续管理现有部署。

实际版本、协议检查、资源用量和仍未覆盖的演练见同目录《Docker 部署验证记录》。
