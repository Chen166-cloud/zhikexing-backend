param([switch]$Wsl)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$environmentPath = Join-Path $repository '.env'
if (Test-Path -LiteralPath $environmentPath) {
    throw '.env 已存在。为保留当前数据库凭据，本脚本不会覆盖它。'
}
function New-Secret {
    $bytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}
$content = Get-Content -LiteralPath (Join-Path $repository '.env.example')
$secretNames = @('MYSQL_ROOT_PASSWORD', 'MYSQL_PASSWORD', 'POSTGRES_PASSWORD', 'REDIS_PASSWORD',
    'RABBITMQ_PASSWORD', 'S3_SECRET_ACCESS_KEY', 'LANGFUSE_DB_PASSWORD', 'LANGFUSE_REDIS_PASSWORD',
    'CLICKHOUSE_PASSWORD', 'LANGFUSE_AUTH_SECRET', 'LANGFUSE_SALT', 'LANGFUSE_ENCRYPTION_KEY',
    'LANGFUSE_ADMIN_PASSWORD', 'GRAFANA_PASSWORD', 'AGENT_INTERNAL_TOKEN')
$result = foreach ($line in $content) {
    $name = ($line -split '=', 2)[0]
    if ($name -in $secretNames) { "$name=$(New-Secret)" }
    elseif ($name -eq 'LANGFUSE_PUBLIC_KEY') { "LANGFUSE_PUBLIC_KEY=pk-lf-$(New-Secret)" }
    elseif ($name -eq 'LANGFUSE_SECRET_KEY') { "LANGFUSE_SECRET_KEY=sk-lf-$(New-Secret)" }
    elseif ($name -eq 'FRONTEND_PATH') {
        if ($Wsl) { 'FRONTEND_PATH=/mnt/d/develop/web-intelligent-integrated-interaction-platform' }
        else { 'FRONTEND_PATH=D:/develop/web-intelligent-integrated-interaction-platform' }
    }
    elseif ($name -eq 'AGENT_RUNTIME_PATH') {
        if ($Wsl) { 'AGENT_RUNTIME_PATH=/mnt/d/develop/intelligent-agent-runtime' }
        else { 'AGENT_RUNTIME_PATH=D:/develop/intelligent-agent-runtime' }
    }
    else { $line }
}
# 模型密钥只通过当前进程环境传入，不打印，也不复制到模板。
[IO.File]::WriteAllLines($environmentPath, $result, [Text.UTF8Encoding]::new($false))
Write-Host '已生成 .env，全部基础设施凭据随机初始化。请在当前终端设置 DASHSCOPE_API_KEY 后启动应用。'
