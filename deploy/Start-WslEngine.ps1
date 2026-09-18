param([string]$Distribution = $env:IIIP_WSL_DISTRIBUTION)
$ErrorActionPreference = 'Stop'
if (-not $Distribution) {
    # 未指定时采用开发者自己的默认发行版。
    $Distribution = & wsl --exec printenv WSL_DISTRO_NAME
    if ($LASTEXITCODE -ne 0) { throw '无法读取 WSL 默认发行版。' }
    $Distribution = $Distribution.Trim()
}
# systemd 服务本身不会让 WSL 保持运行；保留一个隐藏的普通 WSL 会话。
$distributionPattern = '(?:-d|--distribution)\s+"?' + [regex]::Escape($Distribution) + '"?\s'
$keepAlive = Get-CimInstance Win32_Process -Filter "Name = 'wsl.exe'" |
    Where-Object { $_.CommandLine -match $distributionPattern -and $_.CommandLine -like '*--exec /bin/sleep infinity*' }
if (-not $keepAlive) {
    Start-Process -FilePath wsl.exe -ArgumentList @('--distribution', "`"$Distribution`"", '--user', 'root', '--exec', '/bin/sleep', 'infinity') -WindowStyle Hidden
}
& wsl --distribution $Distribution --user root --exec systemctl start docker
if ($LASTEXITCODE -ne 0) { throw 'WSL Docker Engine 启动失败。' }
Write-Host "$Distribution Docker Engine 已启动，隐藏会话保持服务运行。"
