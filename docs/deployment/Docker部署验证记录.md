# 知课行 Docker 部署验证记录

最新完整 Compose 更新日期：2026-09-29；下方保留 2026-09-26 的历史记录。测试账户、活动和订单均为合成数据；完整 Compose 模型沿用 `AI_PROVIDER=fixture`。启动和复现命令见[Docker 部署文档](Docker部署文档.md)。

## 2026-09-29 完整 Compose 更新

在 `Ubuntu-22.04` 的 Docker Engine 重建后端、前端和 Agent 镜像，启用 `app`、`observability`，保留原有数据卷。三个应用容器的镜像 ID 均与新构建镜像一致。首次重建遇到 OpenTelemetry 容器名称冲突，检查容器状态后再次执行 `up -d --wait` 成功，无需删除容器或数据卷。

| 范围 | 实际结果 |
|---|---|
| 服务 | 17 个常驻服务运行；其中 15 个配置了 Docker 健康检查且均为 `healthy`，Langfuse Worker 与 Collector 为 `running`；3 个初始化任务均 `Exited (0)` |
| 健康与观测 | Java 健康 `UP`；Agent `UP`、`provider=fixture`、`storage=postgresql`；前端、Collector、Prometheus、Grafana、Langfuse 健康入口均 HTTP 200；Prometheus 的 5 个抓取目标全部 `up` |
| 课程 API | 未登录 401；首页 3 门课程、校区 2 个；空白归一关键词搜索 1 项；详情 ID 为字符串，价格 4999 元、周期 90 天；不存在的课程返回 404 |
| 两级缓存 | 重复读取课程后出现 Caffeine 命中与 Redis 命中指标；本次未执行并发压测，跨 JVM 锁和回源合并的隔离测试见[课程目录与两级缓存](../modules/课程目录与两级缓存.md) |
| 登录保护 | 注册加 3 次登录产生第 4 个会话后，最早 Token 返回 401，保留的 3 个会话均可访问；退出后 Token 返回 401；第 5 次密码错误返回 429，冷却期间正确密码仍返回 429，`Retry-After=60` |
| 浏览器 | 经真实 8088 入口完成注册、退出、重新登录、课程广场、关键词搜索、详情与 Agent 咨询预填；预填过程无 API 写请求，未提交会话或任务；控制台错误、警告和页面异常均为 0 |
| 试听闭环 | `Verify-Compose.ps1` 通过；直接申请与 Agent 审批申请均 `SUCCEEDED`，重放幂等键返回同一申请；两场活动均容量 2、剩余 1、已确认订单 1，Redis 与 MySQL 一致 |

本次直接活动 ID 为 `230692431773827072`，Agent 活动 ID 为 `230692433753538560`，Agent 运行 ID 为 `230692434193940480`。浏览器脚本与截图位于前端项目的 `output/playwright/compose-course-smoke.js`、`compose-course-catalog.png`、`compose-course-agent.png`。

```powershell
$env:ZHIKEXING_WSL_DISTRIBUTION='Ubuntu-22.04'
$env:AI_PROVIDER='fixture'
./deploy/Compose.ps1 -Wsl --profile app --profile observability config --quiet
./deploy/Compose.ps1 -Wsl --profile app --profile observability up -d --build --wait --wait-timeout 300
./deploy/Compose.ps1 -Wsl --profile app --profile observability ps -a
./deploy/Verify-Compose.ps1
```

本次覆盖更新后的真实部署功能；登录全局限流、并发上限及跨 JVM 缓存锁的边界由此前隔离测试验证，未在完整环境重复压力测试。外部模型、浏览器会话淘汰与任务续跑、生产吞吐仍未纳入本次验收。

## 2026-09-29 登录保护增量

| 范围 | 已执行结果 |
|---|---|
| Java | `AuthFlowTest` 10/10；隔离 Redis 8.2.1 上 `AuthLoginGuardIntegrationTest` 6/6、`AuthSessionIntegrationTest` 6/6；`mvn -q clean package` 通过 |
| Vue | 类型检查与生产构建通过；本地 Playwright mock API 验证登录 429、注册 503 的倒计时与恢复、普通密码错误、成功跳转及会话失效 401 清 Token |
| Agent | 14 项 fixture/模拟 Java 工具的隔离回归通过；内部凭据不依赖浏览器 Token，业务代码无需调整 |
| 部署 | 已于同日重建完整 Compose，并经真实 8088 入口复验；结果见上方完整 Compose 更新记录 |

