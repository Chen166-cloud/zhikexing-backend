# 课程缓存实验

三档模式使用相同 HTTP 接口、鉴权和查询语义，默认保持双层缓存。只在独立实验数据与 Redis 上运行。

后端环境变量：

- `APP_CATALOG_CACHE_MODE=db|redis|two-level`（默认 `two-level`）。`db` 直接调用数据库 loader，`redis` 仅使用共享缓存与重建锁，`two-level` 加 Caffeine。
- `APP_CATALOG_CACHE_KEY_PREFIX=benchmark:cache:`（默认 `catalog:`）。两个实例必须一致；不同实例配置不同 `SNOWFLAKE_NODE_ID`。
- `CATALOG_LOCAL_TTL=10s`、`CATALOG_SHARED_TTL=60s`、`CATALOG_MAX_ENTRIES=1000`、`CATALOG_LOCK_WAIT=1s` 保持三档一致。

计数：`catalog_cache_calls_total{mode}` 是缓存入口次数；`catalog_cache_loads_total` 是 loader 调用次数；`catalog_sql_queries_total{query}` 是 SQL 调用次数。首页一次 loader 包含 count 和列表两次 SQL，不能将 loader 次数当 SQL 次数。所有计数是调用尝试数；失败 SQL 也计数。`catalog_cache_configuration{mode,key_prefix}` 用于 runner 校验实际配置。缓存命中仍有登录态 Redis 访问。

依赖 Python 3.10+ 与 `httpx`。token 文件可以是 JSON 字符串数组或每行一个 token。文件不要提交到 Git。脚本不打印 token，也不保存响应正文。

```powershell
python experiments/cache/run_cache.py throughput --mode two-level --base-urls http://127.0.0.1:28380 --metrics-urls http://127.0.0.1:28381/actuator/prometheus --tokens-file <tokens.json> --output-dir <new-output-directory> --paths /api/v1/courses,/api/v1/courses/<course-id> --rates 25,50,100 --warmup-seconds 60 --stage-seconds 180 --repetitions 3
```

三种模式分别重启后端后执行。每个 rate/repetition 先预热，再记录测量阶段。HTTP/1.1 连接池复用；到达计划使用单调时钟，与上一个请求完成无关。达到 `--max-inflight`（默认 512）或调度落后超过 `--max-lag-ms`（默认 250）直接记录掉发，不将队列等待伪装成吞吐。`--late-ms`（默认 5）只记录调度晚到，不决定丢弃。单次请求超时默认 10 秒。

空闲连接池默认 `--max-keepalive 32`，与最大在途数分开设置，避免发生器因保存大量连接增加调度开销。可用 `--startup-rates 100,300 --startup-seconds 10` 在每次启动时先做两级升温，再进行指定的预热/测量阶段；这些记录标记为 `startup`，不计入正式结果。若预热或测量出现发生器掉发，应保留失败记录并重选速率，而不是把设定到达率写成实测吞吐。

`requests.jsonl` 每个计划到达一行（包括掉发），带 stageId、目标实例、延迟、调度落后、状态、错误分类。`summary.json` 每阶段含 P50/P95/P99、offered/sent/completed/success、掉发、CPU秒与指标增量。warmup 与 measure 分别标记。完成后不以正常退出码代表满足某个性能 SLO：需检查 summary 的错误、掉发与目标分位数。

可追加 `--machine-metadata <hardware.json>` 保存机器与容器配置，runner 还会记录实际 Python/httpx/操作系统版本与脚本 SHA256。追加 `--docker-command "wsl -d Ubuntu-22.04 -- docker" --docker-containers <backend-name>,<mysql-name>,<redis-name> --resource-interval 5` 可持续生成 `resources.jsonl`，每条含阶段、起止 UTC 时间、Docker CPU/内存/IO 样本；Linux 直接传 `--docker-command docker`。采样完成后等待指定秒数再采下一次。只读指定容器，不修改其配置。

- `offered_rps = 计划到达数 / 到达窗口长度`
- `achieved_rps = 实际发送数 / 到达窗口长度`
- `success_rps = 在到达窗口内完成的成功响应数 / 到达窗口长度`
- `success_rps_including_drain = 全部成功响应数 / 包含排空的实际经过时间`
- `latency_ms` 是客户端往返时间：从实际发起 HTTP，到接收正文并完成预期状态/JSON 摘要校验，包含网络、连接池等待与客户端内容校验成本；它不是纯服务端处理耗时。三档请求与正文一致，因此此客户端口径可比。
- `scheduled_to_finish_ms` 从计划到达计时，在上述客户端往返时间之外还包含压测器晚到。

200 响应必须与各接口预检时的规范化 JSON 摘要一致，内容变化也计为错误。预检不计入负载。`--expected-status 404` 可测同一不存在课程，404 是该实验预期结果。未知 HTTP 错误与连接异常分开统计。不要在实验期间修改课程数据。

预检同时要求无 token 和无效 token 均返回 401；结果及每个接口的正常响应 SHA256 保存在 summary，方便三档比较鉴权和内容一致性。

双实例冷 key 模式：

```powershell
python experiments/cache/run_cache.py cold --mode two-level --base-urls http://127.0.0.1:28380,http://127.0.0.1:28382 --metrics-urls http://127.0.0.1:28381/actuator/prometheus,http://127.0.0.1:28383/actuator/prometheus --tokens-file <tokens.json> --output-dir <new-output-directory> --paths /api/v1/courses/<course-id> --cache-key course:<course-id> --redis-port 28379 --redis-database 0 --redis-key-prefix benchmark:cache: --concurrencies 100,300 --rounds 20 --local-ttl-wait-seconds 10.5
```

每轮等待 L1 过期，执行 **一个明确共享 value key** 的 `DEL`，然后同时释放 100 或 300 个请求，均匀发往两个实例。默认会执行 40 轮。`--local-ttl-wait-seconds` 必须大于真实 L1 TTL。脚本先校验两实例的 mode/prefix 指标；namespace 只接受 `benchmark:cache:` 或 `experiment:cache:` 前缀。不会删除 lock、扫描 key 或 FLUSH。不要同时运行其他课程负载，否则全局回源计数增量无法归因。

有密码时通过 `--redis-password-env BENCHMARK_REDIS_PASSWORD` 读取指定环境变量，ACL用户名可通过 `--redis-username` 提供。任何一轮响应有误或聚合 loader 增量不等于 1，冷测试以退出码 2 结束并保留结果。L2 每轮重新建立，响应正文与预检一致。

无需服务的验证：

```powershell
python -m unittest discover -s experiments/cache -p "test_*.py" -v
mvn "-Dtest=CatalogCacheModeTest,CatalogAuthorizationTest" test
```

现有真实 Redis 集成测试仍可用 `CATALOG_TEST_REDIS_PORT` / `CATALOG_TEST_REDIS_DATABASE` 启用。正式实验前先用低负载短时 smoke；正式配置使用 60 秒预热、180 秒测量、3 次重复，并保存机器信息、版本、完整启动配置与指标。提交代码时不应包含 tokens 或原始服务凭据。
