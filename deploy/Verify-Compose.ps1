# 在完整 Compose 环境通过 Vue/Nginx 同源入口执行；会创建并保留合成用户、活动、申请与零元订单。
$ErrorActionPreference = 'Stop'
$BaseUrl = 'http://127.0.0.1:8088'
$script:headers = @{}

$landing = Invoke-WebRequest -Uri "$BaseUrl/" -Method GET
if ($landing.StatusCode -ne 200) { throw "前端入口未返回 200：$($landing.StatusCode)" }

function Call-Api([string]$Method, [string]$Path, $Body = $null) {
    $parameters = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $script:headers
        ContentType = 'application/json'
    }
    if ($null -ne $Body) {
        $parameters.Body = ConvertTo-Json -InputObject $Body -Depth 12 -Compress
    }
    try { Invoke-RestMethod @parameters }
    catch { throw "$Method $Path 请求失败：$($_.Exception.Message)" }
}

function New-Campaign([string]$Title, [string]$Prefix) {
    $campaign = Call-Api POST "$Prefix/campaigns" @{
        title = $Title
        courseId = '220331822288990210'
        schoolId = '220331822288990212'
        capacity = 2
        startsAt = [DateTimeOffset]::UtcNow.AddSeconds(-5).ToString('o')
        endsAt = [DateTimeOffset]::UtcNow.AddMinutes(30).ToString('o')
    }
    if (-not $campaign.id) { throw '活动创建未返回 ID' }
    $published = Call-Api POST "$Prefix/campaigns/$($campaign.id)/publish"
    if ($published.status -ne 'LIVE') { throw '活动未发布为 LIVE' }
    return [string]$campaign.id
}

function Wait-Claim([string]$Prefix, [string]$RequestId, [string]$CampaignId) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 300
        $claim = Call-Api GET "$Prefix/claims/$RequestId"
    } while ($claim.status -in @('PENDING', 'RESERVED') -and [DateTimeOffset]::UtcNow -lt $deadline)
    if ($claim.status -ne 'SUCCEEDED' -or -not $claim.orderId) {
        throw "活动 $CampaignId 的申请 $RequestId 未异步落单，状态：$($claim.status)"
    }
    if ([string]$claim.campaignId -ne $CampaignId) { throw '申请关联了错误的活动' }
    if ([long]$claim.amountCent -ne 0) { throw '试听订单金额不是零元' }
    return $claim
}

function Assert-Ledger([string]$Prefix, [string]$CampaignId, [string]$ExpectedRedisState) {
    $ledger = Call-Api GET "$Prefix/campaigns/$CampaignId/reconciliation"
    $capacity = [long]$ledger.capacity
    $remaining = [long]$ledger.remaining
    $confirmed = [long]$ledger.confirmedOrders
    if (-not $ledger.databaseInvariantHolds -or $capacity -ne ($remaining + $confirmed)) {
        throw "活动 $CampaignId 数据库库存账不守恒：$capacity != $remaining + $confirmed"
    }
    if ($confirmed -ne 1) { throw "活动 $CampaignId 预期恰有一笔零元订单，实际：$confirmed" }
    if ($null -eq $ledger.redis -or $ledger.redis.status -eq 'UNAVAILABLE') {
        throw "活动 $CampaignId 的 Redis 库存不可达"
    }
    if ($ledger.redis.state -ne $ExpectedRedisState) {
        throw "活动 $CampaignId 的 Redis 状态错误：$($ledger.redis.state)"
    }
    if ($null -eq $ledger.redis.remaining -or [long]$ledger.redis.remaining -ne $remaining) {
        throw "活动 $CampaignId 的 Redis 库存与数据库不一致：Redis=$($ledger.redis.remaining)，MySQL=$remaining"
    }
    return [ordered]@{
        capacity = $capacity
        remaining = $remaining
        confirmedOrders = $confirmed
        redisState = $ledger.redis.state
        redisRemaining = [long]$ledger.redis.remaining
    }
}

$username = 'cp' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$registered = Call-Api POST '/user/register' @{ userName = $username; password = 'TrialOnly123!' }
if ($registered.ok -ne 1 -or -not $registered.data) {
    throw "合成测试用户注册失败：ok=$($registered.ok)，msg=$($registered.msg)"
}
$script:headers = @{ Authorization = $registered.data }
$workspaces = @(Call-Api GET '/api/v1/workspaces')
$ownerWorkspace = @($workspaces | Where-Object { $_.role -eq 'OWNER' }) | Select-Object -First 1
if (-not $ownerWorkspace -or -not $ownerWorkspace.id) { throw '新用户没有 OWNER 工作空间' }
$workspace = [string]$ownerWorkspace.id
$prefix = "/api/v1/workspaces/$workspace/trials"

