# Trial campaign experiment

This suite exercises the real HTTP → durable MySQL request → RocketMQ transaction
message → Redis Lua reservation → MySQL order path. It never invokes a model.
Run it against the isolated benchmark deployment, with fresh campaigns for each
stage. The default HTTP target is `http://127.0.0.1:28380`.

## Inputs and endpoints

The root seed tool supplies a private JSON file:

```json
{
  "workspaceId": "benchmark-workspace",
  "ownerToken": "OWNER_SESSION_TOKEN",
  "actors": [
    {"actorId": "10001", "token": "ACTOR_SESSION_TOKEN"}
  ]
}
```

Supply 5,000 distinct actors for the largest 30-second / 80-new-users-per-second
stage. All actors must be real members of this workspace. Each needs its own
session created through the application's normal Redis session protocol.
`--workspace` and `TRIAL_OWNER_TOKEN` override manifest values. Tokens are never
copied into experiment artifacts. Keep the actor file outside version control.

All paths below start with `/api/v1/workspaces/{workspaceId}/trials`:

| Endpoint | Meaning |
|---|---|
| `POST /campaigns` | Owner creates a draft with courseId, schoolId, capacity, startsAt, endsAt, title. Capacity is 1–10,000. |
| `POST /campaigns/{campaignId}/publish` | Owner publishes and initializes Redis inventory; RocketMQ must be enabled. |
| `POST /campaigns/{campaignId}/claims` | Actor submits `{clientRequestId}`. HTTP 202 with `requestId/status` means a durable receipt, **not a successful order**. |
| `GET /claims/{requestId}` | Actor reads their own receipt. Terminal status is `SUCCEEDED` or `REJECTED`; success includes `orderId`. |
| `GET /campaigns/{campaignId}/reconciliation` | Owner reads capacity, remaining, confirmedOrders, pending, reserved, releasePending, databaseInvariantHolds and Redis inventory. |

The same actor, source and stable clientRequestId replay the same receipt. Each
actor can create only one distinct claim per campaign. Replays must keep their
campaign. The runner selects exactly floor(users × retry_fraction) actors with a
fixed random seed; each sends one immediate retry after its first response,
including after an ambiguous transport error. There are no hidden HTTP retries.

## Baseline scheduling

The original recovery sender selects 30 durable PENDING/RESERVED requests per
cycle, sends transaction messages serially, then waits 1,000 ms **after the cycle
finishes**. This is not a 30-orders/s guarantee. The MQ consumer already uses
4–8 concurrent threads, with a message batch size of 1.

The only server change here is a configurable selection batch with the same
default 30. The existing fixed-delay setting remains unchanged. Spring command
line options make comparisons explicit:

```text
--app.trial.recovery-batch-size=30 --app.trial.recovery-delay-ms=1000
```

Equivalent environment property names are `APP_TRIAL_RECOVERY_BATCH_SIZE` and
`APP_TRIAL_RECOVERY_DELAY_MS`. Do not change sender concurrency for a baseline.
Record the Java build, environment values and Redis/MySQL/RocketMQ versions with
each run. Change one variable per later comparison.

## Reproducible runs (PowerShell from repository root)

Replace the actor file and course/school IDs with values produced by the seed.
The default scenario offers 1,000 distinct actors over about 10 seconds, plus
200 stable-ID replays, competing for 100 seats:

```powershell
python experiments/trial/trial_load.py --actors experiments/private/actors.json --course-id 1 --school-id 1 --create-capacity 100 --users 1000 --rate 100 --retry-fraction 0.2 --label baseline-100-seats --output experiments/trial/results/baseline-100-seats
```

For an immediate burst, explicitly use `--rate 0`; `--concurrency 128` still
bounds in-flight POST requests. 1,000 users and 1,000 simultaneous HTTP requests
are different workloads. The output records both scheduler lag and HTTP latency.

Sustained successful-order capacity uses sufficient stock and a fresh campaign
per rate. There are 150, 300, 600, 1,200 and 2,400 unique actors respectively.
The 20% replays are *additional* HTTP traffic; `--rate` is new logical users/s.

```powershell
foreach ($trialRate in @(5,10,20,40,80)) {
    python experiments/trial/trial_load.py --actors experiments/private/actors.json --course-id 1 --school-id 1 --create-capacity 10000 --rate $trialRate --duration 30 --retry-fraction 0.2 --label "baseline-r$trialRate" --output "experiments/trial/results/baseline-r$trialRate"
    if ($LASTEXITCODE -ne 0) { throw "Trial stage $trialRate needs inspection; do not silently continue." }
}
```

