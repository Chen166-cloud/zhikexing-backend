#!/usr/bin/env bash
# 从仓库根目录执行：bash deploy/backup.sh [输出目录]。
# 暂停应用写入，并在文件快照时停止对象存储和队列。
set -euo pipefail
cd "$(dirname "$0")/.."
backup_dir=${1:-"backups/$(date +%Y%m%d-%H%M%S)"}
mkdir -p "$backup_dir"
chmod 700 "$backup_dir"
helper_image=busybox:1.37.0
docker image inspect "$helper_image" >/dev/null 2>&1 || docker pull "$helper_image"
minio_container=$(docker compose ps -q minio)
rocketmq_container=$(docker compose ps -q rocketmq-broker)
agent_container=$(docker compose --profile app ps -q agent-runtime)
docker compose --profile app stop frontend backend agent-runtime
trap 'docker compose start --wait minio rocketmq-broker; docker compose --profile app start --wait backend agent-runtime frontend' EXIT
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -uiiip --single-transaction --no-tablespaces iiip' > "$backup_dir/mysql.sql"
docker compose exec -T postgres pg_dump -U iiip -d iiip_agent -Fc > "$backup_dir/agent.dump"
docker compose exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli SAVE' >/dev/null
docker compose exec -T redis cat /data/dump.rdb > "$backup_dir/redis.rdb"
# Broker 中尚未进入 inbox 的消息也是业务状态，停 Broker 后备份其命名卷。
docker compose stop minio rocketmq-broker
# MinIO 镜像不包含 tar；辅助容器只读挂载两个命名卷，不接触宿主目录。
docker run --rm --volumes-from "$minio_container:ro" "$helper_image" tar -C /data -czf - . > "$backup_dir/minio.tar.gz"
docker run --rm --volumes-from "$rocketmq_container:ro" "$helper_image" tar -C /home/rocketmq/store -czf - . > "$backup_dir/rocketmq-store.tar.gz"
docker run --rm --volumes-from "$agent_container:ro" "$helper_image" tar -C /data -czf - . > "$backup_dir/agent-data.tar.gz"
sha256sum "$backup_dir"/* > "$backup_dir/SHA256SUMS"
echo "业务备份已保存：$backup_dir"
echo '观测历史需另行备份 Langfuse PostgreSQL 与 ClickHouse，步骤见 Docker 部署文档。'
