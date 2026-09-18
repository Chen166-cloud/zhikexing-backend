#!/usr/bin/env bash
# 在 Ubuntu 22.04/24.04/26.04 安装官方 Docker Engine，不操作 Docker Desktop 数据。
set -euo pipefail
if [[ $EUID -ne 0 ]]; then
  echo '请使用 sudo bash deploy/install-docker-ubuntu.sh'
  exit 1
fi
if command -v dockerd >/dev/null; then
  systemctl start docker
  docker version
  exit 0
fi
. /etc/os-release
if [[ "$ID" != ubuntu ]]; then
  echo '本脚本仅支持 Ubuntu。'
  exit 1
fi
apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker version
