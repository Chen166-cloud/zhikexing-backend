# 知课行 Docker 部署验证记录

核对日期：2026-09-26。以下结果对应当前 `zhikexing` Compose 与知课行命名的 Java、Python、Vue 源码。测试账户、活动和订单均为合成数据；模型使用 `AI_PROVIDER=fixture`。启动和复现命令见[Docker 部署文档](Docker部署文档.md)。

## 完整 Compose

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
