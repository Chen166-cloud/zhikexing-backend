param([switch]$Wsl)
# 使用普通脚本参数，避免 Compose 的 -d 被 PowerShell 解释为 -Debug。
$ComposeArguments = $args
$Distribution = $env:IIIP_WSL_DISTRIBUTION
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
    # 只转发明确允许的模型配置，密钥不写入命令行和仓库文件。
    $env:WSLENV = (@($originalWslEnv -split ':') + @('DASHSCOPE_API_KEY/u', 'AI_PROVIDER/u', 'ROCKETMQ_ENABLED/u', 'AGENT_RUNTIME_IMAGE/u') |
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