上述增量与下方历史全栈结果分开记录。配置、旧 Token 失效规则和测试复现见[登录保护](../modules/登录保护.md)。

## 2026-09-26 完整 Compose 历史记录

WSL Docker Engine 中的 `app`、`observability` profile 已构建并健康启动，包含 MySQL、PostgreSQL/pgvector、Redis、RocketMQ、MinIO、Java、Python、Vue/Nginx 与观测服务。`rocketmq-volume-init`、`rocketmq-init`、`minio-init` 为成功退出的一次性初始化任务。当前 MySQL 业务库与用户为 `zhikexing`，PostgreSQL Agent 库为 `zhikexing_agent`；Flyway V1–V7 在新库执行成功，聊天记录和 PDF 文件表为 `zhikexing_chat_record`、`zhikexing_pdf_file`。

```powershell
./deploy/Compose.ps1 -Wsl --profile app --profile observability config --quiet
./deploy/Compose.ps1 -Wsl --profile app --profile observability ps -a
./deploy/Compose.ps1 -Wsl exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
./deploy/Compose.ps1 -Wsl exec -T rocketmq-broker sh mqadmin topicRoute -n rocketmq-nameserver:9876 -t zhikexing-agent-commands
./deploy/Compose.ps1 -Wsl exec -T rocketmq-broker sh mqadmin topicRoute -n rocketmq-nameserver:9876 -t zhikexing_trial_claims
./deploy/Compose.ps1 -Wsl exec -T backend curl -fsS http://localhost:8081/actuator/health
./deploy/Verify-Compose.ps1
```

`Verify-Compose.ps1` 经真实 `http://127.0.0.1:8088` Nginx 入口完成跨服务烟测：注册合成 OWNER，创建并发布两场免费试听活动；直接申请在相同 `clientRequestId` 重放时返回同一请求，RocketMQ 事务消息提交后经 Redis Lua 预占和 Java 消费者异步创建零元订单。另一场活动由 Python Agent fixture 生成 `claim_trial` 审批，Java 确认审批后也异步落单。脚本检查申请与活动归属、订单金额、数据库 `capacity = remaining + confirmedOrders` 守恒、Redis 剩余库存与数据库一致，以及暂停活动的状态。该结果验证真实 MySQL、Redis、RocketMQ、Java/Python HTTP 和持久化链路；不代表外部模型质量或生产吞吐。

真实浏览器经 `8088` 入口完成注册、登录，进入知课行首页 `/`、Agent 工作台 `/agent` 和“免费试听”页面；浏览器控制台错误数为 0。浏览器内 MEMBER 权限、Agent 审批卡与跨设备恢复尚未计入本次通过结果；直接抢课与 Agent 审批的业务闭环由上述跨服务脚本验证。

## 隔离中间件与迁移

隔离 MySQL 的 Flyway V1–V7 新库迁移与雪花主键业务检查通过。隔离真实 Redis/RocketMQ 测试中，`TrialIntegrationTest` 15/15、`RocketMqBridgeIntegrationTest` 3/3 通过，失败、错误和跳过均为 0。测试覆盖请求幂等、库存预占、重复消费、事务消息、补偿边界及普通 Agent 命令桥接；该项目使用独立 Compose 名 `zhikexing-trial-test` 和独立 MySQL/Redis/Broker，不操作主项目数据卷。

```powershell
./deploy/Compose.ps1 -Wsl -f deploy/trial-test/compose.yml up -d --wait
./deploy/Compose.ps1 -Wsl -f deploy/trial-test/compose.yml exec -T broker sh mqadmin updateTopic -n nameserver:9876 -c TrialTestCluster -t zhikexing_trial_claims -r 4 -w 4 -a +message.type=TRANSACTION
./deploy/Compose.ps1 -Wsl -f deploy/trial-test/compose.yml exec -T broker sh mqadmin updateTopic -n nameserver:9876 -c TrialTestCluster -t zhikexing-agent-bridge-test -r 4 -w 4 -a +message.type=NORMAL
$env:TRIAL_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:23306/zhikexing_trial_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:TRIAL_TEST_ROCKETMQ='true'
mvn -q '-Dtest=TrialIntegrationTest,RocketMqBridgeIntegrationTest' test
./deploy/Compose.ps1 -Wsl -f deploy/trial-test/compose.yml down
```

真实百炼模型、浏览器内 Agent 审批、真实 API 的 MEMBER 操作、跨设备恢复、备份恢复、网络故障注入、多 Broker 高可用及 QPS/p95/p99 尚未验收。
