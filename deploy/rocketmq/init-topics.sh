#!/usr/bin/env sh
set -eu
# 与应用中的 topic/group 配套；重复执行 update 命令不删除历史数据。
sh mqadmin updateTopic -n rocketmq-nameserver:9876 -c IIIPCluster \
  -t iiip-agent-commands -r 4 -w 4 -a +message.type=NORMAL
sh mqadmin updateTopic -n rocketmq-nameserver:9876 -c IIIPCluster \
  -t iiip_trial_claims -r 4 -w 4 -a +message.type=TRANSACTION
sh mqadmin updateSubGroup -n rocketmq-nameserver:9876 -c IIIPCluster \
  -g iiip-agent-command-consumer
sh mqadmin updateSubGroup -n rocketmq-nameserver:9876 -c IIIPCluster \
  -g iiip_trial_order_v1
