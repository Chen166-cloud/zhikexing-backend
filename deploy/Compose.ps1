param([switch]$Wsl)
# 使用普通脚本参数，避免 Compose 的 -d 被 PowerShell 解释为 -Debug。
$ComposeArguments = $args
$Distribution = $env:ZHIKEXING_WSL_DISTRIBUTION
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
if ($Wsl) {
    # 由 WSL 解析实际挂载点，支持其他盘符和自定义 automount 配置。
    $distributionArguments = @()
    if ($Distribution) { $distributionArguments = @('--distribution', $Distribution) }
    $linuxRepository = & wsl @distributionArguments --exec wslpath -a -u $repository
    if ($LASTEXITCODE -ne 0) { throw '无法将当前仓库路径转换为 WSL 路径。' }
    $linuxRepository = $linuxRepository.Trim()
    $originalWslEnv = $env:WSLENV
    # 只转发明确设置的覆盖项；空变量会遮蔽 Compose .env 中的值。
    $forwarded = @('DASHSCOPE_API_KEY', 'AI_PROVIDER', 'ROCKETMQ_ENABLED', 'AGENT_RUNTIME_IMAGE') |
        Where-Object { [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($_, 'Process')) -eq $false } |
        ForEach-Object { "$_/u" }
    $env:WSLENV = (@($originalWslEnv -split ':') + $forwarded |
        Where-Object { $_ } | Select-Object -Unique) -join ':'
    try { & wsl @distributionArguments --user root --cd $linuxRepository --exec docker compose @ComposeArguments }
    finally { $env:WSLENV = $originalWslEnv }
}
else {
    Push-Location $repository
    try { & docker compose @ComposeArguments }
    finally { Pop-Location }
}
if ($LASTEXITCODE -ne 0) { throw "Docker Compose 执行失败：$LASTEXITCODE" }