$directCampaign = New-Campaign '合成数据：Compose 直接抢课' $prefix
$key = [Guid]::NewGuid().ToString()
$receipt = Call-Api POST "$prefix/campaigns/$directCampaign/claims" @{ clientRequestId = $key }
$repeated = Call-Api POST "$prefix/campaigns/$directCampaign/claims" @{ clientRequestId = $key }
if (-not $receipt.requestId -or [string]$receipt.requestId -ne [string]$repeated.requestId) {
    throw '相同幂等键没有返回同一申请'
}
$directClaim = Wait-Claim $prefix ([string]$receipt.requestId) $directCampaign
$paused = Call-Api POST "$prefix/campaigns/$directCampaign/pause"
if ($paused.status -ne 'PAUSED') { throw '直接抢课活动未暂停' }
$directLedger = Assert-Ledger $prefix $directCampaign 'PAUSED'

$agentCampaign = New-Campaign '合成数据：Compose Agent 抢课' $prefix
$conversation = Call-Api POST '/api/v1/conversations' @{ workspaceId = $workspace; title = 'Compose 试听联调' }
if (-not $conversation.id) { throw '会话创建未返回 ID' }
$submitted = Call-Api POST '/api/v1/runs' @{
    workspaceId = $workspace
    conversationId = [string]$conversation.id
    clientRequestId = [Guid]::NewGuid().ToString()
    mode = 'agent'
    input = '帮我领取免费试听名额'
    knowledgeBaseIds = @()
}
if (-not $submitted.runId) { throw 'Agent 运行未返回 runId' }
$runId = [string]$submitted.runId
$deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
do {
    Start-Sleep -Milliseconds 400
    $approvals = @((Call-Api GET "/api/v1/approvals?workspaceId=$workspace&runId=$runId") |
        Where-Object { $null -ne $_ })
} while ($approvals.Count -eq 0 -and [DateTimeOffset]::UtcNow -lt $deadline)
if ($approvals.Count -ne 1 -or $approvals[0].toolName -ne 'claim_trial') {
    $first = if ($approvals.Count) { $approvals[0] } else { $null }
    $kind = if ($null -ne $first) { $first.GetType().FullName } else { 'null' }
    throw "Agent 未生成唯一试听审批；审批数：$($approvals.Count)，首项类型：$kind，工具：$($first.toolName)"
}
$approval = $approvals[0]
if ([string]$approval.args.campaignId -ne $agentCampaign) {
    throw "Agent 审批指向错误活动：$($approval.args.campaignId)，期望 $agentCampaign"
}
Call-Api POST "/api/v1/approvals/$($approval.id)/decision" @{
    workspaceId = $workspace
    decision = 'APPROVED'
    expectedVersion = $approval.version
} | Out-Null

$deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
do {
    Start-Sleep -Milliseconds 400
    $run = Call-Api GET "/api/v1/runs/$($runId)?workspaceId=$workspace"
} while ($run.status -notin @('SUCCEEDED', 'FAILED', 'CANCELLED') -and [DateTimeOffset]::UtcNow -lt $deadline)
if ($run.status -ne 'SUCCEEDED') { throw "Agent 运行未成功：$($run.status)" }
$updatedApprovals = @((Call-Api GET "/api/v1/approvals?workspaceId=$workspace&runId=$runId") |
    Where-Object { $null -ne $_ })
if ($updatedApprovals.Count -ne 1 -or -not $updatedApprovals[0].result.requestId) {
    throw 'Agent 审批结果未包含试听申请 ID'
}
$agentClaim = Wait-Claim $prefix ([string]$updatedApprovals[0].result.requestId) $agentCampaign
$agentLedger = Assert-Ledger $prefix $agentCampaign 'LIVE'

[ordered]@{
    environment = 'full Compose frontend proxy; real MySQL, Redis, RocketMQ and Java/Python HTTP; fixture model'
    directCampaignId = $directCampaign
    directClaim = $directClaim.status
    duplicateRequestIdStable = $true
    directLedger = $directLedger
    agentCampaignId = $agentCampaign
    agentRunId = $runId
    approvalCampaignId = [string]$approval.args.campaignId
    agentClaimCampaignId = [string]$agentClaim.campaignId
    agentClaim = $agentClaim.status
    agentLedger = $agentLedger
} | ConvertTo-Json -Depth 8
