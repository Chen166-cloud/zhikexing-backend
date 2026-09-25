# Docker 部署验证记录

更新日期：2026-09-25。当前仓库 Compose 已配置 MySQL、PostgreSQL/pgvector、Redis、RocketMQ、MinIO、Java、Python、Vue/Nginx 和可选观测服务。具体配置、端口、启动与备份命令见[Docker 部署文档](Docker部署文档.md)。

## 已完成的新版验证

- Compose 全 profile 配置与启动脚本语法检查通过。
- 独立测试项目在真实 MySQL 8.4.8、Redis 8.2.1、RocketMQ Broker 5.5.0 上通过 Java 19 项专项测试；Java/Python/Agent fixture 跨服务 HTTP 演练通过。
- Vue 生产构建与类型检查通过；测试数据均为合成数据，隔离环境已清理。

## 常用部署状态

本机用于常用部署的 Ubuntu-22.04 WSL 检查时为 **Stopped**。常用完整 Compose 应用尚未按新版重新构建、启动并验收；旧 RabbitMQ 队列尚未迁移。因此，本页不报告新版完整部署的服务健康、真实模型响应、备份恢复或端到端浏览器结果。

执行升级时，先备份数据库、对象存储和配置并盘点旧待处理消息，再按[部署文档](Docker部署文档.md)启动新版。验收应至少包括 Flyway V5、RocketMQ Topic/Group、Java/Python/Vue 健康、普通命令幂等接收、试听发布/参与/审批/订单/对账和故障恢复。完成后记录时间、镜像摘要、命令输出及实际结果，不能以本页的隔离测试替代。
