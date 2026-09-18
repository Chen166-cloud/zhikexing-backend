param([switch]$Wsl)
# 使用普通脚本参数，避免 Compose 的 -d 被 PowerShell 解释为 -Debug。
$ComposeArguments = $args
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
if ($Wsl) {
    # 独立 WSL 引擎不依赖 Docker Desktop；Windows D: 路径对应 /mnt/d。
    $linuxRepository = '/mnt/' + $repository.Substring(0, 1).ToLowerInvariant() +
        $repository.Substring(2).Replace('\', '/')
    $originalWslEnv = $env:WSLENV
    # 只转发明确允许的模型配置，密钥不写入命令行和仓库文件。
    $env:WSLENV = (@($originalWslEnv -split ':') + @('DASHSCOPE_API_KEY/u', 'AI_PROVIDER/u', 'RABBITMQ_ENABLED/u', 'AGENT_RUNTIME_IMAGE/u') |
        Where-Object { $_ } | Select-Object -Unique) -join ':'
    try { & wsl -d Ubuntu-22.04 -u root --cd $linuxRepository --exec /usr/bin/docker compose @ComposeArguments }
    finally { $env:WSLENV = $originalWslEnv }
}
else {
    Push-Location $repository
    try { & docker compose @ComposeArguments }
    finally { Pop-Location }
}
if ($LASTEXITCODE -ne 0) { throw "Docker Compose 执行失败：$LASTEXITCODE" }