The runner waits for pending, reserved and compensation backlog to drain and for
known receipts to reach terminal state. Default timeout after submissions is
240 seconds. A failed check produces exit code 2 and retains evidence. HTTP 429,
timeouts, incomplete draining and receipt mismatches are reported, not discarded.

Observation defaults: reconciliation at most once per second; all actor polling
combined is at most 50 GET/s, up to 32 concurrent GETs, minimum one second between
polls of the same receipt. This load is extra traffic and is recorded separately.
It can cause substantial client-observed terminal delay for large populations.

## Database reconciliation and exact timing

After the HTTP run, use the campaignId in `summary.json`. The following example
uses the local MySQL CLI and prompts for the isolated database password; substitute
the isolated DB name/user. Do not put a password into the script or command line.

```powershell
$trialRun = 'experiments/trial/results/baseline-100-seats'
$trialSummary = Get-Content "$trialRun/summary.json" -Raw | ConvertFrom-Json
$trialCampaign = [string]$trialSummary.campaignId
if ($trialCampaign -notmatch '^[a-zA-Z0-9-]{1,64}$') { throw 'Invalid campaign ID' }
$trialSql = "SET @campaign_id='$trialCampaign';`n" + (Get-Content experiments/trial/export_claims.sql -Raw)
mysql --host=127.0.0.1 --port=28306 --user=benchmark --password --database=benchmark --batch --raw --execute=$trialSql | Set-Content "$trialRun/claims.tsv" -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'SQL export failed' }
python experiments/trial/analyze_db.py --claims-tsv "$trialRun/claims.tsv" --http-summary "$trialRun/summary.json" --output "$trialRun/database-summary.json"
```

Execute `reconcile.sql` in the same way, prefixing `SET @campaign_id=...`, for
independent read-only inventory, duplicate, orphan and per-second order checks.
When no host MySQL CLI is installed, pipe the SQL into the isolated MySQL
container's CLI and save its `--batch --raw` output; never query the development DB.

`export_claims.sql` joins requests to orders. `analyze_db.py` reports:

- Actual DB order P95/P99: immutable `trial_order.created_at - request.created_at`.
- All terminal P95/P99: `request.updated_at - request.created_at`. A compensation
  update can move a rejected request's updated_at; successful order timing is preferred.
- Orders/s by one-second bucket, including empty buckets; whole-run successful-order
  throughput from first request to last order; an optional steady window `[5,25)`
  seconds after the first DB claim for a 30-second sustained run.
- Backlog drain after the last DB claim, stock conservation, zero oversell,
  duplicate actor/order/request/idempotency keys, terminal/order consistency,
  zero-price orders and the expected successful count `min(users,capacity)`.

The bounded steady interval is reported only if the actual submission span
covers it. A single 100-seat sellout cannot establish sustained successful-order
  capacity; use the 10,000-seat stages. DB timing excludes HTTP/network latency.
The database's created_at/updated_at values are assigned within a transaction,
not at commit acknowledgement; report them as server persistence timestamps,
with millisecond precision, rather than exact commit timestamps.
Both HTTP and database checks must pass before citing a correctness result.

## Artifacts and interpretation

`manifest.json` records run IDs, workload/configuration and campaign metadata.
`requests.jsonl` records every primary/retry HTTP call and generator scheduling lag.
`terminals.jsonl` records client-observed terminal states and previous nonterminal
observation times. `reconciliation.jsonl` is the inventory/backlog/order timeline.
`summary.json` reports admission P95/P99, client-observed terminal P95/P99, receipt
replay checks and sampled backlog drain. `database-summary.json` supplies exact
DB timing and the independent correctness checks. Never relabel observed HTTP
terminal delay as exact commit latency or accepted-request QPS as successful-order TPS.

If open-loop generator scheduling lag P95 exceeds 100 ms, the runner emits a
warning. Inspect client CPU/concurrency before claiming the configured offered
rate was sustained. Keep the raw data and repeat each stage at least three times
when establishing a resume result; report the run distribution, not its best value.

Offline verification (local HTTP fixture only, no production components):

```powershell
python -m unittest discover -s experiments/trial -p test_trial_tools.py -v
```
