$ErrorActionPreference = 'Stop'
# systemd 服务本身不会让 WSL 保持运行；保留一个隐藏的普通 WSL 会话。
$keepAlive = Get-CimInstance Win32_Process -Filter "Name = 'wsl.exe'" |
    Where-Object { $_.CommandLine -like '*Ubuntu-22.04*--exec /bin/sleep infinity*' }
if (-not $keepAlive) {
    Start-Process -FilePath wsl.exe -ArgumentList @('-d', 'Ubuntu-22.04', '-u', 'root', '--exec', '/bin/sleep', 'infinity') -WindowStyle Hidden
}
& wsl -d Ubuntu-22.04 -u root --exec systemctl start docker
if ($LASTEXITCODE -ne 0) { throw 'WSL Docker Engine 启动失败。' }
Write-Host 'Ubuntu-22.04 Docker Engine 已启动，隐藏会话保持服务运行。'
