param([string]$BaseUrl = 'http://127.0.0.1:28080')
$ErrorActionPreference = 'Stop'
if ($BaseUrl -ne 'http://127.0.0.1:28080') { throw '该演练固定使用隔离测试应用28080端口。' }
function Call-Api([string]$Method, [string]$Path, $Body = $null) {
    $parameters = @{ Uri = "$BaseUrl$Path"; Method = $Method; Headers = $script:headers; ContentType = 'application/json' }
    if ($null -ne $Body) { $parameters.Body = ConvertTo-Json -InputObject $Body -Depth 12 -Compress }
    try { Invoke-RestMethod @parameters } catch { throw "$Method $Path 请求失败：$($_.Exception.Message)" }
}
$script:headers = @{}
$username = 'tr' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$registered = Call-Api POST '/user/register' @{userName=$username;password='TrialOnly123!'}
if ($registered.ok -ne 1) { throw '合成测试用户注册失败' }
$script:headers = @{Authorization=$registered.data}
$workspaces = Call-Api GET '/api/v1/workspaces'
$workspace = @($workspaces | Where-Object { $_.role -eq 'OWNER' })[0].id
$prefix = "/api/v1/workspaces/$workspace/trials"
function New-Campaign([string]$Title) {
    $value = Call-Api POST "$prefix/campaigns" @{
        title=$Title;courseId='10001';schoolId='10001';capacity=2
        startsAt=[DateTimeOffset]::UtcNow.AddSeconds(-5).ToString('o')
        endsAt=[DateTimeOffset]::UtcNow.AddMinutes(30).ToString('o')
    }
    Call-Api POST "$prefix/campaigns/$($value.id)/publish" | Out-Null
    return $value.id
}
$directCampaign = New-Campaign '合成数据：直接抢课验证'
$key = [Guid]::NewGuid().ToString()
$receipt = Call-Api POST "$prefix/campaigns/$directCampaign/claims" @{clientRequestId=$key}
$repeated = Call-Api POST "$prefix/campaigns/$directCampaign/claims" @{clientRequestId=$key}
if ($receipt.requestId -ne $repeated.requestId) { throw '重放未返回同一申请' }
$deadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
do {
    Start-Sleep -Milliseconds 250
    $result = Call-Api GET "$prefix/claims/$($receipt.requestId)"
} while ($result.status -in @('PENDING','RESERVED') -and [DateTimeOffset]::UtcNow -lt $deadline)
if ($result.status -ne 'SUCCEEDED' -or -not $result.orderId) { throw '直接抢课未创建订单' }

$agentCampaign = New-Campaign '合成数据：Agent免费试听抢课验证'
$conversation = Call-Api POST '/api/v1/conversations' @{workspaceId=$workspace;title='试听秒杀端到端演练'}
$submitted = Call-Api POST '/api/v1/runs' @{
    workspaceId=$workspace;conversationId=$conversation.id;clientRequestId=[Guid]::NewGuid().ToString()
    mode='agent';input='帮我领取免费试听名额';knowledgeBaseIds=@()
}
$runId = $submitted.runId
$deadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
do {
    Start-Sleep -Milliseconds 300
    $approvals = Call-Api GET "/api/v1/approvals?workspaceId=$workspace&runId=$runId"
} while ($approvals.Count -eq 0 -and [DateTimeOffset]::UtcNow -lt $deadline)
if ($approvals.Count -ne 1 -or $approvals[0].toolName -ne 'claim_trial') { throw 'Agent未生成试听审批' }
$approval = $approvals[0]
Call-Api POST "/api/v1/approvals/$($approval.id)/decision" @{
    workspaceId=$workspace;decision='APPROVED';expectedVersion=$approval.version
} | Out-Null
$deadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
do {
    Start-Sleep -Milliseconds 300
    $run = Call-Api GET "/api/v1/runs/$($runId)?workspaceId=$workspace"
} while ($run.status -notin @('SUCCEEDED','FAILED','CANCELLED') -and [DateTimeOffset]::UtcNow -lt $deadline)
if ($run.status -ne 'SUCCEEDED') { throw "Agent运行未完成：$($run.status)" }
$updated = (Call-Api GET "/api/v1/approvals?workspaceId=$workspace&runId=$runId")[0]
$claim = Call-Api GET "$prefix/claims/$($updated.result.requestId)"
if ($claim.status -ne 'SUCCEEDED' -or -not $claim.orderId) { throw 'Agent申请未落单' }
$ledger = Call-Api GET "$prefix/campaigns/$agentCampaign/reconciliation"
if (-not $ledger.databaseInvariantHolds) { throw '库存账不守恒' }
[ordered]@{
    test='live HTTP + MySQL + Redis + RocketMQ + Python LangGraph (fixture model)'
    directClaim=$result.status; repeatReturnsSameRequest=$true
    agentRun=$run.status; approvalType=$approval.toolName; agentClaim=$claim.status
    inventoryInvariant=$ledger.databaseInvariantHolds; realModelCalled=$false
} | ConvertTo-Json
